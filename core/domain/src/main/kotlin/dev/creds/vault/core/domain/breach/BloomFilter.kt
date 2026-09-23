package dev.creds.vault.core.domain.breach

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A Bloom filter over UTF-8 strings.
 *
 * "Definitely not in the set" or "probably in the set", in a fixed number of bits however
 * long the members are — which is how a million breached passwords fit in about 2MB and
 * are checked without a byte leaving the device.
 *
 * Positions come from SHA-256 of the value, split into two 64-bit halves and combined as
 * `h1 + i·h2` (Kirsch–Mitzenmacher), so [hashCount] positions cost one digest. The build
 * tool and the app use this same class, so a filter can never be built with one hashing
 * scheme and read with another.
 *
 * File format, big-endian: magic `CRBF`, version byte, hash count byte, entry count
 * (int), bit count (long), then the bits, least significant first within each byte.
 */
class BloomFilter private constructor(
    val bitCount: Long,
    val hashCount: Int,
    entries: Int,
    private val bits: ByteArray,
) {
    var entries: Int = entries
        private set

    init {
        require(bitCount > 0 && bitCount <= Int.MAX_VALUE.toLong() * 8) { "Unsupported bit count $bitCount" }
        require(hashCount in 1..32) { "Unsupported hash count $hashCount" }
        require(bits.size.toLong() == (bitCount + 7) / 8) { "Bit array does not match bit count" }
    }

    fun put(value: String) {
        forEachPosition(value) { position ->
            val index = (position ushr 3).toInt()
            bits[index] = (bits[index].toInt() or bitMask(position)).toByte()
        }
        entries++
    }

    /** False means [value] was never added. True means it probably was. */
    fun mightContain(value: String): Boolean {
        var all = true
        forEachPosition(value) { position ->
            if (bits[(position ushr 3).toInt()].toInt() and bitMask(position) == 0) all = false
        }
        return all
    }

    private fun bitMask(position: Long): Int = 1 shl (position and 7).toInt()

    fun writeTo(output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeByte(VERSION)
        data.writeByte(hashCount)
        data.writeInt(entries)
        data.writeLong(bitCount)
        data.write(bits)
        data.flush()
    }

    private inline fun forEachPosition(value: String, action: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val h1 = digest.longAt(0)
        val h2 = digest.longAt(8)
        for (i in 0 until hashCount) {
            action(Math.floorMod(h1 + i * h2, bitCount))
        }
    }

    companion object {
        private const val MAGIC = 0x43524246 // "CRBF"
        private const val VERSION = 1

        /** An empty filter sized for [expectedEntries] at [falsePositiveRate]. */
        fun create(expectedEntries: Int, falsePositiveRate: Double): BloomFilter {
            require(expectedEntries > 0) { "expectedEntries must be positive" }
            require(falsePositiveRate > 0 && falsePositiveRate < 1) { "falsePositiveRate must be in (0, 1)" }
            val bitCount = ceil(-expectedEntries * ln(falsePositiveRate) / (ln(2.0) * ln(2.0))).toLong()
            val hashCount = ((bitCount.toDouble() / expectedEntries) * ln(2.0)).roundToInt().coerceIn(1, 32)
            return BloomFilter(bitCount, hashCount, 0, ByteArray(((bitCount + 7) / 8).toInt()))
        }

        /** Reads a filter written by [writeTo], refusing anything that is not exactly one. */
        fun readFrom(input: InputStream): BloomFilter {
            val data = DataInputStream(input)
            require(data.readInt() == MAGIC) { "Not a Creds Bloom filter" }
            val version = data.readUnsignedByte()
            require(version == VERSION) { "Unsupported Bloom filter version $version" }
            val hashCount = data.readUnsignedByte()
            val entries = data.readInt()
            val bitCount = data.readLong()
            require(bitCount > 0 && bitCount <= Int.MAX_VALUE.toLong() * 8) { "Corrupt bit count $bitCount" }
            val bits = ByteArray(((bitCount + 7) / 8).toInt())
            data.readFully(bits)
            require(data.read() == -1) { "Trailing bytes after the Bloom filter" }
            return BloomFilter(bitCount, hashCount, entries, bits)
        }

        private fun ByteArray.longAt(offset: Int): Long {
            var result = 0L
            for (i in 0 until 8) result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
            return result
        }
    }
}
