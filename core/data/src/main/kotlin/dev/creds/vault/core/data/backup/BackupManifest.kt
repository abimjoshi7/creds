package dev.creds.vault.core.data.backup

import kotlinx.serialization.Serializable

/**
 * Record 0 of a `.vault` file: everything except attachment bytes, which follow in their
 * own records, numbered by [BackupAttachment.record].
 *
 * Lossless for what a user owns. Deliberately left out: soft-deleted fields, generator
 * history (it expires within a day), cached audit scores (recomputed), and settings.
 */
@Serializable
internal data class BackupManifest(
    val format: Int,
    val exportedAt: Long,
    val tags: List<BackupTag> = emptyList(),
    val items: List<BackupItem> = emptyList(),
)

@Serializable
internal data class BackupTag(val name: String, val color: Int? = null)

@Serializable
internal data class BackupItem(
    val uuid: String,
    val template: String,
    val title: String,
    val subtitle: String = "",
    val note: String = "",
    val icon: String? = null,
    val favorite: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val tags: List<String> = emptyList(),
    val fields: List<BackupField> = emptyList(),
    val associations: List<BackupAssociation> = emptyList(),
    val attachments: List<BackupAttachment> = emptyList(),
)

@Serializable
internal data class BackupField(
    val type: String,
    val label: String,
    val value: String,
    val sensitive: Boolean,
    val updatedAt: Long,
    val valueUpdatedAt: Long,
    /** Previous values, newest first. */
    val history: List<BackupHistory> = emptyList(),
)

@Serializable
internal data class BackupHistory(val value: String, val replacedAt: Long)

@Serializable
internal data class BackupAssociation(
    val kind: String,
    val value: String,
    val certSha256: String? = null,
    val confirmedAt: Long,
)

@Serializable
internal data class BackupAttachment(
    /** Index of the record holding this file's bytes; records 1..n follow the manifest. */
    val record: Int,
    val name: String,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
)
