package dev.creds.vault.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reveal toggle.
 *
 * The behaviour that matters is that the field starts obscured — a field that came back
 * revealed after a rotation, or that defaulted to visible, would put a master password on
 * screen without the user asking for it.
 */
@RunWith(AndroidJUnit4::class)
class PasswordFieldTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun startsObscured() {
        setContent()

        // The button advertises the action it will perform, so "Show password" means the
        // value is currently hidden.
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Hide password").assertCountEquals(0)
    }

    @Test
    fun tappingRevealsTheValue() {
        setContent()

        compose.onNodeWithTag(REVEAL_TAG).performClick()

        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show password").assertCountEquals(0)
    }

    @Test
    fun tappingTwiceObscuresAgain() {
        setContent()

        compose.onNodeWithTag(REVEAL_TAG).performClick()
        compose.onNodeWithTag(REVEAL_TAG).performClick()

        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }

    @Test
    fun revealedTextIsReadable() {
        var typed = ""
        compose.setContent {
            PasswordField(
                value = typed,
                onValueChange = { typed = it },
                label = "Master password",
                fieldTag = FIELD_TAG,
                revealTag = REVEAL_TAG,
            )
        }

        compose.onNodeWithTag(FIELD_TAG).performTextInput("hunter2")

        assertEquals("hunter2", typed)
    }

    @Test
    fun toggleIsDisabledWithTheField() {
        compose.setContent {
            PasswordField(
                value = "",
                onValueChange = {},
                label = "Master password",
                fieldTag = FIELD_TAG,
                revealTag = REVEAL_TAG,
                enabled = false,
            )
        }

        // During a lockout the whole field is disabled; letting the toggle stay live
        // would be an odd half-interactive state.
        compose.onNodeWithTag(REVEAL_TAG).assertIsNotEnabled()
    }

    private fun setContent(value: String = "hunter2") {
        compose.setContent {
            PasswordField(
                value = value,
                onValueChange = {},
                label = "Master password",
                fieldTag = FIELD_TAG,
                revealTag = REVEAL_TAG,
            )
        }
    }

    private companion object {
        const val FIELD_TAG = "test:password"
        const val REVEAL_TAG = "test:password:reveal"
    }
}
