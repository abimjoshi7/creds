package dev.creds.vault.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.creds.vault.core.crypto.VaultKey
import dev.creds.vault.core.data.crypto.FieldCipher
import dev.creds.vault.core.data.repository.VaultRepository
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema migrations, against SQLCipher on a device.
 *
 * There is no destructive fallback (see `VaultDatabaseFactory`), so a migration that
 * fails leaves the user unable to open their vault at all. Each one is proven twice: by
 * Room's own validator, and by opening a real older vault through the production path.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vaultKey = VaultKey(ByteArray(32) { 3 })

    @get:Rule
    val helper: MigrationTestHelper = run {
        SqlCipherNative.ensureLoaded()
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CredsDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(vaultKey.databasePassphrase()),
        )
    }

    @Before
    @After
    fun deleteVault() {
        context.deleteDatabase(CredsDatabase.NAME)
    }

    @Test
    fun migrate1To2AddsGeneratorHistory() {
        helper.createDatabase(CredsDatabase.NAME, 1).close()

        helper.runMigrationsAndValidate(CredsDatabase.NAME, 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM generator_history").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun aVersion1VaultOpensThroughTheProductionPathWithItsItems() = runBlocking {
        helper.createDatabase(CredsDatabase.NAME, 1).use { db ->
            // A real version-1 vault also has the FTS table its creation callback made.
            db.execSQL(FtsSchema.CREATE)
            db.execSQL(
                """
                INSERT INTO items (uuid, template, title, subtitle, favorite, archived, trashed, created_at, updated_at)
                VALUES ('kept', 'login', 'Survivor', '', 0, 0, 0, 1, 1)
                """,
            )
        }

        val database = VaultDatabaseFactory(context).open(vaultKey)
        try {
            assertNotNull(database.itemDao().byUuid("kept"))
            VaultRepository(database, FieldCipher()).recordGenerated(vaultKey, "fresh-value", now = 10)
            assertEquals(1, database.generatorHistoryDao().all().size)
        } finally {
            database.close()
        }
    }
}
