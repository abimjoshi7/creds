package dev.creds.vault.core.data.attachments

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Sealed attachment bytes on disk, one file per attachment.
 *
 * Files live outside the database so a vault full of scans does not make every query page
 * through megabytes of ciphertext, and so SQLCipher's WAL never holds a copy of them.
 * Everything written here is already AES-GCM sealed by the caller; this class never sees
 * plaintext and knows nothing about keys.
 *
 * The directory is app-private and excluded from backup. Names are the attachment id and
 * nothing else — no file name, no type, no item — so a directory listing says only how
 * many files there are and roughly how big.
 *
 * Free of `android.*`: the directory is handed in, so this is testable on the host.
 */
class AttachmentStore(private val directory: File) {

    /**
     * Writes [sealed] as the file for a new [id].
     *
     * Attachments are write-once: an id that already has a file is refused rather than
     * overwritten, so a colliding id can never destroy existing bytes. The data goes to a
     * temporary file, is synced, then renamed into place, so a crash mid-write leaves no
     * file under [id] at all rather than half of one.
     */
    fun write(id: String, sealed: ByteArray) {
        val target = fileFor(id)
        if (target.exists()) throw FileAlreadyExistsException(target)
        directory.mkdirs()
        val temp = File(directory, "$id$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    /** The sealed bytes, or null when the file is gone. */
    fun read(id: String): ByteArray? {
        val file = fileFor(id)
        return if (file.isFile) file.readBytes() else null
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    /**
     * Deletes every file whose id is not in [keep], including abandoned temporary files.
     *
     * Files are written before the database row that references them and deleted after
     * the row is gone, so a crash can only ever leave an unreferenced file — never a row
     * pointing at nothing it could have had. This clears those.
     *
     * @return how many files were removed.
     */
    fun sweep(keep: Set<String>): Int {
        val files = directory.listFiles() ?: return 0
        return files.count { file ->
            val id = file.name.removeSuffix(TEMP_SUFFIX)
            val orphan = file.name.endsWith(TEMP_SUFFIX) || id !in keep
            orphan && file.delete()
        }
    }

    /** Removes every attachment. Used only when the vault itself is destroyed. */
    fun deleteAll() {
        directory.deleteRecursively()
    }

    private fun fileFor(id: String): File {
        // Ids come from our own database, but they become path components, so anything
        // that is not a plain uuid is refused rather than trusted.
        require(ID_PATTERN.matches(id)) { "Not an attachment id" }
        return File(directory, id)
    }

    companion object {
        const val DIRECTORY_NAME: String = "attachments"

        private const val TEMP_SUFFIX = ".tmp"
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
