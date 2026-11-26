package com.example.banksmstracker

import android.content.Context
import androidx.room.*

@Entity(tableName = "transaction_notes")
data class TransactionNoteEntity(
    @PrimaryKey val smsId: String,
    val note: String = "",
    val category: String = "",
    val isInternalTransferOverride: Boolean? = null,
    val unitId: Int? = null,
    val receiptImagePath: String = ""
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
    val unitId: Int,
    val unitTitle: String
)

@Entity(tableName = "building_units")
data class BuildingUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val unitNumber: String,
    val residentName: String,
    val monthlyCharge: Long,
    val phoneNumber: String = "",
    val openingBalance: Long = 0L
)

@Entity(tableName = "unit_charges")
data class UnitChargeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val unitId: Int,
    val amount: Long,
    val title: String,
    val persianDate: String,
    val persianMonthYear: String,
    val timestampMillis: Long
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
    val scope: String = "شخصی"
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

    @Query("SELECT * FROM building_units ORDER BY id ASC")
    suspend fun getAllBuildingUnits(): List<BuildingUnitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBuildingUnit(unit: BuildingUnitEntity): Long

    @Query("DELETE FROM building_units WHERE id = :unitId")
    suspend fun deleteBuildingUnit(unitId: Int)

    @Query("SELECT * FROM unit_charges ORDER BY timestampMillis DESC")
    suspend fun getAllUnitCharges(): List<UnitChargeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnitCharges(charges: List<UnitChargeEntity>)

    @Query("DELETE FROM unit_charges WHERE id = :chargeId")
    suspend fun deleteUnitCharge(chargeId: Int)

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
        BuildingUnitEntity::class,
        UnitChargeEntity::class,
        ImportedRecordEntity::class,
        IgnoredItemEntity::class,
        CategoryEntity::class,
        AppSettingEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val DEFAULT_CATEGORIES = listOf(
            CategoryEntity("خوراک و سوپرمارکت", true, "شخصی"),
            CategoryEntity("خودرو و سوخت", true, "شخصی"),
            CategoryEntity("قبوض و اینترنت", true, "شخصی"),
            CategoryEntity("پوشاک و خرید", true, "شخصی"),
            CategoryEntity("درمان و دارو", true, "شخصی"),
            CategoryEntity("رستوران و کافه", true, "شخصی"),
            CategoryEntity("اقساط و وام", true, "شخصی"),
            CategoryEntity("حقوق و دستمزد", false, "شخصی"),
            CategoryEntity("سود و سرمایه‌گذاری", false, "شخصی"),
            CategoryEntity("سایر هزینه‌های شخصی", true, "شخصی"),
            CategoryEntity("شارژ ماهیانه واحدها", false, "مدیریت ساختمان"),
            CategoryEntity("صندوق عمرانی و ذخیره", false, "مدیریت ساختمان"),
            CategoryEntity("تعمیر و نگهداری آسانسور", true, "مدیریت ساختمان"),
            CategoryEntity("نظافت و سرایداری", true, "مدیریت ساختمان"),
            CategoryEntity("قبوض مشاعات (آب/برق/گاز)", true, "مدیریت ساختمان"),
            CategoryEntity("تاسیسات و موتورخانه", true, "مدیریت ساختمان"),
            CategoryEntity("بیمه و فضای سبز ساختمان", true, "مدیریت ساختمان"),
            CategoryEntity("تعمیرات عمومی ساختمان", true, "مدیریت ساختمان")
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "holoo_sharjeman_v8_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
