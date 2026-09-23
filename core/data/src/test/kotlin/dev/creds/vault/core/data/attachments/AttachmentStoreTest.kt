package dev.creds.vault.core.data.attachments

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AttachmentStoreTest {

    @TempDir
    lateinit var dir: File

    private val store by lazy { AttachmentStore(File(dir, "attachments")) }

    private val a = "aaaaaaaa-0000-4000-8000-000000000001"
    private val b = "bbbbbbbb-0000-4000-8000-000000000002"

    @Test
    fun `writes and reads back`() {
        store.write(a, byteArrayOf(1, 2, 3))

        assertThat(store.read(a)?.toList()).isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `a missing file reads as null`() {
        assertThat(store.read(a)).isNull()
    }

    @Test
    fun `an id is written once and never overwritten`() {
        store.write(a, byteArrayOf(1))

        assertThrows<FileAlreadyExistsException> { store.write(a, byteArrayOf(2)) }
        assertThat(store.read(a)?.toList()).isEqualTo(listOf<Byte>(1))
    }

    @Test
    fun `no temporary file is left behind`() {
        store.write(a, byteArrayOf(1))

        assertThat(File(dir, "attachments").list()?.toList()).isNotNull()
            .containsExactlyInAnyOrder(a)
    }

    @Test
    fun `refuses ids that are not uuids`() {
        // Ids become path components; anything else could escape the directory.
        assertThrows<IllegalArgumentException> { store.write("../escape", byteArrayOf(1)) }
        assertThrows<IllegalArgumentException> { store.read("x/y") }
    }

    @Test
    fun `delete removes the file`() {
        store.write(a, byteArrayOf(1))
        store.delete(a)

        assertThat(store.read(a)).isNull()
    }

    @Test
    fun `sweep removes unreferenced and temporary files only`() {
        store.write(a, byteArrayOf(1))
        store.write(b, byteArrayOf(2))
        File(File(dir, "attachments"), "$a.tmp").writeBytes(byteArrayOf(3))

        val removed = store.sweep(keep = setOf(a))

        assertThat(removed).isEqualTo(2)
        assertThat(store.read(a)).isNotNull()
        assertThat(store.read(b)).isNull()
    }

    @Test
    fun `sweep of a directory that does not exist yet is a no-op`() {
        assertThat(store.sweep(emptySet())).isEqualTo(0)
    }

    @Test
    fun `deleteAll removes the directory`() {
        store.write(a, byteArrayOf(1))
        store.deleteAll()

        assertThat(File(dir, "attachments").exists()).isFalse()
    }
}
