package dev.creds.vault.core.data.backup

import dev.creds.vault.core.crypto.AesGcm
import dev.creds.vault.core.crypto.Hkdf
import dev.creds.vault.core.crypto.KdfParams
import dev.creds.vault.core.crypto.PasswordHasher
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.crypto.useAndWipe
import dev.creds.vault.core.crypto.wipe
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.domain.importer.ImportAttachmentSource
import dev.creds.vault.core.domain.importer.ImportWarnings
import dev.creds.vault.core.domain.importer.ImportedAttachment
import dev.creds.vault.core.domain.importer.ImportedItem
import dev.creds.vault.core.domain.importer.ParsedImport
import dev.creds.vault.core.model.AssociationKind
import dev.creds.vault.core.model.FieldHistoryEntry
import dev.creds.vault.core.model.FieldType
import dev.creds.vault.core.model.ItemAssociation
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultField
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted export and import of a whole vault, as a `.vault` file.
 *
 * The file has its own password, stretched with Argon2id exactly like the master
 * password, so a backup is as hard to attack as the vault — and a leaked backup reveals
 * nothing about the master password. Plaintext exists only in memory, one record at a
 * time; no intermediate file is ever written.
 */
@Singleton
class VaultBackup @Inject constructor(
    private val hasher: PasswordHasher,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /**
     * Writes every item — archived and trashed included — with its fields, history, tags,
     * trusted apps and sites, and attachments to [output].
     *
     * [password] belongs to the caller, who must wipe it. Argon2id is slow on purpose, so
     * call this off the main thread.
     *
     * @return how many items were written.
     */
    suspend fun export(
        repository: VaultRepository,
        vaultKey: VaultKey,
        password: CharArray,
        output: OutputStream,
        now: Long,
        params: KdfParams = KdfParams.DEFAULT,
    ): Int {
        val header = VaultArchive.Header(params, AesGcm.randomBytes(KdfParams.SALT_BYTES))
        val items = repository.allItemUuids().mapNotNull { repository.load(vaultKey, it) }
        val sources = mutableListOf<Pair<String, String>>()

        val manifest = BackupManifest(
            format = VaultArchive.FORMAT,
            exportedAt = now,
            tags = repository.observeTags().first().map { BackupTag(it.name, it.color) },
            items = items.map { item ->
                BackupItem(
                    uuid = item.uuid,
                    template = item.template.id,
                    title = item.title,
                    subtitle = item.subtitle,
                    note = item.note,
                    icon = item.icon,
                    favorite = item.favorite,
                    archived = item.archived,
                    trashed = item.trashed,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    tags = item.tags.map { it.name },
                    fields = item.fields.filter { !it.deleted }.sortedBy { it.order }.map { field ->
                        BackupField(
                            type = field.type.id,
                            label = field.label,
                            value = field.value,
                            sensitive = field.sensitive,
                            updatedAt = field.updatedAt,
                            valueUpdatedAt = field.valueUpdatedAt,
                            history = repository.fieldHistory(vaultKey, field.uid)
                                .map { BackupHistory(it.value, it.replacedAt) },
                        )
                    },
                    associations = repository.associations(item.uuid).map {
                        BackupAssociation(it.kind.id, it.value, it.certSha256, it.confirmedAt)
                    },
                    attachments = item.attachments.map { attachment ->
                        sources += item.uuid to attachment.id
                        BackupAttachment(
                            record = sources.size,
                            name = attachment.name,
                            mimeType = attachment.mimeType,
                            size = attachment.size,
                            createdAt = attachment.createdAt,
                        )
                    },
                )
            },
        )

        fileKey(password, header).useAndWipe { key ->
            val writer = VaultArchive.Writer(output, key, header)
            json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8)
                .useAndWipe { writer.write(it, last = sources.isEmpty()) }
            sources.forEachIndexed { i, (itemUuid, attachmentId) ->
                val bytes = repository.openAttachment(vaultKey, itemUuid, attachmentId)
                    ?: throw BackupException("A file attached to an item is missing, so the backup was not finished.")
                bytes.useAndWipe { writer.write(it, last = i == sources.lastIndex) }
            }
        }
        return items.size
    }

    /** Whether the first bytes of a chosen file say it is a `.vault` backup. */
    fun isBackup(prefix: ByteArray): Boolean = VaultArchive.looksLikeArchive(prefix)

    /**
     * Opens a backup: checks the password and reads the manifest, ready for the import
     * preview. Attachments are not read yet; [OpenedBackup.attachments] streams them from
     * a fresh read of the same file when the import is written.
     *
     * @throws BackupException for a wrong password or a damaged, truncated or foreign file.
     */
    fun open(input: InputStream, password: CharArray): OpenedBackup {
        val header = VaultArchive.readHeader(input)
        val key = fileKey(password, header)
        try {
            val reader = VaultArchive.Reader(input, key, header)
            val manifestBytes = reader.next() ?: throw BackupException("This backup is empty.")
            val manifest = try {
                manifestBytes.useAndWipe { json.decodeFromString(BackupManifest.serializer(), it.decodeToString()) }
            } catch (e: SerializationException) {
                throw BackupException("This backup is damaged.", e)
            } catch (e: IllegalArgumentException) {
                throw BackupException("This backup is damaged.", e)
            }
            return OpenedBackup(header, key, toParsedImport(manifest), manifest.items.sumOf { it.attachments.size })
        } catch (e: Throwable) {
            key.wipe()
            throw e
        }
    }

    internal fun fileKey(password: CharArray, header: VaultArchive.Header): ByteArray =
        hasher.deriveMasterKey(password, header.salt, header.params).useAndWipe { stretched ->
            // Domain-separated from anything else Argon2id output is used for.
            Hkdf.derive(ikm = stretched, salt = null, info = INFO_EXPORT, length = AesGcm.KEY_BYTES)
        }

    private fun toParsedImport(manifest: BackupManifest): ParsedImport {
        val colors = manifest.tags.mapNotNull { tag -> tag.color?.let { tag.name to it } }.toMap()
        val items = manifest.items.map { backup ->
            val fields = backup.fields.mapIndexed { index, field ->
                val type = FieldType.fromId(field.type)
                VaultField(
                    uid = 0,
                    type = type,
                    label = field.label,
                    value = field.value,
                    // Only ever raised, never weakened, like every importer.
                    sensitive = field.sensitive || type.defaultSensitive,
                    order = index,
                    updatedAt = field.updatedAt,
                    valueUpdatedAt = field.valueUpdatedAt,
                )
            }
            ImportedItem(
                item = VaultItem(
                    uuid = backup.uuid,
                    template = Template.fromId(backup.template),
                    title = backup.title,
                    subtitle = backup.subtitle,
                    note = backup.note,
                    icon = backup.icon,
                    favorite = backup.favorite,
                    archived = backup.archived,
                    trashed = backup.trashed,
                    createdAt = backup.createdAt,
                    updatedAt = backup.updatedAt,
                    fields = fields,
                ),
                history = backup.fields.withIndex()
                    .filter { it.value.history.isNotEmpty() }
                    .associate { (index, field) ->
                        index to field.history.mapIndexed { i, h -> FieldHistoryEntry(id = i.toLong(), value = h.value, replacedAt = h.replacedAt) }
                    },
                tagNames = backup.tags,
                tagColors = backup.tags.mapNotNull { name -> colors[name]?.let { name to it } }.toMap(),
                associations = backup.associations.mapNotNull { a ->
                    val kind = AssociationKind.fromId(a.kind) ?: return@mapNotNull null
                    ItemAssociation(kind, a.value, a.certSha256, a.confirmedAt)
                },
                attachments = backup.attachments.map {
                    ImportedAttachment(
                        sourceId = it.record.toString(),
                        name = it.name,
                        mimeType = it.mimeType,
                        size = it.size,
                        createdAt = it.createdAt,
                    )
                },
            )
        }
        return ParsedImport(items, ImportWarnings())
    }

    private companion object {
        val INFO_EXPORT = "export".toByteArray(StandardCharsets.UTF_8)
    }
}

