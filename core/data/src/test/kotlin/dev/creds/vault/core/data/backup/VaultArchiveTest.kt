package dev.creds.vault.core.data.backup

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.PasswordHasher
import dev.creds.vault.core.domain.importer.ImportedAttachment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The `.vault` container on the host. Argon2id is JNI-only, so a stand-in hasher derives
 * the key; everything else — framing, sealing, the record AAD — is the production code.
 */
class VaultArchiveTest {

    private val key = ByteArray(32) { 7 }
    private val header = VaultArchive.Header(PARAMS, ByteArray(16) { 1 })

    private fun archive(vararg records: String, key: ByteArray = this.key, header: VaultArchive.Header = this.header): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = VaultArchive.Writer(out, key, header)
        records.forEachIndexed { i, r -> writer.write(r.toByteArray(), last = i == records.lastIndex) }
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray, key: ByteArray = this.key): List<String> {
        val input = ByteArrayInputStream(bytes)
        val reader = VaultArchive.Reader(input, key, VaultArchive.readHeader(input))
        return generateSequence { reader.next()?.decodeToString() }.toList()
    }

    @Test
    fun `round trips every record in order`() {
        assertThat(readAll(archive("manifest", "file one", "file two"))).containsExactly("manifest", "file one", "file two")
    }

    @Test
    fun `nothing is readable without the key`() {
        val bytes = archive("secret manifest")
        assertThat(String(bytes, Charsets.ISO_8859_1).contains("secret")).isFalse()
    }

    @Test
    fun `a wrong key is refused at the first record`() {
        val e = assertThrows<BackupException> { readAll(archive("m"), key = ByteArray(32) { 8 }) }
        assertThat(e.message).isEqualTo("Wrong password, or this backup is damaged.")
    }

    @Test
    fun `a truncated file is caught`() {
        val bytes = archive("manifest", "file")
        assertThrows<BackupException> { readAll(bytes.copyOf(bytes.size - 10)) }
        // Cut cleanly after the first record: the last record never arrives.
        val firstRecordEnd = header.bytes.size + 5 + ByteBuffer.wrap(bytes, header.bytes.size + 1, 4).int
        assertThrows<BackupException> { readAll(bytes.copyOf(firstRecordEnd)) }
    }

    @Test
    fun `a flipped byte anywhere in a record is caught`() {
        val bytes = archive("manifest", "file")
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        assertThrows<BackupException> { readAll(bytes) }
    }

    @Test
    fun `records cannot be swapped between positions`() {
        // Each record is sealed to its position; putting record 1 first must fail.
        val a = archive("aaaa", "bbbb")
        val headerSize = header.bytes.size
        val len0 = ByteBuffer.wrap(a, headerSize + 1, 4).int
        val r0 = a.copyOfRange(headerSize, headerSize + 5 + len0)
        val r1 = a.copyOfRange(headerSize + 5 + len0, a.size)
        val swapped = header.bytes + r1 + r0
        assertThrows<BackupException> { readAll(swapped) }
    }

    @Test
    fun `a record cannot be moved into another file`() {
        val other = VaultArchive.Header(PARAMS, ByteArray(16) { 2 })
        val a = archive("from a")
        val b = archive("from b", header = other)
        val grafted = header.bytes + b.copyOfRange(other.bytes.size, b.size)
        assertThat(readAll(a)).containsExactly("from a")
        assertThrows<BackupException> { readAll(grafted) }
    }

    @Test
    fun `a changed header is caught even with the right key`() {
        val bytes = archive("m")
        // Raise the stored iteration count: the header is part of every record's AAD.
        val tampered = bytes.copyOf()
        tampered[8 + 2 + 4 + 3] = (tampered[8 + 2 + 4 + 3] + 1).toByte()
        assertThrows<BackupException> { readAll(tampered) }
    }

    @Test
    fun `foreign files and absurd costs are refused before any key work`() {
        assertThrows<BackupException> { VaultArchive.readHeader(ByteArrayInputStream("{\"items\":[]}".toByteArray())) }
        val greedy = VaultArchive.Header(KdfParams(memoryKib = 4 * 1024 * 1024, iterations = 3, parallelism = 2), ByteArray(16))
        assertThrows<BackupException> { VaultArchive.readHeader(ByteArrayInputStream(greedy.bytes)) }
    }

    @Test
    fun `recognises its own files by their first bytes`() {
        assertThat(VaultArchive.looksLikeArchive(archive("m").copyOf(16))).isTrue()
        assertThat(VaultArchive.looksLikeArchive("{\"folders\":[".toByteArray())).isFalse()
    }

    @Test
    fun `a backup opens with its password and streams its attachments`() = runBlocking {
        val backup = VaultBackup(FakeHasher())
        val password = "correct horse battery".toCharArray()
        val manifest = """{"format":1,"exportedAt":1,"tags":[{"name":"work","color":5}],"items":[
            {"uuid":"u1","template":"login","title":"Mail","createdAt":1,"updatedAt":2,"tags":["work"],
             "fields":[{"type":"password","label":"Password","value":"hunter2","sensitive":true,"updatedAt":2,"valueUpdatedAt":2,
                        "history":[{"value":"old","replacedAt":1}]},
                       {"type":"totp","label":"Code","value":"JBSWY3DP","sensitive":false,"updatedAt":2,"valueUpdatedAt":2}],
             "associations":[{"kind":"domain","value":"mail.example","confirmedAt":3}],
             "attachments":[{"record":1,"name":"a.txt","mimeType":"text/plain","size":5,"createdAt":4}]}]}"""
        val file = archive(manifest, "hello", key = backup.fileKey(password, header))

        val opened = backup.open(ByteArrayInputStream(file), password)
        val item = opened.parsed.items.single()
        assertThat(item.item.title).isEqualTo("Mail")
        assertThat(item.item.fields[0].value).isEqualTo("hunter2")
        // Sensitivity is only raised on import: a TOTP seed is secret whatever the file says.
        assertThat(item.item.fields[1].sensitive).isTrue()
        assertThat(item.history[0]?.single()?.value).isEqualTo("old")
        assertThat(item.tagColors).isEqualTo(mapOf("work" to 5))
        assertThat(item.associations.single().value).isEqualTo("mail.example")
        assertThat(item.attachments).containsExactly(ImportedAttachment("1", "a.txt", "text/plain", 5, 4))

        val streamed = mutableListOf<Pair<String, String>>()
        opened.attachments { ByteArrayInputStream(file) }.forEach { id, bytes -> streamed += id to bytes.decodeToString() }
        assertThat(streamed).containsExactly("1" to "hello")
        opened.close()
    }

    @Test
    fun `a wrong backup password is refused`() {
        val backup = VaultBackup(FakeHasher())
        val file = archive("""{"format":1,"exportedAt":1}""", key = backup.fileKey("right password".toCharArray(), header))
        val e = assertThrows<BackupException> { backup.open(ByteArrayInputStream(file), "wrong password".toCharArray()) }
        assertThat(e.message).isEqualTo("Wrong password, or this backup is damaged.")
    }

    @Test
    fun `an archive missing an attachment record is refused while importing`() = runBlocking {
        val backup = VaultBackup(FakeHasher())
        val password = "pw".toCharArray()
        val manifest = """{"format":1,"exportedAt":1,"items":[{"uuid":"u","template":"note","title":"N","createdAt":1,"updatedAt":1,
            "attachments":[{"record":1,"name":"a","mimeType":"x/y","size":1,"createdAt":1},
                           {"record":2,"name":"b","mimeType":"x/y","size":1,"createdAt":1}]}]}"""
        // Only one attachment record, marked last: a well-formed but incomplete backup.
        val file = archive(manifest, "a", key = backup.fileKey(password, header))
        val opened = backup.open(ByteArrayInputStream(file), password)
        assertThrows<BackupException> { runBlocking { opened.attachments { ByteArrayInputStream(file) }.forEach { _, _ -> } } }
    }

    /** Deterministic stand-in for Argon2id; the format does not care how the key was stretched. */
    private class FakeHasher : PasswordHasher {
        override fun deriveMasterKey(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(String(password).toByteArray() + salt)
    }

    private companion object {
        val PARAMS = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 2)
    }
}
