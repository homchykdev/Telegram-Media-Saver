package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Lifecycle of a single history row. Stored as plain strings for DB compatibility. */
object HistoryStatus {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val DONE = "done"
    const val ERROR = "error"
}

/** Media kind. Stored as plain strings for DB compatibility. */
object HistoryType {
    const val VIDEO = "video"
    const val CAROUSEL = "carousel"
}

@Entity(
    tableName = "history_entries",
    indices = [Index("timestamp"), Index("status")]
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val type: String,
    val timestamp: Long,
    val status: String,
    val errorMessage: String? = null,
    val messageLink: String? = null,
    val mediaCount: Int = 0,
    val thumbnailPath: String? = null,
    /** How many times this entry has been (re)submitted. Added in v3. */
    val attempts: Int = 0,
    /** Epoch ms of a successful Telegram upload. Added in v3. */
    val uploadedAt: Long? = null
)

@Dao
interface HistoryDao {

    /**
     * Bounded, newest-first.
     *
     * Room's invalidation is table-granular, so *any* write to `history_entries`
     * re-runs this query and re-materialises the whole result. A single download
     * writes four times (insert, markRunning twice, markDone), so an unbounded
     * `SELECT *` meant ~4 200 entity allocations and ~46 000 cursor reads per
     * download, competing for I/O with the download itself. `id DESC` breaks ties
     * between rows written in the same millisecond, which otherwise came back in a
     * non-deterministic order.
     */
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getRecentEntries(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history_entries WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HistoryEntry): Long

    @Update
    suspend fun updateEntry(entry: HistoryEntry)

    @Query("DELETE FROM history_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history_entries")
    suspend fun deleteAll()

    /**
     * Marks a job as running without touching any other column.
     *
     * A plain [updateEntry] of a reconstructed entity used to overwrite the whole row
     * and silently drop messageLink / mediaCount / thumbnailPath, which is how a
     * successfully uploaded video could end up displayed as a failed one.
     *
     * [attemptDelta] is 1 when the row is being submitted and 0 when an already
     * counted attempt is just starting. Both call sites used to increment, so every
     * download recorded two attempts.
     */
    @Query(
        """
        UPDATE history_entries
        SET status = :status,
            errorMessage = :errorMessage,
            attempts = attempts + :attemptDelta
        WHERE id = :id
        """
    )
    suspend fun markRunning(id: Long, status: String, errorMessage: String?, attemptDelta: Int)

    /**
     * Records a completed upload. Deliberately does NOT clear errorMessage, so a
     * user-visible history of why a retry was needed stays available in the UI.
     */
    @Query(
        """
        UPDATE history_entries
        SET status = :status,
            type = :type,
            messageLink = :messageLink,
            mediaCount = :mediaCount,
            thumbnailPath = COALESCE(:thumbnailPath, thumbnailPath),
            uploadedAt = :uploadedAt,
            errorMessage = NULL
        WHERE id = :id
        """
    )
    suspend fun markDone(
        id: Long,
        status: String,
        type: String,
        messageLink: String,
        mediaCount: Int,
        thumbnailPath: String?,
        uploadedAt: Long
    )

