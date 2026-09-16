package dev.creds.vault.core.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PasswordHasher] backed by argon2kt's bundled native reference implementation.
 *
 * argon2kt ships `.so` binaries for Android ABIs only, so this class cannot run in a
 * host JVM unit test — its known-answer vectors live in `androidTest` and run on a real
 * device. Everything derived *from* the master key is pure JVM and tested on the host.
 *
 * [Argon2Kt] is cheap to construct but loads a native library on first use, so it is a
 * singleton.
 */
@Singleton
class Argon2idPasswordHasher @Inject constructor(
    // No default value: a default argument makes Kotlin emit a second constructor, and
    // Dagger rejects a type with two @Inject constructors. CryptoModule provides this.
    private val argon2: Argon2Kt,
) : PasswordHasher {

    override fun deriveMasterKey(
        password: CharArray,
        salt: ByteArray,
        params: KdfParams,
    ): ByteArray {
        require(salt.size >= KdfParams.MIN_SALT_BYTES) {
            "Salt must be at least ${KdfParams.MIN_SALT_BYTES} bytes, got ${salt.size}"
        }

        // The UTF-8 copy is the one buffer we own here, so it is the one we must wipe.
        // The caller's CharArray stays intact by contract.
        val passwordBytes = password.toUtf8Bytes()

        return passwordBytes.useAndWipe {
            val result = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = it,
                salt = salt,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKib,
                parallelism = params.parallelism,
                hashLengthInBytes = params.outputBytes,
                version = Argon2Version.V13,
            )
            result.rawHashAsByteArray()
        }
    }
}
