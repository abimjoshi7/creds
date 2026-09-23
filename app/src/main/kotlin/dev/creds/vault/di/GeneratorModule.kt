package dev.creds.vault.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.creds.vault.core.domain.generator.PasswordGenerator
import dev.creds.vault.core.domain.generator.SecureRandomSource
import dev.creds.vault.core.domain.generator.UniformRandom
import javax.inject.Singleton

/**
 * The generator lives in `:core:domain`, which has no DI of its own, so it is assembled
 * here: the platform CSPRNG behind the unbiased sampler.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeneratorModule {

    @Provides
    @Singleton
    fun providePasswordGenerator(): PasswordGenerator =
        PasswordGenerator(UniformRandom(SecureRandomSource()))
}
