package dev.creds.vault.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.data.prefs.LockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen's guard rails, rendered in-process. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(policy: LockPolicy = LockPolicy(), actions: SettingsActions, passwordChange: PasswordChangeState? = null) =
        compose.setContent {
            SettingsScreen(policy = policy, biometricAvailable = false, passwordChange = passwordChange, actions = actions)
        }

    @Test
    fun erasingTheVaultIsOnlyTurnedOnAfterConfirming() {
        var wipe: Int? = null
        show(actions = SettingsActions(onWipe = { wipe = it }))

        compose.onNodeWithTag(SettingsTags.WIPE).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.choice(10)).performClick()
        assertNull("chosen but not yet confirmed", wipe)

        compose.onNodeWithTag(SettingsTags.CONFIRM_WIPE).performClick()
        assertEquals(10, wipe)
    }

    @Test
    fun allowingScreenshotsIsAskedAboutAndCanBeKeptBlocked() {
        var secure: Boolean? = null
        show(actions = SettingsActions(onSecureFlag = { secure = it }))

        compose.onNodeWithTag(SettingsTags.SECURE).assertIsOn().performClick()
        compose.onNodeWithText("Keep blocked").performClick()
        assertNull(secure)

        compose.onNodeWithTag(SettingsTags.SECURE).performClick()
        compose.onNodeWithText("Allow").performClick()
        assertEquals(false, secure)
    }

    @Test
    fun aPasswordChangeErrorIsShownInTheDialog() {
        show(
            actions = SettingsActions(),
            passwordChange = PasswordChangeState(current = "x", error = "Current password is wrong"),
        )
        compose.onNodeWithTag(SettingsTags.PASSWORD_ERROR).assertIsDisplayed()
        compose.onNode(hasText("There is no reset", substring = true)).assertIsDisplayed()
    }
}
