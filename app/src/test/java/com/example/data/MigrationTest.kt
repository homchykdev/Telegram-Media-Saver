package com.example.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Exercises [MIGRATION_2_3] against a real SQLite implementation.
 *
 * There is no exported v2 schema JSON in the repo, so instead of letting Room open
 * the database we build the v2 table by hand, fill it, and then let the production
 * [MIGRATION_2_3] object upgrade it. The SQL used below to *create* the v2 table is
 * exactly what Room generated for version 2 of `history_entries`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val dbName = "migration_test.db"

    // The v2 schema: no attempts / uploadedAt, no indices.
    private val CREATE_V2_TABLE = """
        CREATE TABLE IF NOT EXISTS `history_entries` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `url` TEXT NOT NULL,
            `type` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `errorMessage` TEXT,
            `messageLink` TEXT,
            `mediaCount` INTEGER NOT NULL DEFAULT 0,
            `thumbnailPath` TEXT
        )
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDbFiles()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_V2_TABLE)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        try {
            helper.close()
        } catch (_: Exception) {
            // Robolectric may already have torn the helper down.
        }
        deleteDbFiles()
    }

    private fun deleteDbFiles() {
        val path = context.getDatabasePath(dbName).absolutePath
        File(path).delete()
        File("$path-journal").delete()
        File("$path-wal").delete()
        File("$path-shm").delete()
    }

    // --- helpers -------------------------------------------------------------

    private fun columnsOf(table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }

    private fun indexNames(): Set<String> =
        db.query("PRAGMA index_list(history_entries)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }

    private fun seedV2Rows() {
        db.execSQL(
            """
            INSERT INTO history_entries
                (url, type, timestamp, status, errorMessage, messageLink, mediaCount, thumbnailPath)
            VALUES
                ('https://www.tiktok.com/@done/video/1', 'video', 1000, 'done', NULL,
                 'https://t.me/c/123/456', 1, '/cache/thumb_done.jpg'),
                ('https://www.tiktok.com/@carousel/video/2', 'carousel', 2000, 'done', NULL,
                 'https://t.me/c/123/457', 4, '/cache/thumb_carousel.jpg'),
                ('https://vm.tiktok.com/ZMerr/', 'video', 3000, 'error',
                 'Download failed: 403', NULL, 0, NULL)
            """.trimIndent()
        )
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun `v2 table starts without attempts and uploadedAt`() {
        val columns = columnsOf("history_entries")
        assertTrue(columns.containsAll(
            setOf(
                "id", "url", "type", "timestamp", "status",
                "errorMessage", "messageLink", "mediaCount", "thumbnailPath"
            )
        ))
        assertTrue(!columns.contains("attempts"))
        assertTrue(!columns.contains("uploadedAt"))
    }

    @Test
    fun `migration adds attempts and uploadedAt columns`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        val columns = columnsOf("history_entries")
        assertTrue(columns.contains("attempts"))
        assertTrue(columns.contains("uploadedAt"))
    }

    @Test
    fun `migration preserves all rows`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.query("SELECT COUNT(*) FROM history_entries").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
    }

    @Test
    fun `migration preserves messageLink mediaCount and thumbnailPath on the done row`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.query(
            "SELECT url, messageLink, mediaCount, thumbnailPath FROM history_entries WHERE status = 'done' ORDER BY timestamp"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://www.tiktok.com/@done/video/1", c.getString(0))
            assertEquals("https://t.me/c/123/456", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals("/cache/thumb_done.jpg", c.getString(3))

            assertTrue(c.moveToNext())
            assertEquals("https://www.tiktok.com/@carousel/video/2", c.getString(0))
            assertEquals("https://t.me/c/123/457", c.getString(1))
            assertEquals(4, c.getInt(2))
            assertEquals("/cache/thumb_carousel.jpg", c.getString(3))
        }
    }

    @Test
    fun `migration preserves the error message on the error row`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.query("SELECT errorMessage, messageLink, thumbnailPath FROM history_entries WHERE status = 'error'")
            .use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Download failed: 403", c.getString(0))
                assertNull(c.getString(1))
                assertNull(c.getString(2))
            }
    }

    @Test
    fun `migration defaults attempts to zero and uploadedAt to null`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.query("SELECT id, attempts, uploadedAt FROM history_entries ORDER BY id").use { c ->
            var rows = 0
            while (c.moveToNext()) {
                rows++
                assertTrue("attempts must default to 0", c.getInt(1) == 0)
                assertTrue("uploadedAt must default to NULL", c.isNull(2))
            }
            assertEquals(3, rows)
        }
    }

    @Test
    fun `migration creates the timestamp and status indices`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        val indices = indexNames()
        assertNotNull(indices)
        assertTrue(indices.contains("index_history_entries_timestamp"))
        assertTrue(indices.contains("index_history_entries_status"))
    }

    @Test
    fun `migration is idempotent`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)
        // Running it again must not throw (CREATE INDEX IF NOT EXISTS) nor add columns.
        MIGRATION_2_3.migrate(db)

        val columns = columnsOf("history_entries")
        assertEquals(11, columns.size)
        db.query("SELECT COUNT(*) FROM history_entries").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
    }

    @Test
    fun `migrated rows can still be updated with the new columns`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.execSQL(
            """
            UPDATE history_entries
            SET status = 'done', attempts = attempts + 1, uploadedAt = 1700000000000
            WHERE id = 1
            """.trimIndent()
        )
        db.execSQL("UPDATE history_entries SET status = 'downloading', attempts = attempts + 1 WHERE id = 2")

        db.query("SELECT id, status, attempts, uploadedAt FROM history_entries ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals("done", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1700000000000L, c.getLong(3))

            assertTrue(c.moveToNext())
            assertEquals(2, c.getInt(0))
            assertEquals("downloading", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertTrue(c.isNull(3))
        }
    }

    @Test
    fun `migration leaves the type and timestamp columns untouched`() {
        seedV2Rows()
        MIGRATION_2_3.migrate(db)

        db.query("SELECT type, timestamp FROM history_entries ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("video", c.getString(0))
            assertEquals(1000L, c.getLong(1))

            assertTrue(c.moveToNext())
            assertEquals("carousel", c.getString(0))
            assertEquals(2000L, c.getLong(1))

            assertTrue(c.moveToNext())
            assertEquals("video", c.getString(0))
            assertEquals(3000L, c.getLong(1))
        }
    }
}