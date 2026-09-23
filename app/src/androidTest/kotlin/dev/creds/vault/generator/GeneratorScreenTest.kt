package dev.creds.vault.generator

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.domain.generator.Capitalization
import dev.creds.vault.core.domain.generator.CharacterClass
import dev.creds.vault.core.domain.generator.Generated
import dev.creds.vault.core.domain.generator.GeneratorMode
import dev.creds.vault.core.domain.generator.GeneratorSettings
import dev.creds.vault.core.domain.generator.RandomOptions
import dev.creds.vault.core.domain.strength.PasswordStrength
import dev.creds.vault.core.model.GeneratedValue
import dev.creds.vault.items.MASK
import dev.creds.vault.items.VaultListTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The generator screen and panel, rendered in-process against hand-built state. */
@RunWith(AndroidJUnit4::class)
class GeneratorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val generated = GeneratorUiState(
        settingsLoaded = true,
        generated = Generated("Tr0ub4dor&3-horse", entropyBits = 128.4),
        strength = PasswordStrength(score = 4, guessesLog10 = 20.0, crackTimeDisplay = "centuries"),
    )

    @Test
    fun showsTheValueItsEntropyAndStrength() {
        setContent(generated)

        compose.onNodeWithTag(GeneratorTags.VALUE).assertTextEquals("Tr0ub4dor&3-horse")
        compose.onNodeWithTag(GeneratorTags.ENTROPY).assertTextEquals("128 bits of entropy")
        compose.onNodeWithText("Very strong · takes centuries to crack offline").assertIsDisplayed()
    }

    @Test
    fun regenerateCopyAndModeReport() {
        var regenerated = false
        var copied = false
        var mode: GeneratorMode? = null
        setContent(
            generated,
            GeneratorActions(onRegenerate = { regenerated = true }, onCopy = { copied = true }, onMode = { mode = it }),
        )

        compose.onNodeWithTag(GeneratorTags.REGENERATE).performClick()
        compose.onNodeWithTag(GeneratorTags.COPY).performClick()
        compose.onNodeWithTag(GeneratorTags.mode(GeneratorMode.PASSPHRASE)).performClick()

        assertEquals(true, regenerated)
        assertEquals(true, copied)
        assertEquals(GeneratorMode.PASSPHRASE, mode)
    }

    @Test
    fun theLastCharacterClassCannotBeSwitchedOff() {
        setContent(
            generated.copy(
                settings = GeneratorSettings(random = RandomOptions(classes = setOf(CharacterClass.DIGITS))),
            ),
        )

        compose.onNodeWithTag(GeneratorTags.characterClass(CharacterClass.DIGITS)).assertIsNotEnabled()
        // "At least one of each" means nothing with a single class, so it is not offered.
        compose.onAllNodesWithTag(GeneratorTags.REQUIRE_EACH).assertCountEquals(0)
    }

    @Test
    fun randomOptionsReport() {
        var toggled: CharacterClass? = null
        var ambiguous: Boolean? = null
        setContent(
            generated,
            GeneratorActions(onToggleClass = { toggled = it }, onExcludeAmbiguous = { ambiguous = it }),
        )

        compose.onNodeWithTag(GeneratorTags.characterClass(CharacterClass.SYMBOLS)).performClick()
        compose.onNodeWithTag(GeneratorTags.AMBIGUOUS).performClick()

        assertEquals(CharacterClass.SYMBOLS, toggled)
        assertEquals(true, ambiguous)
    }

    @Test
    fun passphraseOptionsReport() {
        var separator: String? = null
        var caps: Capitalization? = null
        var digit: Boolean? = null
        setContent(
            generated.copy(settings = GeneratorSettings(mode = GeneratorMode.PASSPHRASE)),
            GeneratorActions(
                onSeparator = { separator = it },
                onCapitalization = { caps = it },
                onIncludeDigit = { digit = it },
            ),
        )

        compose.onNodeWithTag(GeneratorTags.separator("Space")).performClick()
        compose.onNodeWithTag(GeneratorTags.capitalization(Capitalization.TITLE)).performClick()
        compose.onNodeWithTag(GeneratorTags.DIGIT).performClick()

        assertEquals(" ", separator)
        assertEquals(Capitalization.TITLE, caps)
        assertEquals(true, digit)
        compose.onAllNodesWithTag(GeneratorTags.AMBIGUOUS).assertCountEquals(0)
    }

    @Test
    fun historyStartsMaskedAndClearingAsksFirst() {
        var cleared = false
        val entry = GeneratedValue(id = 5, value = "old-generated", createdAt = System.currentTimeMillis())
        setContent(
            generated.copy(history = listOf(entry)),
            historyActions = GeneratorHistoryActions(onClear = { cleared = true }),
        )

        compose.onNodeWithTag(GeneratorTags.historyValue(5)).assertTextEquals(MASK)
        compose.onNodeWithTag(GeneratorTags.historyReveal(5)).performClick()
        compose.onNodeWithTag(GeneratorTags.historyValue(5)).assertTextEquals("old-generated")

        compose.onNodeWithTag(GeneratorTags.CLEAR_HISTORY).performClick()
        assertEquals(false, cleared)
        compose.onNodeWithTag(VaultListTags.CONFIRM).performClick()
        assertEquals(true, cleared)
    }

    @Test
    fun anEmptyHistorySaysSo() {
        setContent(generated)

        compose.onNodeWithText("Nothing yet.").assertIsDisplayed()
        compose.onAllNodesWithTag(GeneratorTags.CLEAR_HISTORY).assertCountEquals(0)
    }

    private fun setContent(
        state: GeneratorUiState,
        actions: GeneratorActions = GeneratorActions(),
        historyActions: GeneratorHistoryActions = GeneratorHistoryActions(),
    ) {
        compose.setContent {
            GeneratorScreen(state = state, actions = actions, historyActions = historyActions, onBack = {})
        }
    }
}
