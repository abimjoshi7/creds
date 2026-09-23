package dev.creds.vault.items

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.repository.NewAttachment
import dev.creds.vault.core.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AttachmentTags {
    const val ADD = "attachment:add"
    const val EXPORT_CONFIRM = "attachment:export"
    fun row(id: String) = "attachment:$id"
    fun menu(id: String) = "attachment:$id:menu"
}

/** An attachment as a list shows it. [pending] ones exist only in memory until saved. */
data class AttachmentRow(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val pending: Boolean = false,
) {
    val previewKind: PreviewKind get() = previewKindOf(mimeType)

    companion object {
        fun from(attachment: Attachment) = AttachmentRow(
            id = attachment.id,
            name = attachment.name,
            mimeType = attachment.mimeType,
            size = attachment.size,
        )

        fun pending(new: NewAttachment) = AttachmentRow(
            id = new.id,
            name = new.name,
            mimeType = new.mimeType,
            size = new.bytes.size.toLong(),
            pending = true,
        )

        fun previewKindOf(mimeType: String): PreviewKind = when {
            mimeType.startsWith("image/") -> PreviewKind.IMAGE
            mimeType.startsWith("text/") || mimeType == "application/json" -> PreviewKind.TEXT
            else -> PreviewKind.NONE
        }
    }
}

enum class PreviewKind { IMAGE, TEXT, NONE }

sealed interface AttachmentPreview {
    val row: AttachmentRow

    data class Loading(override val row: AttachmentRow) : AttachmentPreview

    data class Image(override val row: AttachmentRow, val bitmap: ImageBitmap) : AttachmentPreview

    data class Text(override val row: AttachmentRow, val text: String) : AttachmentPreview

    data class Unsupported(override val row: AttachmentRow) : AttachmentPreview
}

data class AttachmentViewState(
    val preview: AttachmentPreview? = null,
    /** An attachment the user asked to export; [exportConfirmed] once they accept the warning. */
    val exportCandidate: AttachmentRow? = null,
    val exportConfirmed: Boolean = false,
    /** A one-off notice for the snackbar, cleared by [AttachmentViewer.messageShown]. */
    val message: String? = null,
)

/**
 * Preview and export for one screen's attachments.
 *
 * Plaintext only ever exists for the moment it is needed: decrypted for a preview and
 * wiped once decoded, or decrypted for an export and wiped once written. Only images and
 * text are previewed; showing anything else would mean handing another app a plaintext
 * file, so those are offered as an explicit, warned export instead.
 *
 * [bytesFor] returns plaintext the viewer owns and wipes, or null when the file cannot be
 * had (the vault locked, or the file is gone).
 */
class AttachmentViewer(
    private val scope: CoroutineScope,
    private val files: AttachmentFiles,
    private val bytesFor: suspend (AttachmentRow) -> ByteArray?,
) {
    private val _state = MutableStateFlow(AttachmentViewState())
    val state: StateFlow<AttachmentViewState> = _state.asStateFlow()

    fun preview(row: AttachmentRow) {
        val kind = row.previewKind
        if (kind == PreviewKind.NONE) {
            _state.update { it.copy(preview = AttachmentPreview.Unsupported(row)) }
            return
        }
        _state.update { it.copy(preview = AttachmentPreview.Loading(row)) }
        scope.launch {
            val bytes = bytesFor(row)
            if (bytes == null) {
                _state.update { it.copy(preview = null, message = UNAVAILABLE_MESSAGE) }
                return@launch
            }
            val preview = try {
                withContext(Dispatchers.Default) {
                    when (kind) {
                        PreviewKind.IMAGE -> decodeImage(bytes)?.let { AttachmentPreview.Image(row, it) }
                        PreviewKind.TEXT -> AttachmentPreview.Text(row, bytes.decodeToString().take(MAX_PREVIEW_CHARS))
                        PreviewKind.NONE -> null
                    }
                } ?: AttachmentPreview.Unsupported(row)
            } finally {
                bytes.wipe()
            }
            // The user may have closed the dialog, or opened another, while this decoded.
            _state.update { if (it.preview?.row?.id == row.id) it.copy(preview = preview) else it }
        }
    }

    fun dismissPreview() = _state.update { it.copy(preview = null) }

    /** Asks for confirmation first: an exported copy is plaintext, outside the vault. */
    fun requestExport(row: AttachmentRow) =
        _state.update { it.copy(preview = null, exportCandidate = row, exportConfirmed = false) }

    /** The user accepted the warning; the screen now asks the system where to save. */
    fun confirmExport() = _state.update { it.copy(exportConfirmed = true) }

    fun cancelExport() = _state.update { it.copy(exportCandidate = null, exportConfirmed = false) }

    fun exportTo(uri: Uri) {
        val row = _state.value.exportCandidate ?: return
        _state.update { it.copy(exportCandidate = null, exportConfirmed = false) }
        scope.launch {
            val bytes = bytesFor(row)
            if (bytes == null) {
                _state.update { it.copy(message = UNAVAILABLE_MESSAGE) }
                return@launch
            }
            val message = try {
                files.write(uri, bytes)
                "Saved an unencrypted copy of ${row.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Could not save the copy"
            } finally {
                bytes.wipe()
            }
            _state.update { it.copy(message = message) }
        }
    }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Drops any open preview, e.g. on lock: a decoded image is plaintext too. */
    fun clear() {
        _state.value = AttachmentViewState()
    }

    private companion object {
        const val MAX_PREVIEW_CHARS = 100_000

        /** Longest image edge decoded for preview; a phone screen shows no more. */
        const val MAX_PREVIEW_EDGE = 2048

        const val UNAVAILABLE_MESSAGE = "This file could not be opened"

        fun decodeImage(bytes: ByteArray): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PREVIEW_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }
}

