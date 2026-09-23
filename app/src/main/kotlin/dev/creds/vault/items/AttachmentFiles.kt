package dev.creds.vault.items

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads files the user picks and writes the copies they export, through the system
 * document picker only.
 *
 * Nothing here touches the app's own storage. A picked file goes straight into memory
 * and from there into the vault sealed; an exported copy goes straight to wherever the
 * user chose. No intermediate plaintext file is ever created.
 */
@Singleton
class AttachmentFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun read(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        if ((declaredSize ?: 0L) > Attachment.MAX_BYTES) return@withContext PickedFile.TooLarge

        // The declared size is only a hint, so the read itself is bounded too. One byte
        // of headroom is how an oversized stream is told apart from one exactly at the cap.
        val buffer = ByteArray((Attachment.MAX_BYTES + 1).toInt())
        var total = 0
        try {
            val input = resolver.openInputStream(uri) ?: return@withContext PickedFile.Unreadable
            input.use {
                while (total < buffer.size) {
                    val read = it.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
            }
            if (total > Attachment.MAX_BYTES) return@withContext PickedFile.TooLarge
            PickedFile.Read(
                name = name?.takeIf { it.isNotBlank() } ?: "File",
                mimeType = resolver.getType(uri) ?: Attachment.FALLBACK_MIME_TYPE,
                bytes = buffer.copyOf(total),
            )
        } catch (_: IOException) {
            PickedFile.Unreadable
        } catch (_: SecurityException) {
            PickedFile.Unreadable
        } finally {
            buffer.wipe()
        }
    }

    /** Writes an exported plaintext copy to a location the user chose. */
    suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open the destination")
        output.use { it.write(bytes) }
    }
}

sealed interface PickedFile {

    /** [bytes] is plaintext and owned by the caller, who must wipe it. */
    class Read(val name: String, val mimeType: String, val bytes: ByteArray) : PickedFile

    data object TooLarge : PickedFile

    data object Unreadable : PickedFile
}
