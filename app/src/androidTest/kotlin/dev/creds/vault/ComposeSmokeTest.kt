package dev.creds.vault

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the Compose test infrastructure work on this device at all?
 *
 * Every attempt to run the real screen tests has hung on the first test with no crash,
 * no kill and no fatal — after ruling out a missing host activity, a genuine crash in the
 * strength estimator, the application class, and the keyguard. This renders a single
 * `Text` and asserts it: if that hangs too, the problem is the test infrastructure on
 * this device rather than anything in the screens, and chasing the screens is wasted
 * effort.
 *
 * Deliberately the smallest possible composition — no text fields, no progress
 * indicators, no state, nothing that could animate or fail to settle.
 */
@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersASingleText() {
        compose.setContent { Text("smoke") }

        compose.onNodeWithText("smoke").assertIsDisplayed()
    }
}

/**
 * The same smoke test against an explicitly named host activity.
 *
 * `createComposeRule()` launches a host activity the test framework picks;
 * `createAndroidComposeRule` names one. Running both isolates "the rule cannot get an
 * activity resumed" from "Compose never reaches idle", which the identical-looking hang
 * cannot otherwise distinguish.
 */
@RunWith(AndroidJUnit4::class)
class ComposeSmokeActivityRuleTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersASingleText() {
        compose.setContent { Text("smoke-activity") }

        compose.onNodeWithText("smoke-activity").assertIsDisplayed()
    }
}
