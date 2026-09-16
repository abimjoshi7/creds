package dev.creds.vault.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the lock policy: backgrounding, screen off, and idle timeout.
 *
 * All three timings use [SystemClock.elapsedRealtime] rather than wall-clock time.
 * `System.currentTimeMillis` can be moved by the user or by an NTP correction, and a
 * clock jumped backwards would postpone a lock indefinitely — the one direction an
 * attacker would want.
 *
 * Every path ends in [VaultManager.lock], which is idempotent, so overlapping triggers
 * are harmless.
 */
@Singleton
class LockCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
    private val lockPreferences: LockPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob())

    private var idleWatch: Job? = null

    @Volatile
    private var lastInteractionAt: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var backgroundedAt: Long? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            scope.launch {
                if (lockPreferences.policy.first().lockOnScreenOff) vaultManager.lock()
            }
        }
    }

    /** Called once from the application. */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        startIdleWatch()
    }

    /** Called from the activity's `onUserInteraction`, which resets the idle timer. */
    fun noteInteraction() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    private val processObserver = object : DefaultLifecycleObserver {

        override fun onStop(owner: LifecycleOwner) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }

        override fun onStart(owner: LifecycleOwner) {
            val since = backgroundedAt ?: return
            backgroundedAt = null
            noteInteraction()

            scope.launch {
                val policy = lockPreferences.policy.first()
                if (!policy.lockOnBackground) return@launch

                // The grace period is why autofill works at all: filling a form bounces
                // the user out to another app and straight back, and locking on that
                // round-trip would make every fill require a re-unlock.
                val away = SystemClock.elapsedRealtime() - since
                if (away >= policy.backgroundGraceMillis) vaultManager.lock()
            }
        }
    }

    /**
     * Polls rather than scheduling a single alarm.
     *
     * The timeout is a user preference that can change while the vault is open, and a
     * scheduled alarm would have to be cancelled and rebuilt on every change and every
     * interaction. A cheap check twice a minute is simpler and cannot leave a stale alarm
     * behind.
     *
     * The loop runs only while the vault is actually unlocked. An unconditional
     * `while (true)` in `Application.onCreate` would tick for the entire life of the
     * process — including the overwhelmingly common case of the app sitting locked — to
     * check a timeout that cannot have expired, and on a scope nothing ever cancels.
     */
    private fun startIdleWatch() {
        idleWatch?.cancel()
        idleWatch = scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                if (!vaultManager.isUnlocked) continue

                val policy = lockPreferences.policy.first()
                val idleFor = SystemClock.elapsedRealtime() - lastInteractionAt
                if (idleFor >= policy.idleTimeoutMillis) vaultManager.lock()
            }
        }
    }

    /** Stops all background work. Exists so tests and teardown can leave nothing running. */
    fun stop() {
        idleWatch?.cancel()
        idleWatch = null
        runCatching { context.unregisterReceiver(screenOffReceiver) }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
    }

    private companion object {
        const val IDLE_CHECK_INTERVAL_MS = 30_000L
    }
}
