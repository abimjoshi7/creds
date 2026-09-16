package dev.creds.vault.unlock

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unlock screen rendering.
 *
 * Same reasoning as the setup tests: this screen is invisible to screenshots and to
 * uiautomator on the test device, so in-process Compose tests are the only way to know
 * it draws anything at all.
 */
@RunWith(AndroidJUnit4::class)
class UnlockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersTheUnlockScreen() {
        setContent(UnlockUiState())

        compose.onNodeWithText("Creds").assertIsDisplayed()
        compose.onNodeWithTag(UnlockTags.PASSWORD).assertIsDisplayed()
        compose.onNodeWithTag(UnlockTags.SUBMIT).assertIsDisplayed()
    }

    @Test
    fun submitIsDisabledWithAnEmptyPassword() {
        setContent(UnlockUiState())

        compose.onNodeWithTag(UnlockTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun submitIsEnabledOnceTyped() {
        setContent(UnlockUiState(password = "hunter2"))

        compose.onNodeWithTag(UnlockTags.SUBMIT).assertIsEnabled()
    }

    @Test
    fun showsAnIncorrectPasswordError() {
        setContent(UnlockUiState(error = "Incorrect master password"))

        compose.onNodeWithText("Incorrect master password").assertIsDisplayed()
    }

    @Test
    fun explainsTheLockoutInsteadOfJustDisablingTheButton() {
        setContent(UnlockUiState(password = "hunter2", lockedOutMillis = 4_000))

        // A dead button with no explanation reads as a broken app.
        compose.onNodeWithText("Too many attempts. Try again in 4s.").assertIsDisplayed()
        compose.onNodeWithTag(UnlockTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun roundsTheLockoutUpSoItNeverShowsZeroSeconds() {
        setContent(UnlockUiState(password = "x", lockedOutMillis = 1))

        compose.onNodeWithText("Too many attempts. Try again in 1s.").assertIsDisplayed()
    }

    @Test
    fun hidesBiometricsWhenNotEnrolled() {
        setContent(UnlockUiState(biometricOffered = false), biometricEnabled = false)

        compose.onAllNodesWithText("Use biometrics").assertCountEquals(0)
    }

    @Test
    fun offersBiometricsWhenEnrolled() {
        setContent(UnlockUiState(biometricOffered = true), biometricEnabled = true)

        compose.onNodeWithTag(UnlockTags.BIOMETRIC).assertIsDisplayed()
    }

    @Test
    fun biometricButtonInvokesTheCallback() {
        var tapped = false
        compose.setContent {
            UnlockScreen(
                state = UnlockUiState(biometricOffered = true),
                biometricEnabled = true,
                onPasswordChange = {},
                onSubmit = {},
                onBiometric = { tapped = true },
            )
        }

        compose.onNodeWithTag(UnlockTags.BIOMETRIC).performClick()

        assertTrue(tapped)
    }

    @Test
    fun showsProgressWhileUnlocking() {
        setContent(UnlockUiState(password = "hunter2", busy = true))

        compose.onNodeWithText("Unlocking…").assertIsDisplayed()
    }

    @Test
    fun passwordStartsObscuredAndCanBeRevealed() {
        setContent(UnlockUiState(password = "hunter2"))

        compose.onNodeWithTag(UnlockTags.PASSWORD_REVEAL).assertIsDisplayed()
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()

        compose.onNodeWithTag(UnlockTags.PASSWORD_REVEAL).performClick()

        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
    }

    private fun setContent(state: UnlockUiState, biometricEnabled: Boolean = false) {
        compose.setContent {
            UnlockScreen(
                state = state,
                biometricEnabled = biometricEnabled,
                onPasswordChange = {},
                onSubmit = {},
                onBiometric = {},
            )
        }
    }
}
