package dev.creds.vault.core.data.repository

/**
 * Files to add to and remove from an item, applied by `VaultRepository.save` in the same
 * transaction as the item itself — so cancelling an edit discards both together.
 */
class AttachmentChanges(
    val added: List<NewAttachment> = emptyList(),
    val removedIds: Set<String> = emptySet(),
) {
    companion object {
        val NONE: AttachmentChanges = AttachmentChanges()
    }
}

/**
 * A file picked in the editor that is not in the vault yet.
 *
 * [bytes] is plaintext. The repository seals it but does not wipe it; whoever read the
 * file owns the array and wipes it once the save is done or abandoned. Not a data class:
 * a generated `toString` or `equals` over plaintext is exactly what this should not have.
 *
 * @param id a fresh random uuid. It names the sealed file on disk and is write-once.
 */
class NewAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val createdAt: Long,
)
