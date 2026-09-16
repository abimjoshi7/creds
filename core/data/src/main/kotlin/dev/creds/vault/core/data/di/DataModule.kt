package dev.creds.vault.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.creds.vault.core.crypto.BiometricKeyStore
import dev.creds.vault.core.crypto.VaultKeySealer
import dev.creds.vault.core.crypto.VaultSession
import dev.creds.vault.core.data.db.VaultDatabaseFactory
import dev.creds.vault.core.data.prefs.LockPreferences
import dev.creds.vault.core.data.prefs.VaultKeyStore
import dev.creds.vault.core.data.vault.VaultManager
import javax.inject.Singleton

/**
 * Data bindings.
 *
 * Note what is *not* here: the database itself. `CredsDatabase` cannot be a singleton
 * binding because it cannot exist while the vault is locked — its passphrase is derived
 * from the vault key. The factory is injectable; the database is opened on unlock and
 * closed on lock by whoever owns that lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataModule {

    @Provides
    @Singleton
    fun provideVaultDatabaseFactory(
        @ApplicationContext context: Context,
    ): VaultDatabaseFactory = VaultDatabaseFactory(context)

    /**
     * Built here because `VaultManager` is public but takes `internal` collaborators,
     * which a public `@Inject` constructor is not allowed to name. This module is
     * already `internal`, so it can see all of them.
     */
    @Provides
    @Singleton
    fun provideVaultManager(
        keyStore: VaultKeyStore,
        lockPreferences: LockPreferences,
        sealer: VaultKeySealer,
        session: VaultSession,
        databaseFactory: VaultDatabaseFactory,
        biometricKeyStore: BiometricKeyStore,
    ): VaultManager = VaultManager(
        keyStore = keyStore,
        lockPreferences = lockPreferences,
        sealer = sealer,
        session = session,
        databaseFactory = databaseFactory,
        biometricKeyStore = biometricKeyStore,
    )
}
