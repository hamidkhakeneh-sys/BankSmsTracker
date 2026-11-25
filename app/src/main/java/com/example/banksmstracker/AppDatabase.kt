package com.example.banksmstracker

import android.content.Context
import androidx.room.*

@Entity(tableName = "transaction_notes")
data class TransactionNoteEntity(
    @PrimaryKey val smsId: String,
    val note: String
)

@Dao
interface TransactionNoteDao {
    @Query("SELECT * FROM transaction_notes")
    suspend fun getAllNotes(): List<TransactionNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(noteEntity: TransactionNoteEntity)

    @Query("DELETE FROM transaction_notes WHERE smsId = :smsId")
    suspend fun deleteNote(smsId: String)
}

@Database(entities = [TransactionNoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): TransactionNoteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bank_sms_tracker_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
