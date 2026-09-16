package dev.creds.vault.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.domain.strength.PasswordStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup screen rendering.
 *
 * These exist because nothing else can see this screen: `FLAG_SECURE` blocks screenshots,
 * and the test device's OEM build blocks both uiautomator and adb input injection. A
 * Compose test runs in-process and is unaffected by all three.
 *
 * The specific failure they rule out is a blank screen. The root state machine draws an
 * empty box while it decides which destination to show, and "launched without crashing"
 * looks identical to "stuck rendering nothing".
 */
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersTheSetupScreen() {
        setContent(SetupUiState())

        compose.onNodeWithText("Create your vault").assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.PASSWORD).assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.CONFIRM).assertIsDisplayed()
        compose.onNodeWithTag(SetupTags.SUBMIT).assertIsDisplayed()
    }

    @Test
    fun statesThereIsNoPasswordReset() {
        setContent(SetupUiState())

        // The single most important fact about this app. If this ever silently
        // disappears, someone eventually loses a vault.
        compose.onNodeWithText("There is no password reset").assertIsDisplayed()
    }

    @Test
    fun submitIsDisabledWhenBothFieldsAreEmpty() {
        setContent(SetupUiState())

        compose.onNodeWithTag(SetupTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun submitIsDisabledWhenOnlyThePasswordIsFilled() {
        // Two tests rather than two setContent calls in one: the rule's host activity
        // holds a single composition for the whole test, so a second setContent throws
        // "has already set content".
        setContent(SetupUiState(password = "correct horse", confirm = ""))

        compose.onNodeWithTag(SetupTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun submitIsEnabledWhenBothFieldsAreFilled() {
        setContent(SetupUiState(password = "correct horse", confirm = "correct horse"))

        compose.onNodeWithTag(SetupTags.SUBMIT).assertIsEnabled()
    }

    @Test
    fun showsTheStrengthReadoutOnceTyping() {
        setContent(
            SetupUiState(
                password = "correct horse battery staple",
                strength = PasswordStrength(
                    score = 4,
                    guessesLog10 = 20.0,
                    crackTimeDisplay = "centuries",
                ),
            ),
        )

        compose.onNodeWithText("Very strong · takes centuries to crack offline")
            .assertIsDisplayed()
    }

    @Test
    fun hidesTheStrengthReadoutWhileEmpty() {
        setContent(SetupUiState())

        compose.onAllNodesWithText("Very weak · takes instantly to crack offline")
            .assertCountEquals(0)
    }

    @Test
    fun showsTheValidationError() {
        setContent(SetupUiState(password = "abc", confirm = "abd", error = "Passwords do not match"))

        compose.onNodeWithText("Passwords do not match").assertIsDisplayed()
    }

    @Test
    fun showsProgressWhileDerivingTheKey() {
        setContent(SetupUiState(password = "a", confirm = "a", busy = true))

        // Argon2id at 64MiB is slow enough that silence would read as a hang.
        compose.onNodeWithText("Deriving key…").assertIsDisplayed()
        compose.onAllNodesWithText("Create vault").assertCountEquals(0)
    }

    @Test
    fun hidesBiometricOptionWhenTheDeviceCannotDoIt() {
        setContent(SetupUiState(), biometricAvailable = false)

        compose.onAllNodesWithText("Unlock with biometrics").assertCountEquals(0)
    }

    @Test
    fun offersBiometricOptionWhenAvailable() {
        setContent(SetupUiState(), biometricAvailable = true)

        compose.onNodeWithText("Unlock with biometrics").assertIsDisplayed()
    }

    @Test
    fun forwardsTypingToTheCallback() {
        var typed = ""
        compose.setContent {
            SetupScreen(
                state = SetupUiState(),
                biometricAvailable = false,
                onPasswordChange = { typed = it },
                onConfirmChange = {},
                onBiometricToggle = {},
                onSubmit = {},
            )
        }

        compose.onNodeWithTag(SetupTags.PASSWORD).performTextInput("hunter2")

        assertEquals("hunter2", typed)
    }

    @Test
    fun submitInvokesTheCallback() {
        var submitted = false
        compose.setContent {
            SetupScreen(
                state = SetupUiState(password = "a", confirm = "a"),
                biometricAvailable = false,
                onPasswordChange = {},
                onConfirmChange = {},
                onBiometricToggle = {},
                onSubmit = { submitted = true },
            )
        }

        compose.onNodeWithTag(SetupTags.SUBMIT).performClick()

        assertTrue(submitted)
    }

    @Test
    fun bothPasswordFieldsStartObscured() {
        setContent(SetupUiState(password = "hunter2", confirm = "hunter2"))

        // Two fields, so two "Show password" buttons and no "Hide password" anywhere.
        compose.onAllNodesWithContentDescription("Show password").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Hide password").assertCountEquals(0)
    }

    @Test
    fun revealingThePasswordDoesNotRevealTheConfirmField() {
        setContent(SetupUiState(password = "hunter2", confirm = "hunter2"))

        compose.onNodeWithTag(SetupTags.PASSWORD_REVEAL).performClick()

        // PasswordField keeps its reveal flag internally, so a shared or hoisted flag
        // would flip both fields at once. Exactly one should be revealed.
        compose.onAllNodesWithContentDescription("Hide password").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Show password").assertCountEquals(1)
    }

    @Test
    fun confirmFieldTogglesIndependently() {
        setContent(SetupUiState(password = "hunter2", confirm = "hunter2"))

        compose.onNodeWithTag(SetupTags.CONFIRM_REVEAL).performClick()

        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show password").assertCountEquals(1)
    }

    private fun setContent(state: SetupUiState, biometricAvailable: Boolean = false) {
        compose.setContent {
            SetupScreen(
                state = state,
                biometricAvailable = biometricAvailable,
                onPasswordChange = {},
                onConfirmChange = {},
                onBiometricToggle = {},
                onSubmit = {},
            )
        }
    }
}
