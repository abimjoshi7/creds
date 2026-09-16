package dev.creds.vault

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumentation runner that swaps in a plain [Application].
 *
 * The UI tests exercise individual composables, not the application graph, but the
 * default runner still instantiates the real [CredsApplication] — which is
 * `@HiltAndroidApp` and starts the lock coordinator's background work in `onCreate`.
 * That pulls Hilt, a process-lifecycle observer, a broadcast receiver and a long-running
 * coroutine into every test that only wanted to render a text field.
 *
 * Substituting a bare Application keeps these tests measuring what they claim to
 * measure. Tests that genuinely need the real graph would use Hilt's own test
 * application instead, and none here do.
 */
class CredsTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application =
        // super, not a bare call: an unqualified newApplication(...) here dispatches
        // virtually back into this override and recurses until the stack blows, killing
        // the instrumentation before a single test runs.
        super.newApplication(classLoader, Application::class.java.name, context)
}
