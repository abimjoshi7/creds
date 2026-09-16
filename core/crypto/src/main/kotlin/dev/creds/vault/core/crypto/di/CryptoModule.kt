package dev.creds.vault.core.crypto.di

import com.lambdapioneer.argon2kt.Argon2Kt
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.nulabinc.zxcvbn.Zxcvbn
import dev.creds.vault.core.crypto.Argon2idPasswordHasher
import dev.creds.vault.core.crypto.PasswordHasher
import dev.creds.vault.core.crypto.strength.ZxcvbnPasswordStrengthEstimator
import dev.creds.vault.core.domain.strength.PasswordStrengthEstimator
import javax.inject.Singleton

/**
 * Crypto bindings.
 *
 * Everything here is a singleton: [Argon2Kt] loads a native library on construction, and
 * `VaultSession` owning the live vault key is only meaningful if there is exactly one of
 * it in the process.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindPasswordHasher(impl: Argon2idPasswordHasher): PasswordHasher

    /**
     * The Android backing for the pure-Kotlin strength contract in `:core:domain`.
     *
     * Bound here rather than in `:core:domain` because that module must stay free of
     * anything platform-specific — zxcvbn is a JVM library, and an iOS port would bind a
     * Swift implementation to the same interface.
     */
    @Binds
    @Singleton
    abstract fun bindPasswordStrengthEstimator(
        impl: ZxcvbnPasswordStrengthEstimator,
    ): PasswordStrengthEstimator

    companion object {

        /**
         * Constructed once. The default [Argon2Kt] constructor loads the bundled
         * `libargon2jni.so` through the system loader.
         */
        @Provides
        @Singleton
        fun provideArgon2Kt(): Argon2Kt = Argon2Kt()

        /** Loads its frequency dictionaries on construction, so it is shared. */
        @Provides
        @Singleton
        fun provideZxcvbn(): Zxcvbn = Zxcvbn()
    }
}
