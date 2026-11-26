package com.example.banksmstracker

import android.content.Context
import androidx.room.*

@Entity(tableName = "transaction_notes")
data class TransactionNoteEntity(
    @PrimaryKey val smsId: String,
    val note: String = "",
    val category: String = "",
    val isInternalTransferOverride: Boolean? = null
)

@Entity(tableName = "account_profiles")
data class AccountProfileEntity(
    @PrimaryKey val accountKey: String,
    val bankName: String,
    val accountNumber: String,
    val customTitle: String = "",
    val scope: String = "شخصی"
)

@Entity(tableName = "payer_unit_mappings")
data class PayerUnitMappingEntity(
    @PrimaryKey val payerIdentifier: String,
    val unitTitle: String
)

@Entity(tableName = "imported_bank_records")
data class ImportedRecordEntity(
    @PrimaryKey val recordId: String,
    val accountKey: String,
    val amount: Long,
    val balance: Long?,
    val isDeposit: Boolean,
    val timestampMillis: Long,
    val persianDate: String,
    val persianMonthYear: String,
    val description: String,
    val payerIdentifier: String = ""
)

@Entity(tableName = "ignored_items")
data class IgnoredItemEntity(
    @PrimaryKey val keyId: String,
    val isSenderBlocked: Boolean = false,
    val label: String = ""
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val isExpense: Boolean = true,
    val scope: String = "ALL"
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val settingKey: String,
    val settingValue: String
)

@Dao
interface AppDao {
    @Query("SELECT * FROM transaction_notes")
    suspend fun getAllNotes(): List<TransactionNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(noteEntity: TransactionNoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNotesBatch(notes: List<TransactionNoteEntity>)

    @Query("DELETE FROM transaction_notes WHERE smsId = :smsId")
    suspend fun deleteNote(smsId: String)

    @Query("SELECT * FROM account_profiles")
    suspend fun getAllAccountProfiles(): List<AccountProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccountProfile(profile: AccountProfileEntity)

    @Query("SELECT * FROM payer_unit_mappings")
    suspend fun getAllPayerMappings(): List<PayerUnitMappingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePayerMapping(mapping: PayerUnitMappingEntity)

    @Query("DELETE FROM payer_unit_mappings WHERE payerIdentifier = :payerId")
    suspend fun deletePayerMapping(payerId: String)

    @Query("SELECT * FROM imported_bank_records")
    suspend fun getAllImportedRecords(): List<ImportedRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedRecords(records: List<ImportedRecordEntity>)

    @Query("SELECT * FROM ignored_items")
    suspend fun getAllIgnored(): List<IgnoredItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addIgnored(item: IgnoredItemEntity)

    @Query("DELETE FROM ignored_items WHERE keyId = :keyId")
    suspend fun removeIgnored(keyId: String)

    @Query("SELECT * FROM categories")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE name = :name")
    suspend fun deleteCategory(name: String)

    @Query("SELECT * FROM app_settings")
    suspend fun getAllSettings(): List<AppSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(setting: AppSettingEntity)
}

@Database(
    entities = [
        TransactionNoteEntity::class,
        AccountProfileEntity::class,
        PayerUnitMappingEntity::class,
        ImportedRecordEntity::class,
        IgnoredItemEntity::class,
        CategoryEntity::class,
        AppSettingEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val DEFAULT_CATEGORIES = listOf(
            CategoryEntity("خوراک و سوپرمارکت", true, "ALL"),
            CategoryEntity("خودرو و سوخت", true, "ALL"),
            CategoryEntity("قبوض و اینترنت", true, "ALL"),
            CategoryEntity("پوشاک و خرید", true, "ALL"),
            CategoryEntity("درمان و دارو", true, "ALL"),
            CategoryEntity("رستوران و کافه", true, "ALL"),
            CategoryEntity("اقساط و وام", true, "ALL"),
            CategoryEntity("حقوق و دستمزد", false, "ALL"),
            CategoryEntity("سود و سرمایه‌گذاری", false, "ALL"),
            CategoryEntity("شارژ ماهیانه واحدها", false, "ALL"),
            CategoryEntity("صندوق عمرانی ساختمان", false, "ALL"),
            CategoryEntity("تعمیر و نگهداری آسانسور", true, "ALL"),
            CategoryEntity("نظافت و سرایداری", true, "ALL"),
            CategoryEntity("قبوض مشاعات (آب/برق/گاز)", true, "ALL"),
            CategoryEntity("تاسیسات و موتورخانه", true, "ALL"),
            CategoryEntity("بیمه و فضای سبز ساختمان", true, "ALL"),
            CategoryEntity("سایر موارد", true, "ALL")
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "holoo_sms_accounting_v4_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
