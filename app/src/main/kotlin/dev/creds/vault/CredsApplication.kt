package dev.creds.vault

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.creds.vault.audit.AuditCoordinator
import dev.creds.vault.lock.LockCoordinator
import javax.inject.Inject

@HiltAndroidApp
class CredsApplication : Application() {

    /**
     * Started here rather than from the activity because two of the three lock triggers
     * — backgrounding and the screen switching off — have to keep working while no
     * activity is resumed.
     */
    @Inject
    lateinit var lockCoordinator: LockCoordinator

    /** Scores passwords in the background while the vault is open; idle while locked. */
    @Inject
    lateinit var auditCoordinator: AuditCoordinator

    override fun onCreate() {
        super.onCreate()
        lockCoordinator.start()
        auditCoordinator.start()
    }
}
