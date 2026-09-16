package dev.creds.vault.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lambdapioneer.argon2kt.Argon2Kt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Argon2id known-answer vectors, run on a real device.
 *
 * argon2kt bundles native libraries for Android ABIs only, so there is no way to run
 * these in a host JVM test — hence `androidTest` rather than `test`. Everything derived
 * *from* the master key is pure JVM and is covered on the host.
 *
 * The expected values were generated with libsodium, an independent Argon2id v1.3
 * implementation, rather than taken from this code's own output. A vector produced by
 * the implementation under test proves only that it is self-consistent.
 *
 * libsodium fixes parallelism at 1, so these vectors pin p=1. Production uses p=2 —
 * `VaultKeySealerTest` is what guards the production cost parameters.
 */
@RunWith(AndroidJUnit4::class)
class Argon2idKnownAnswerTest {

    private val hasher = Argon2idPasswordHasher(Argon2Kt())

    @Test
    fun matchesLibsodiumVector_m64MiB_t3() {
        val key = hasher.deriveMasterKey(
            password = "password".toCharArray(),
            salt = "somesalt01234567".toByteArray(Charsets.UTF_8),
            params = KdfParams(memoryKib = 65536, iterations = 3, parallelism = 1),
        )

        assertEquals(
            "ae5c07329066ac1e58c39e2d74bb9ee56c9dfa411a88866cf2de9fb4cb61601d",
            key.toHex(),
        )
    }

    @Test
    fun matchesLibsodiumVector_m8MiB_t2() {
        val key = hasher.deriveMasterKey(
            password = "correct horse battery staple".toCharArray(),
            salt = ByteArray(16) { it.toByte() },
            params = KdfParams(memoryKib = 8192, iterations = 2, parallelism = 1),
        )

        assertEquals(
            "a891dd91a3d123add6ea78ceedcc94a7f1dc1e4ca480e57e6f240a113662ea2f",
            key.toHex(),
        )
    }

    @Test
    fun matchesLibsodiumVector_emptyPassword() {
        val key = hasher.deriveMasterKey(
            password = CharArray(0),
            salt = ByteArray(16) { 2 },
            params = KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1),
        )

        assertEquals(
            "f56b1caef10cf12f7a49d3424729e77ab14177da57294b613830bc511f7ec986",
            key.toHex(),
        )
    }

    @Test
    fun derivationIsDeterministic() {
        val salt = ByteArray(16) { 7 }
        val params = KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1)

        val first = hasher.deriveMasterKey("hunter2".toCharArray(), salt, params)
        val second = hasher.deriveMasterKey("hunter2".toCharArray(), salt, params)

        assertEquals(first.toHex(), second.toHex())
    }

    @Test
    fun differentSaltGivesDifferentKey() {
        val params = KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1)

        val first = hasher.deriveMasterKey("hunter2".toCharArray(), ByteArray(16) { 1 }, params)
        val second = hasher.deriveMasterKey("hunter2".toCharArray(), ByteArray(16) { 2 }, params)

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun parallelismChangesTheResult() {
        val salt = ByteArray(16) { 7 }

        val p1 = hasher.deriveMasterKey(
            "hunter2".toCharArray(), salt,
            KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1),
        )
        val p2 = hasher.deriveMasterKey(
            "hunter2".toCharArray(), salt,
            KdfParams(memoryKib = 8192, iterations = 1, parallelism = 2),
        )

        // Confirms the parallelism argument actually reaches the native call, rather
        // than being silently dropped into the wrong positional slot.
        assertNotEquals(p1.toHex(), p2.toHex())
    }

    @Test
    fun producesTheRequestedKeyLength() {
        val key = hasher.deriveMasterKey(
            "hunter2".toCharArray(),
            ByteArray(16) { 7 },
            KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1, outputBytes = 64),
        )

        assertEquals(64, key.size)
    }

    @Test
    fun leavesTheCallersPasswordBufferIntact() {
        val password = "hunter2".toCharArray()

        hasher.deriveMasterKey(
            password,
            ByteArray(16) { 7 },
            KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1),
        )

        assertEquals("hunter2", String(password))
    }

    @Test
    fun rejectsAShortSalt() {
        try {
            hasher.deriveMasterKey(
                "hunter2".toCharArray(),
                ByteArray(8),
                KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1),
            )
            throw AssertionError("Expected a short salt to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Salt must be at least"))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
