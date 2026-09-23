package dev.creds.vault.core.domain.breach

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BreachesTest {

    @Test
    fun `a Bloom filter never forgets what was added`() {
        val filter = BloomFilter.create(expectedEntries = 10_000, falsePositiveRate = 0.001)
        val members = (0 until 10_000).map { "member-$it" }
        members.forEach(filter::put)

        members.forEach { assertThat(filter.mightContain(it), it).isTrue() }
    }

    @Test
    fun `the false positive rate is near what was asked for`() {
        val filter = BloomFilter.create(expectedEntries = 20_000, falsePositiveRate = 0.001)
        repeat(20_000) { filter.put("member-$it") }

        val falsePositives = (0 until 200_000).count { filter.mightContain("stranger-$it") }

        // 0.1% of 200k is 200. Deterministic, since hashing is: this is a fixed number.
        assertThat(falsePositives).isLessThan(400)
    }

    @Test
    fun `a filter survives a round trip byte for byte`() {
        val filter = BloomFilter.create(expectedEntries = 100, falsePositiveRate = 0.01)
        listOf("a", "hunter2", "correct horse", "pässwörd").forEach(filter::put)

        val bytes = ByteArrayOutputStream().also(filter::writeTo).toByteArray()
        val read = BloomFilter.readFrom(ByteArrayInputStream(bytes))

        assertThat(read.entries).isEqualTo(4)
        assertThat(read.hashCount).isEqualTo(filter.hashCount)
        assertThat(read.mightContain("pässwörd")).isTrue()
        assertThat(ByteArrayOutputStream().also(read::writeTo).toByteArray().contentEquals(bytes)).isTrue()
    }

    @Test
    fun `corrupt or truncated filters are refused`() {
        val bytes = ByteArrayOutputStream().also(BloomFilter.create(100, 0.01)::writeTo).toByteArray()

        assertThat(runCatching { BloomFilter.readFrom(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }).isFailure()
        assertThat(runCatching { BloomFilter.readFrom(ByteArrayInputStream(bytes + 0)) }).isFailure()
        val badMagic = bytes.copyOf().also { it[0] = 0 }
        assertThat(runCatching { BloomFilter.readFrom(ByteArrayInputStream(badMagic)) }).isFailure()
    }

    @Test
    fun `the bundled list knows the classics and not a generated password`() {
        assertThat(OfflineBreachList.entries).isGreaterThan(990_000)
        listOf("123456", "password", "qwerty", "iloveyou").forEach {
            assertThat(OfflineBreachList.contains(it), it).isTrue()
        }
        assertThat(OfflineBreachList.contains("vK7#q9!Lz2@mP4xR8wT1")).isFalse()
        assertThat(OfflineBreachList.contains("")).isFalse()
    }

    @Test
    fun `hibp hashes are uppercase SHA-1 split five and thirty-five`() {
        val hash = HibpRange.sha1Hex("password")

        assertThat(hash).isEqualTo("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8")
        assertThat(HibpRange.prefix(hash)).isEqualTo("5BAA6")
        assertThat(HibpRange.isValidPrefix("5BAA6")).isTrue()
        assertThat(HibpRange.isValidPrefix("5baa6")).isFalse()
        assertThat(HibpRange.isValidPrefix("5BAA")).isFalse()
    }

    @Test
    fun `a range match is found whatever the case and line endings, and padding is ignored`() {
        val hash = HibpRange.sha1Hex("password")
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:10\r\n" +
            "1e4c9b93f3f0682250b6cf8331b7ee68fd8:9659365\r\n" +
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:0\r\n"

        assertThat(HibpRange.count(body, hash)).isEqualTo(9659365)
        assertThat(HibpRange.count(body, "5BAA6FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")).isEqualTo(0)
        assertThat(HibpRange.count(body, "5BAA6000000000000000000000000000000000000")).isEqualTo(0)
    }
}