    /**
     * Records a failure while preserving everything already known about the entry
     * (link to the uploaded media, media count, resolved type, thumbnail).
     */
    @Query(
        """
        UPDATE history_entries
        SET status = :status, errorMessage = :errorMessage
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: Long, status: String, errorMessage: String?)

    /**
     * Fails a row only while it is still in flight.
     *
     * Cancelling is not instantaneous: `cancelUniqueWork` returns as soon as the
     * request is recorded, while the worker may be in the middle of committing its
     * upload. An unconditional write would then land after `markDone` and turn a
     * finished entry back into an error — which shows the user a "Повторить" button
     * for a video that is already in the group, and tapping it uploads a second copy.
     */
    @Query(
        """
        UPDATE history_entries
        SET status = :status, errorMessage = :errorMessage
        WHERE id = :id AND status IN ('queued', 'downloading')
        """
    )
    suspend fun markCancelled(id: Long, status: String, errorMessage: String?)

    /**
     * Fails rows left mid-flight by a dead process, sparing any whose worker is
     * still alive.
     *
     * [liveIds] must never be empty — `id NOT IN ()` is not valid SQL — callers pass
     * a sentinel instead.
     */
    @Query(
        """
        UPDATE history_entries
        SET status = :status, errorMessage = :errorMessage
        WHERE status IN ('queued', 'downloading') AND id NOT IN (:liveIds)
        """
    )
    suspend fun failOrphanedExcluding(status: String, errorMessage: String?, liveIds: List<Long>)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(history_entries)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    add(cursor.getString(nameIndex))
                }
            }
        }
        if ("mediaCount" !in columns) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN mediaCount INTEGER NOT NULL DEFAULT 0")
        }
        if ("thumbnailPath" !in columns) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN thumbnailPath TEXT DEFAULT NULL")
        }
    }
}

/**
 * v2 -> v3. Adds attempt tracking and an upload timestamp, and indexes the columns
 * the history list actually filters and orders by.
 *
 * This is a purely additive migration: no column is dropped, renamed or retyped, so
 * every existing row survives with its messageLink, mediaCount and thumbnailPath
 * intact.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(history_entries)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    add(cursor.getString(nameIndex))
                }
            }
        }
        if ("attempts" !in columns) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        }
        if ("uploadedAt" !in columns) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN uploadedAt INTEGER DEFAULT NULL")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_entries_timestamp ON history_entries (timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_history_entries_status ON history_entries (status)")
    }
}

@Database(entities = [HistoryEntry::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        private const val DB_NAME = "history_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // No fallbackToDestructiveMigration(): a future schema bump must fail
                // loudly in review rather than silently wiping users' history.
                .build()
    }
}

class HistoryRepository(private val historyDao: HistoryDao) {

    /**
     * The newest [HISTORY_PAGE_SIZE] rows, which is far more than the list can show
     * and keeps the cost of every re-query proportional to what is on screen rather
     * than to the whole accumulated history.
     */
    val recentEntries: Flow<List<HistoryEntry>> = historyDao.getRecentEntries(HISTORY_PAGE_SIZE)

    suspend fun insert(entry: HistoryEntry): Long = historyDao.insertEntry(entry)

    suspend fun update(entry: HistoryEntry) = historyDao.updateEntry(entry)

    suspend fun get(id: Long): HistoryEntry? = historyDao.getById(id)

    suspend fun delete(entry: HistoryEntry) = historyDao.deleteById(entry.id)

    suspend fun deleteAll() = historyDao.deleteAll()

    suspend fun markRunning(id: Long, errorMessage: String? = null, countAttempt: Boolean = true) =
        historyDao.markRunning(
            id = id,
            status = HistoryStatus.DOWNLOADING,
            errorMessage = errorMessage,
            attemptDelta = if (countAttempt) 1 else 0
        )

    suspend fun markDone(
        id: Long,
        type: String,
        messageLink: String,
        mediaCount: Int,
        thumbnailPath: String?
    ) = historyDao.markDone(
        id = id,
        status = HistoryStatus.DONE,
        type = type,
        messageLink = messageLink,
        mediaCount = mediaCount,
        thumbnailPath = thumbnailPath,
        uploadedAt = System.currentTimeMillis()
    )

    suspend fun markFailed(id: Long, errorMessage: String?) =
        historyDao.markFailed(id, HistoryStatus.ERROR, errorMessage)

    suspend fun markCancelled(id: Long, errorMessage: String?) =
        historyDao.markCancelled(id, HistoryStatus.ERROR, errorMessage)

    /**
     * @param liveEntryIds rows whose worker is still enqueued or running; they are
     *   left untouched. Must be safe to pass empty.
     */
suspend fun failOrphaned(errorMessage: String, liveEntryIds: Set<Long>) {
        // `NOT IN ()` is a syntax error, so an empty set becomes an impossible id.
        val ids = liveEntryIds.ifEmpty { setOf(NO_SUCH_ID) }
        historyDao.failOrphanedExcluding(HistoryStatus.ERROR, errorMessage, ids.toList())
    }

    private companion object {
        const val NO_SUCH_ID = -1L
        const val HISTORY_PAGE_SIZE = 200
    }
}