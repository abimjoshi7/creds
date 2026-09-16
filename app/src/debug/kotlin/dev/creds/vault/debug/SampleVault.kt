package dev.creds.vault.debug

import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.repository.TagResult
import dev.creds.vault.core.data.repository.VaultRepository
import dev.creds.vault.core.domain.template.TemplateCatalog
import dev.creds.vault.core.model.Template
import dev.creds.vault.core.model.VaultItem
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Fake items for exercising the vault list on a device, debug builds only.
 *
 * Until item editing and import exist there is no other way to get rows on screen. Every
 * value is invented and obviously so; the release source set has a stub that adds
 * nothing.
 */
object SampleVault {

    const val AVAILABLE: Boolean = true

    suspend fun seed(repository: VaultRepository, vaultKey: VaultKey, now: Long): Int {
        val tagIds = listOf("work", "personal", "finance").associateWith { name ->
            when (val result = repository.createTag(name)) {
                is TagResult.Saved -> result.tag.id
                else -> repository.observeTags().first().first { it.name.equals(name, true) }.id
            }
        }

        val samples = listOf(
            Sample(Template.LOGIN, "Sample GitHub", listOf("work"), favorite = true,
                values = mapOf("Username" to "octo-sample", "Password" to "sample-only-Tr4in", "Website" to "https://github.com")),
            Sample(Template.LOGIN, "Sample Mail", listOf("personal"),
                values = mapOf("Email" to "someone@example.com", "Password" to "sample-only-M4il", "Website" to "https://mail.example.com")),
            Sample(Template.BANK_ACCOUNT, "Example Savings", listOf("finance", "personal"),
                values = mapOf("Bank" to "Example Bank", "Account number" to "000000000", "Password" to "sample-only-B4nk")),
            Sample(Template.CARD, "Example Visa", listOf("finance"),
                values = mapOf("Cardholder" to "Sample Person", "Number" to "4111111111111111", "Issuing bank" to "Example Bank")),
            Sample(Template.WIFI, "Home Wi-Fi", listOf("personal"),
                values = mapOf("Network name" to "sample-network", "Password" to "sample-only-W1fi")),
            Sample(Template.SERVER, "Build server", listOf("work"),
                values = mapOf("Host" to "ci.example.internal", "Username" to "deploy")),
            Sample(Template.NOTE, "Recovery codes", emptyList(),
                note = "sample-only recovery codes: 0000-0000"),
            Sample(Template.IDENTITY, "Sample identity", listOf("personal"),
                values = mapOf("Full name" to "Sample Person", "Email" to "someone@example.com")),
            Sample(Template.LOGIN, "Old forum", emptyList(), archived = true,
                values = mapOf("Username" to "retired-sample")),
        )

        samples.forEach { sample ->
            val fields = TemplateCatalog.newFields(sample.template, now)
                .map { field -> sample.values[field.label]?.let { field.copy(value = it) } ?: field }
                .filter { it.value.isNotEmpty() }
            val uuid = UUID.randomUUID().toString()

            repository.save(
                vaultKey,
                VaultItem(
                    uuid = uuid,
                    template = sample.template,
                    title = sample.title,
                    subtitle = TemplateCatalog.subtitleFor(sample.template, fields),
                    note = sample.note,
                    favorite = sample.favorite,
                    archived = sample.archived,
                    createdAt = now,
                    updatedAt = now,
                    fields = fields,
                ),
            )
            repository.setItemTags(uuid, sample.tags.mapTo(HashSet()) { tagIds.getValue(it) }, now)
        }

        return samples.size
    }

    private class Sample(
        val template: Template,
        val title: String,
        val tags: List<String>,
        val values: Map<String, String> = emptyMap(),
        val note: String = "",
        val favorite: Boolean = false,
        val archived: Boolean = false,
    )
}