/**
 * A backup whose password has been checked, holding its file key until [close].
 *
 * The key is kept, rather than the password, so writing the import does not run Argon2id
 * a second time. Close it when the import is written, cancelled, or the vault locks.
 */
class OpenedBackup internal constructor(
    private val header: VaultArchive.Header,
    private val key: ByteArray,
    val parsed: ParsedImport,
    val attachmentCount: Int,
) : AutoCloseable {

    /**
     * Streams attachment bytes from [reopen], a fresh stream over the same file, checking
     * every record again as it goes. The source closes the stream itself.
     */
    fun attachments(reopen: () -> InputStream): ImportAttachmentSource = ImportAttachmentSource { block ->
        if (attachmentCount == 0) return@ImportAttachmentSource
        reopen().use { input ->
            val fresh = VaultArchive.readHeader(input)
            if (!fresh.bytes.contentEquals(header.bytes)) throw BackupException("The backup changed while it was being imported.")
            val reader = VaultArchive.Reader(input, key, header)
            reader.next()?.wipe() ?: throw BackupException("This backup is damaged.")
            var record = 1
            while (true) {
                val bytes = reader.next() ?: break
                try {
                    block(record.toString(), bytes)
                } finally {
                    bytes.wipe()
                }
                record++
            }
            if (record - 1 != attachmentCount) throw BackupException("This backup is incomplete.")
        }
    }

    override fun close() {
        key.wipe()
    }
}
