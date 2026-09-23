package dev.creds.vault.core.model

/**
 * A file stored with an item, as the UI sees it.
 *
 * Metadata only. The bytes are sealed on disk and are decrypted on demand, one file at a
 * time, for exactly as long as a preview or an explicit export needs them.
 *
 * [name] is plaintext here because the vault is open; at rest it is sealed like a note,
 * since a file name such as `statement-acct-1234.pdf` says as much as the file does.
 */
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
) {
    companion object {
        /** Largest file the vault accepts. Preview and export hold the whole file in memory. */
        const val MAX_BYTES: Long = 5L * 1024 * 1024

        const val FALLBACK_MIME_TYPE: String = "application/octet-stream"
    }
}
