package com.example.banksmstracker

import android.content.Context
import androidx.room.*

// ۱. یادداشت‌ها، دسته‌بندی و وضعیت انتقال داخلی هر پیامک
@Entity(tableName = "transaction_notes")
data class TransactionNoteEntity(
    @PrimaryKey val smsId: String,
    val note: String = "",
    val category: String = "",
    val isInternalTransferOverride: Boolean? = null // null = تشخیص خودکار، true = انتقال داخلی، false = عادی
)

// ۲. پروفایل حساب‌های بانکی برای تفکیک "شخصی" از "مدیریت ساختمان"
@Entity(tableName = "account_profiles")
data class AccountProfileEntity(
    @PrimaryKey val accountKey: String, // ترکیب بانک و شماره حساب مثلاً "بانک ملی - 42007"
    val bankName: String,
    val accountNumber: String,
    val customTitle: String = "",
    val scope: String = "PERSONAL" // "PERSONAL" (شخصی) یا "BUILDING" (مدیریت ساختمان)
)

// ۳. پیامک‌ها و سرشماره‌های مسدود شده (تشخیص اشتباه)
@Entity(tableName = "ignored_items")
data class IgnoredItemEntity(
    @PrimaryKey val keyId: String,
    val isSenderBlocked: Boolean = false,
    val label: String = ""
)

// ۴. دسته‌بندی‌های هزینه و درآمد (تفکیک‌شده برای شخصی و ساختمان)
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val name: String,
    val isExpense: Boolean = true,
    val scope: String = "ALL" // "ALL", "PERSONAL", "BUILDING"
)

@Dao
interface AppDao {
    @Query("SELECT * FROM transaction_notes")
    suspend fun getAllNotes(): List<TransactionNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNote(noteEntity: TransactionNoteEntity)

    @Query("DELETE FROM transaction_notes WHERE smsId = :smsId")
    suspend fun deleteNote(smsId: String)

    @Query("SELECT * FROM account_profiles")
    suspend fun getAllAccountProfiles(): List<AccountProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAccountProfile(profile: AccountProfileEntity)

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
}

@Database(
    entities = [
        TransactionNoteEntity::class,
        AccountProfileEntity::class,
        IgnoredItemEntity::class,
        CategoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val DEFAULT_CATEGORIES = listOf(
            // دسته‌های شخصی
            CategoryEntity("خوراک و سوپرمارکت", true, "PERSONAL"),
            CategoryEntity("خودرو و سوخت", true, "PERSONAL"),
            CategoryEntity("قبوض و اینترنت", true, "PERSONAL"),
            CategoryEntity("پوشاک و خرید", true, "PERSONAL"),
            CategoryEntity("درمان و دارو", true, "PERSONAL"),
            CategoryEntity("رستوران و کافه", true, "PERSONAL"),
            CategoryEntity("اقساط و وام", true, "PERSONAL"),
            CategoryEntity("حقوق و دستمزد", false, "PERSONAL"),
            CategoryEntity("سود و سرمایه‌گذاری", false, "PERSONAL"),
            // دسته‌های مدیریت ساختمان
            CategoryEntity("شارژ ماهیانه واحدها", false, "BUILDING"),
            CategoryEntity("صندوق عمرانی ساختمان", false, "BUILDING"),
            CategoryEntity("تعمیر و نگهداری آسانسور", true, "BUILDING"),
            CategoryEntity("نظافت و سرایداری", true, "BUILDING"),
            CategoryEntity("قبوض مشاعات (آب/برق/گاز)", true, "BUILDING"),
            CategoryEntity("تاسیسات و موتورخانه", true, "BUILDING"),
            CategoryEntity("بیمه و فضای سبز ساختمان", true, "BUILDING"),
            // عمومی
            CategoryEntity("سایر موارد", true, "ALL")
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "holoo_sms_accounting_v3_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
