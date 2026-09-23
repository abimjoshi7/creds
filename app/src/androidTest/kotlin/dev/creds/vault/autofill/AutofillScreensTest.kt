package dev.creds.vault.autofill

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.creds.vault.core.model.Template
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Autofill's picker, save and settings screens, rendered in-process. */
@RunWith(AndroidJUnit4::class)
class AutofillScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val trusted = PickerRow("t", "Bank", "alice", Template.LOGIN, PickVerdict.TRUSTED)
    private val unconfirmed = PickerRow("u", "Mail", "bob", Template.LOGIN, PickVerdict.UNCONFIRMED)
    private val conflict = PickerRow("c", "Shop", "carol", Template.LOGIN, PickVerdict.CONFLICT)

    @Test
    fun pickingReportsTheItem() {
        var picked: String? = null
        setPicker(AutofillPickerUiState(originName = "Bank App", rows = listOf(trusted, unconfirmed)), AutofillPickerActions(onPick = { picked = it }))

        compose.onNodeWithText("Suggested here").assertIsDisplayed()
        compose.onNodeWithTag(AutofillTags.row("u")).performClick()

        assertEquals("u", picked)
    }

    @Test
    fun searchNarrowsByTitleOrSubtitle() {
        setPicker(AutofillPickerUiState(rows = listOf(trusted, unconfirmed), query = "bob"))

        compose.onNodeWithTag(AutofillTags.row("u")).assertIsDisplayed()
        compose.onAllNodesWithTag(AutofillTags.row("t")).assertCountEquals(0)
    }

    @Test
    fun confirmingAnAppNamesItsSigningKey() {
        var confirmed: String? = null
        setPicker(
            AutofillPickerUiState(originName = "Bank App", rows = listOf(unconfirmed), confirm = unconfirmed),
            AutofillPickerActions(onConfirm = { confirmed = it }),
            fingerprint = "AB12 CD34…",
        )

        compose.onNodeWithText("Use “Mail” here?").assertIsDisplayed()
        compose.onNode(hasText("AB12 CD34…", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag(AutofillTags.CONFIRM).performClick()

        assertEquals("u", confirmed)
    }

    @Test
    fun confirmingASiteNamesTheSite() {
        setPicker(AutofillPickerUiState(originName = "bank.com", isWeb = true, rows = listOf(unconfirmed), confirm = unconfirmed))

        compose.onNode(hasText("Creds will remember bank.com for this item", substring = true)).assertIsDisplayed()
    }

    @Test
    fun aConflictIsRefusedWithAReason() {
        setPicker(AutofillPickerUiState(originName = "Shop", rows = listOf(conflict), refused = conflict))

        compose.onNodeWithText("Not filling “Shop”").assertIsDisplayed()
        compose.onNodeWithTag(AutofillTags.REFUSED).assertIsDisplayed()
        compose.onAllNodesWithTag(AutofillTags.CONFIRM).assertCountEquals(0)
    }

    @Test
    fun saveOffersUpdatingAMatchOrSavingNew() {
        var updated: String? = null
        var savedNew = false
        compose.setContent {
            AutofillSaveScreen(
                state = AutofillSaveUiState(
                    loaded = true,
                    originName = "bank.com",
                    title = "bank.com",
                    username = "alice",
                    matches = listOf(SaveMatch("m", "Bank")),
                ),
                actions = AutofillSaveActions(onUpdate = { updated = it }, onSaveNew = { savedNew = true }),
            )
        }

        compose.onNodeWithText("Save login for bank.com?").assertIsDisplayed()
        compose.onNodeWithTag(AutofillTags.update("m")).performClick()
        compose.onNodeWithTag(AutofillTags.SAVE_NEW).assert(hasText("Save as new item")).performClick()

        assertEquals("m", updated)
        assertEquals(true, savedNew)
        // The captured password itself is never on this screen.
        compose.onAllNodesWithTag("autofill:save:password").assertCountEquals(0)
    }

    @Test
    fun savingFromAnAppShowsTheKeyItWillBeTrustedBy() {
        compose.setContent {
            AutofillSaveScreen(
                state = AutofillSaveUiState(loaded = true, originName = "Bank App", title = "Bank App", fingerprint = "AB12 CD34…"),
                actions = AutofillSaveActions(),
            )
        }

        compose.onNode(hasText("signed with key AB12 CD34…", substring = true)).assertIsDisplayed()
    }

    @Test
    fun settingsOfferEnablingOnlyWhenDisabled() {
        var enabled = false
        compose.setContent { AutofillSettingsScreen(status = AutofillStatus.DISABLED, onBack = {}, onEnable = { enabled = true }) }

        compose.onNodeWithTag(AutofillSettingsTags.ENABLE).performClick()

        assertEquals(true, enabled)
    }

    private fun setPicker(state: AutofillPickerUiState, actions: AutofillPickerActions = AutofillPickerActions(), fingerprint: String? = null) {
        compose.setContent { AutofillPickerScreen(state = state, appFingerprint = fingerprint, actions = actions) }
    }
}
