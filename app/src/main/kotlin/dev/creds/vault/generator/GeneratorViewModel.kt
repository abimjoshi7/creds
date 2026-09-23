package dev.creds.vault.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.prefs.GeneratorPreferences
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.data.vault.VaultManager
import dev.creds.vault.core.domain.generator.Capitalization
import dev.creds.vault.core.domain.generator.CharacterClass
import dev.creds.vault.core.domain.generator.Generated
import dev.creds.vault.core.domain.generator.GeneratorMode
import dev.creds.vault.core.domain.generator.GeneratorSettings
import dev.creds.vault.core.domain.generator.PasswordGenerator
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import dev.creds.vault.core.model.GeneratedValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The generator: options, the current value, its strength, and recent values taken.
 *
 * Shared by the generator screen and the editor's "generate" sheet. Only the screen asks
 * for [watchHistory]; the sheet has no reason to decrypt twenty old passwords.
 *
 * A generated value is a secret the moment it exists, so it follows the vault like
 * everything else plaintext: a lock clears it and the history, and the next unlock draws
 * a fresh one rather than bringing the old one back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val vaultManager: VaultManager,
    private val preferences: GeneratorPreferences,
    private val generator: PasswordGenerator,
    private val estimator: PasswordStrengthEstimator,
) : ViewModel() {

    private val _state = MutableStateFlow(GeneratorUiState())
    val state: StateFlow<GeneratorUiState> = _state.asStateFlow()

    private var generation: Job? = null
    private var historyWatch: Job? = null
    private var pendingSave: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(settings = preferences.settings.first(), settingsLoaded = true) }
            vaultManager.repository.collect { repository ->
                if (repository == null) {
                    generation?.cancel()
                    _state.update { it.copy(generated = null, strength = null, history = emptyList()) }
                } else if (_state.value.generated == null) {
                    regenerate()
                }
            }
        }

        // zxcvbn can take tens of milliseconds on a long value, so it runs off the main
        // thread and a newer value cancels the estimate for an older one.
        viewModelScope.launch {
            _state.map { it.generated?.value }.distinctUntilChanged().collectLatest { value ->
                val strength = value?.let { estimate(it) }
                _state.update { if (it.generated?.value == value) it.copy(strength = strength) else it }
            }
        }
    }

    fun regenerate() {
        val settings = _state.value.settings
        generation?.cancel()
        generation = viewModelScope.launch {
            // The first passphrase parses the wordlist; keep that off the main thread too.
            val generated = withContext(Dispatchers.Default) { generator.generate(settings) }
            if (vaultManager.isUnlocked) _state.update { it.copy(generated = generated) }
        }
    }

    fun setMode(mode: GeneratorMode) = updateSettings { it.copy(mode = mode) }

    fun setLength(length: Int) = updateSettings { it.copy(random = it.random.copy(length = length)) }

    /** Refuses to switch off the last class: an empty alphabet is not a setting. */
    fun toggleClass(characterClass: CharacterClass) = updateSettings { settings ->
        val classes = settings.random.effectiveClasses
        val next = if (characterClass in classes) classes - characterClass else classes + characterClass
        if (next.isEmpty()) settings else settings.copy(random = settings.random.copy(classes = next))
    }

    fun setExcludeAmbiguous(value: Boolean) =
        updateSettings { it.copy(random = it.random.copy(excludeAmbiguous = value)) }

    fun setRequireEachClass(value: Boolean) =
        updateSettings { it.copy(random = it.random.copy(requireEachClass = value)) }

    fun setWords(words: Int) = updateSettings { it.copy(passphrase = it.passphrase.copy(words = words)) }

    fun setSeparator(separator: String) =
        updateSettings { it.copy(passphrase = it.passphrase.copy(separator = separator)) }

    fun setCapitalization(capitalization: Capitalization) =
        updateSettings { it.copy(passphrase = it.passphrase.copy(capitalization = capitalization)) }

    fun setIncludeDigit(value: Boolean) =
        updateSettings { it.copy(passphrase = it.passphrase.copy(includeDigit = value)) }

    fun setPinLength(length: Int) = updateSettings { it.copy(pin = it.pin.copy(length = length)) }

    /**
     * The current value was copied or put into an item. Only then is it remembered: the
     * values someone scrolled past while dragging a slider were never at risk of being
     * set anywhere, and would push the one that was out of the list.
     */
    fun onTaken() {
        val value = _state.value.generated?.value ?: return
        mutate { repository, vaultKey -> repository.recordGenerated(vaultKey, value, now()) }
    }

    /** Starts keeping [GeneratorUiState.history] current. Idempotent. */
    fun watchHistory() {
        if (historyWatch != null) return
        historyWatch = viewModelScope.launch {
            vaultManager.repository
                .flatMapLatest { repository -> repository?.observeGeneratedHistoryChanges() ?: flowOf(null) }
                .collectLatest { signal ->
                    val history = if (signal == null) {
                        emptyList()
                    } else {
                        vaultManager.withUnlocked { repository, vaultKey ->
                            repository.generatedHistory(vaultKey, now())
                        }.orEmpty()
                    }
                    _state.update { it.copy(history = history) }
                }
        }
    }

    fun deleteHistoryEntry(id: Long) = mutate { repository, _ -> repository.deleteGenerated(id) }

    fun clearHistory() = mutate { repository, _ -> repository.clearGenerated() }

    private fun updateSettings(transform: (GeneratorSettings) -> GeneratorSettings) {
        val current = _state.value.settings
        val next = transform(current)
        if (next == current) return
        _state.update { it.copy(settings = next) }
        // A slider drag changes settings many times a second; only the value it settles
        // on is worth writing.
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            preferences.save(next)
        }
        regenerate()
    }

    private suspend fun estimate(value: String): PasswordStrength = withContext(Dispatchers.Default) {
        val chars = value.toCharArray()
        try {
            estimator.estimate(chars)
        } finally {
            chars.wipe()
        }
    }

    /** See `VaultListViewModel.mutate`. */
    private fun mutate(block: suspend (VaultRepository, VaultKey) -> Unit) {
        viewModelScope.launch {
            try {
                vaultManager.withUnlocked(block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (vaultManager.isUnlocked) throw e
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}

data class GeneratorUiState(
    val settings: GeneratorSettings = GeneratorSettings(),
    val settingsLoaded: Boolean = false,
    val generated: Generated? = null,
    val strength: PasswordStrength? = null,
    val history: List<GeneratedValue> = emptyList(),
)
