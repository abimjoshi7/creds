package dev.creds.vault.core.data.backup

import dev.creds.vault.core.crypto.AesGcm
import dev.creds.vault.core.crypto.KdfParams
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

/**
 * The `.vault` file: a plaintext header, then a sequence of separately sealed records.
 *
 * ```
 * header  magic "VAULTESQ" · format u8 · kdf u8 (1 = Argon2id)
 *         memoryKib u32 · iterations u32 · parallelism u32 · salt[16]
 * record  flags u8 (bit 0 = last) · length u32 · AesGcm blob
 * ```
 *
 * Record 0 is the manifest; attachment bytes follow, one record each. Every record is
 * sealed under the file key with AAD `header ‖ index ‖ flags`, so a record cannot be moved,
 * dropped, duplicated or taken from another file, and a truncated file is caught because
 * its final record never arrives. Records rather than one sealed payload so a backup with
 * many attachments never has to fit in memory at once.
 *
 * The KDF parameters live in the header, so a later build can raise them and still read
 * files made today. Free of `android.*`: the key is derived by the caller.
 */
internal object VaultArchive {

    private val MAGIC = "VAULTESQ".toByteArray(Charsets.US_ASCII)
    const val FORMAT: Int = 1
    private const val KDF_ARGON2ID: Int = 1
    private const val FLAG_LAST: Int = 1

    /** One sealed record may not claim more than this: a 5 MB attachment plus framing. */
    private const val MAX_RECORD_BYTES: Int = 8 * 1024 * 1024

    class Header(val params: KdfParams, val salt: ByteArray) {
        val bytes: ByteArray = ByteBuffer.allocate(MAGIC.size + 2 + 12 + salt.size).apply {
            put(MAGIC)
            put(FORMAT.toByte())
            put(KDF_ARGON2ID.toByte())
            putInt(params.memoryKib)
            putInt(params.iterations)
            putInt(params.parallelism)
            put(salt)
        }.array()
    }

    /** Whether [prefix] starts like a `.vault` file. Enough to route a chosen file. */
    fun looksLikeArchive(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && prefix.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupException("This is not a Vaultesque backup.")
            val format = data.readUnsignedByte()
            if (format != FORMAT) throw BackupException("This backup was made by a newer version of Vaultesque.")
            if (data.readUnsignedByte() != KDF_ARGON2ID) throw BackupException("This backup uses an unknown key format.")
            val params = try {
                KdfParams(memoryKib = data.readInt(), iterations = data.readInt(), parallelism = data.readInt())
            } catch (e: IllegalArgumentException) {
                throw BackupException("This backup is damaged.", e)
            }
            // Refuse costs that would hang or exhaust the phone before a password is even tried.
            if (params.memoryKib > MAX_MEMORY_KIB || params.iterations > MAX_ITERATIONS || params.parallelism > MAX_PARALLELISM) {
                throw BackupException("This backup asks for more memory than this phone can give.")
            }
            val salt = ByteArray(KdfParams.SALT_BYTES).also(data::readFully)
            return Header(params, salt)
        } catch (e: EOFException) {
            throw BackupException("This backup is incomplete.", e)
        }
    }

    class Writer(output: OutputStream, private val key: ByteArray, private val header: Header) {
        private val out = DataOutputStream(output)
        private var index = 0
        private var finished = false

        init {
            out.write(header.bytes)
        }

        fun write(payload: ByteArray, last: Boolean) {
            check(!finished) { "Archive already finished" }
            val flags = if (last) FLAG_LAST else 0
            val sealed = AesGcm.seal(key, payload, aad(header, index, flags))
            out.writeByte(flags)
            out.writeInt(sealed.size)
            out.write(sealed)
            index++
            if (last) {
                finished = true
                out.flush()
            }
        }
    }

    class Reader(input: InputStream, private val key: ByteArray, private val header: Header) {
        private val data = DataInputStream(input)
        private var index = 0
        private var finished = false

        /**
         * The next record's plaintext, which the caller owns and should wipe, or null
         * after the last one.
         *
         * @throws BackupException on a wrong password, tampering, reordering or truncation.
         *   A wrong password and a damaged file look the same by design.
         */
        fun next(): ByteArray? {
            if (finished) return null
            try {
                val flags = data.readUnsignedByte()
                val length = data.readInt()
                if (length !in 1..MAX_RECORD_BYTES) throw BackupException("This backup is damaged.")
                val sealed = ByteArray(length).also(data::readFully)
                val plain = try {
                    AesGcm.open(key, sealed, aad(header, index, flags))
                } catch (e: GeneralSecurityException) {
                    throw BackupException(
                        if (index == 0) "Wrong password, or this backup is damaged." else "This backup is damaged.",
                        e,
                    )
                }
                index++
                if (flags and FLAG_LAST != 0) finished = true
                return plain
            } catch (e: EOFException) {
                throw BackupException("This backup is incomplete.", e)
            }
        }
    }

    private fun aad(header: Header, index: Int, flags: Int): ByteArray =
        ByteBuffer.allocate(header.bytes.size + 5).put(header.bytes).putInt(index).put(flags.toByte()).array()

    // Well above what any build writes (64 MiB, t=3, p=2) and still safe to attempt.
    private const val MAX_MEMORY_KIB = 512 * 1024
    private const val MAX_ITERATIONS = 20
    private const val MAX_PARALLELISM = 8
}

/** A backup that cannot be read. The message is written for the user. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
