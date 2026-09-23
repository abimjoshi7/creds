package dev.creds.vault.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.domain.generator.CharacterClass
import dev.creds.vault.core.domain.generator.GeneratorSettings
import dev.creds.vault.core.domain.generator.PassphraseOptions
import dev.creds.vault.core.domain.generator.PinOptions
import dev.creds.vault.core.domain.generator.RandomOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.generatorDataStore: DataStore<Preferences> by preferencesDataStore("generator")

/**
 * How the user last set up the generator.
 *
 * Plain DataStore outside the vault, because none of it is secret: "20 characters, no
 * symbols" describes a policy, not a password. Generated values are never stored here.
 *
 * Enum values are stored by name and unknown names fall back to defaults, so renaming an
 * option in a later version degrades to the default rather than crashing on read.
 */
@Singleton
class GeneratorPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    val settings: Flow<GeneratorSettings> = context.generatorDataStore.data.map { prefs ->
        val defaults = GeneratorSettings()
        GeneratorSettings(
            mode = enumOr(prefs[Keys.MODE], defaults.mode),
            random = RandomOptions(
                length = prefs[Keys.RANDOM_LENGTH] ?: defaults.random.length,
                classes = prefs[Keys.RANDOM_CLASSES]
                    ?.mapNotNull { name -> CharacterClass.entries.firstOrNull { it.name == name } }
                    ?.toSet()
                    ?: defaults.random.classes,
                excludeAmbiguous = prefs[Keys.RANDOM_EXCLUDE_AMBIGUOUS] ?: defaults.random.excludeAmbiguous,
                requireEachClass = prefs[Keys.RANDOM_REQUIRE_EACH] ?: defaults.random.requireEachClass,
            ),
            passphrase = PassphraseOptions(
                words = prefs[Keys.PASSPHRASE_WORDS] ?: defaults.passphrase.words,
                separator = prefs[Keys.PASSPHRASE_SEPARATOR] ?: defaults.passphrase.separator,
                capitalization = enumOr(prefs[Keys.PASSPHRASE_CAPITALIZATION], defaults.passphrase.capitalization),
                includeDigit = prefs[Keys.PASSPHRASE_DIGIT] ?: defaults.passphrase.includeDigit,
            ),
            pin = PinOptions(length = prefs[Keys.PIN_LENGTH] ?: defaults.pin.length),
        )
    }

    suspend fun save(settings: GeneratorSettings) {
        context.generatorDataStore.edit { prefs ->
            prefs[Keys.MODE] = settings.mode.name
            prefs[Keys.RANDOM_LENGTH] = settings.random.effectiveLength
            prefs[Keys.RANDOM_CLASSES] = settings.random.effectiveClasses.mapTo(HashSet()) { it.name }
            prefs[Keys.RANDOM_EXCLUDE_AMBIGUOUS] = settings.random.excludeAmbiguous
            prefs[Keys.RANDOM_REQUIRE_EACH] = settings.random.requireEachClass
            prefs[Keys.PASSPHRASE_WORDS] = settings.passphrase.effectiveWords
            prefs[Keys.PASSPHRASE_SEPARATOR] = settings.passphrase.separator
            prefs[Keys.PASSPHRASE_CAPITALIZATION] = settings.passphrase.capitalization.name
            prefs[Keys.PASSPHRASE_DIGIT] = settings.passphrase.includeDigit
            prefs[Keys.PIN_LENGTH] = settings.pin.effectiveLength
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val RANDOM_LENGTH = intPreferencesKey("random_length")
        val RANDOM_CLASSES = stringSetPreferencesKey("random_classes")
        val RANDOM_EXCLUDE_AMBIGUOUS = booleanPreferencesKey("random_exclude_ambiguous")
        val RANDOM_REQUIRE_EACH = booleanPreferencesKey("random_require_each")
        val PASSPHRASE_WORDS = intPreferencesKey("passphrase_words")
        val PASSPHRASE_SEPARATOR = stringPreferencesKey("passphrase_separator")
        val PASSPHRASE_CAPITALIZATION = stringPreferencesKey("passphrase_capitalization")
        val PASSPHRASE_DIGIT = booleanPreferencesKey("passphrase_digit")
        val PIN_LENGTH = intPreferencesKey("pin_length")
    }
}
