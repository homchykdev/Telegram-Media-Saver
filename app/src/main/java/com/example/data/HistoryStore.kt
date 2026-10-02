package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val type: String, // "video" or "carousel"
    val timestamp: Long,
    val status: String, // "queued", "downloading", "done", "error"
    val errorMessage: String? = null,
    val messageLink: String? = null,
    val mediaCount: Int = 0,
    val thumbnailPath: String? = null
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: HistoryEntry): Long

    @Update
    suspend fun updateEntry(entry: HistoryEntry)

    @Delete
    suspend fun deleteEntry(entry: HistoryEntry)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query("PRAGMA table_info(history_entries)")
        var hasMediaCount = false
        var hasThumbnailPath = false
        while (cursor.moveToNext()) {
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex != -1) {
                val columnName = cursor.getString(nameIndex)
                if (columnName == "mediaCount") {
                    hasMediaCount = true
                }
                if (columnName == "thumbnailPath") {
                    hasThumbnailPath = true
                }
            }
        }
        cursor.close()

        if (!hasMediaCount) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN mediaCount INTEGER NOT NULL DEFAULT 0")
        }
        if (!hasThumbnailPath) {
            db.execSQL("ALTER TABLE history_entries ADD COLUMN thumbnailPath TEXT DEFAULT NULL")
        }
    }
}

@Database(entities = [HistoryEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "history_database"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // Prevent crashes if schema updates
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class HistoryRepository(private val historyDao: HistoryDao) {
    val allEntries: Flow<List<HistoryEntry>> = historyDao.getAllEntries()

    suspend fun insert(entry: HistoryEntry): Long = historyDao.insertEntry(entry)

    suspend fun update(entry: HistoryEntry) = historyDao.updateEntry(entry)

    suspend fun delete(entry: HistoryEntry) = historyDao.deleteEntry(entry)
}
