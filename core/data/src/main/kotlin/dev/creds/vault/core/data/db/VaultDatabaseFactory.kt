package dev.creds.vault.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.creds.vault.core.crypto.VaultKey
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the SQLCipher-backed vault database.
 *
 * The database can only be opened while the vault is unlocked, because its passphrase is
 * derived from the vault key and nothing else. There is no fallback path and no stored
 * copy of the passphrase — lock the vault and this file becomes undecryptable noise.
 */
@Singleton
internal class VaultDatabaseFactory @Inject constructor(
    private val context: Context,
) {

    /**
     * Builds the database for an unlocked [vaultKey].
     *
     * The caller owns the returned database and must close it on lock.
     */
    fun open(vaultKey: VaultKey): CredsDatabase {
        SqlCipherNative.ensureLoaded()

        // HKDF hands back a fresh array each call, so this is already detached from the
        // vault key. That matters: SupportOpenHelperFactory stores the array *by
        // reference* and never zeroes it, so it stays live for as long as the helper
        // does, and wiping it here would break every subsequent open.
        val passphrase = vaultKey.databasePassphrase()

        return Room.databaseBuilder(context, CredsDatabase::class.java, CredsDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addCallback(FtsSchema.callback)
            // WAL is worth having for concurrent reads during an import. The inner
            // AES-GCM layer is what makes a stale WAL frame harmless.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // No fallbackToDestructiveMigration, ever. Dropping the table on a migration
            // failure would destroy the only copy of the user's vault.
            .build()
    }

    /** Whether a vault file exists yet. Drives setup versus unlock at launch. */
    fun exists(): Boolean = context.getDatabasePath(CredsDatabase.NAME).exists()

    /**
     * Deletes the vault file, including its WAL and journal sidecars.
     *
     * `deleteDatabase` is used rather than `File.delete` precisely because of those
     * sidecars: a leftover `-wal` holds recently written pages, so removing only the
     * main file would leave real vault data on disk.
     */
    fun delete(): Boolean = context.deleteDatabase(CredsDatabase.NAME)
}

/**
 * Loads SQLCipher's native library exactly once.
 *
 * `sqlcipher-android` 4.18.0 contains no `System.loadLibrary` call of its own — its
 * `SQLiteConnection`, `CursorWindow` and `SQLiteGlobal` declare native methods and
 * expect the host application to have loaded `libsqlcipher.so` first. Calling a native
 * method before this runs throws `UnsatisfiedLinkError`.
 */
internal object SqlCipherNative {

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }
}