/** What the preview and export dialogs can ask of their owner. No-ops by default, for tests. */
class AttachmentViewActions(
    val onPreview: (AttachmentRow) -> Unit = {},
    val onDismissPreview: () -> Unit = {},
    val onRequestExport: (AttachmentRow) -> Unit = {},
    val onConfirmExport: () -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onExportTo: (Uri) -> Unit = {},
    val onMessageShown: () -> Unit = {},
) {
    companion object {
        fun of(viewer: AttachmentViewer) = AttachmentViewActions(
            onPreview = viewer::preview,
            onDismissPreview = viewer::dismissPreview,
            onRequestExport = viewer::requestExport,
            onConfirmExport = viewer::confirmExport,
            onCancelExport = viewer::cancelExport,
            onExportTo = viewer::exportTo,
            onMessageShown = viewer::messageShown,
        )
    }
}

/** One attachment: tap to preview, menu to export and, where editing, to remove. */
@Composable
fun AttachmentListRow(
    row: AttachmentRow,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .padding(vertical = 6.dp)
            .testTag(AttachmentTags.row(row.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when (row.previewKind) {
                PreviewKind.IMAGE -> Icons.Outlined.Image
                PreviewKind.TEXT -> Icons.Outlined.Description
                PreviewKind.NONE -> Icons.Outlined.AttachFile
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(Formatter.formatShortFileSize(context, row.size))
                    if (row.pending) append(" · not saved yet")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(AttachmentTags.menu(row.id))) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${row.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Save a copy…") },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                )
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The preview dialog, the plaintext warning, the system "save as" picker, and the
 * snackbar notices, for whichever screen owns an [AttachmentViewer].
 */
@Composable
fun AttachmentDialogs(
    state: AttachmentViewState,
    actions: AttachmentViewActions,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
        actions.onMessageShown()
    }

    val exportMime = state.exportCandidate?.mimeType ?: Attachment.FALLBACK_MIME_TYPE
    val createDocument = remember(exportMime) { ActivityResultContracts.CreateDocument(exportMime) }
    val exportFile = rememberLauncherForActivityResult(createDocument) { uri ->
        if (uri != null) actions.onExportTo(uri) else actions.onCancelExport()
    }

    state.preview?.let { preview ->
        AttachmentPreviewDialog(
            preview = preview,
            onDismiss = actions.onDismissPreview,
            onExport = { actions.onRequestExport(preview.row) },
        )
    }

    state.exportCandidate?.takeIf { !state.exportConfirmed }?.let { candidate ->
        ExportWarningDialog(
            name = candidate.name,
            onConfirm = {
                actions.onConfirmExport()
                // Launched from the tap rather than from state, so a configuration change
                // cannot open the picker a second time.
                exportFile.launch(candidate.name)
            },
            onDismiss = actions.onCancelExport,
        )
    }
}

@Composable
private fun AttachmentPreviewDialog(
    preview: AttachmentPreview,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(preview.row.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when (preview) {
                is AttachmentPreview.Loading -> CircularProgressIndicator()
                is AttachmentPreview.Image -> Image(
                    bitmap = preview.bitmap,
                    contentDescription = preview.row.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                )
                is AttachmentPreview.Text -> Text(
                    preview.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
                is AttachmentPreview.Unsupported -> Text(
                    "This kind of file can't be shown inside Vaultesque. Save a copy to open it " +
                        "in another app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onExport) { Text("Save a copy…") }
        },
    )
}

@Composable
private fun ExportWarningDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("Save an unencrypted copy?") },
        text = {
            Text(
                "The copy of $name will be saved outside the vault, without encryption. " +
                    "Anyone or any app that can reach where you save it can read it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(AttachmentTags.EXPORT_CONFIRM)) {
                Text("Choose location")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
