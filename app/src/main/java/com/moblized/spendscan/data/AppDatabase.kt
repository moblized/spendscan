package com.moblized.spendscan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendDao {
    // Categories
    @Query("SELECT * FROM categories ORDER BY fixed ASC, position ASC, id ASC")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY fixed ASC, position ASC, id ASC")
    suspend fun categoriesNow(): List<Category>

    @Insert
    suspend fun insertCategory(category: Category): Long

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("UPDATE receipts SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveReceipts(from: Long, to: Long)

    // Receipts
    @Query(
        "SELECT r.*, (SELECT COUNT(*) FROM items i WHERE i.receiptId = r.id) AS itemCount " +
            "FROM receipts r ORDER BY r.dateEpochDay DESC, r.id DESC",
    )
    fun receiptRows(): Flow<List<ReceiptRow>>

    @Query("SELECT * FROM receipts ORDER BY dateEpochDay DESC, id DESC")
    suspend fun receiptsNow(): List<Receipt>

    @Query("SELECT * FROM receipts WHERE dateEpochDay BETWEEN :from AND :to")
    fun receiptsBetween(from: Long, to: Long): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun receipt(id: Long): Flow<Receipt?>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun receiptNow(id: Long): Receipt?

    @Query("SELECT * FROM items WHERE receiptId = :receiptId ORDER BY position ASC, id ASC")
    fun items(receiptId: Long): Flow<List<ReceiptItem>>

    @Query("SELECT * FROM items WHERE receiptId = :receiptId ORDER BY position ASC, id ASC")
    suspend fun itemsNow(receiptId: Long): List<ReceiptItem>

    @Query("SELECT * FROM items ORDER BY receiptId DESC, position ASC")
    suspend fun allItemsNow(): List<ReceiptItem>

    @Query(
        "SELECT i.receiptId, i.name, i.quantity, i.amountCents, r.store, r.dateEpochDay FROM items i " +
            "JOIN receipts r ON r.id = i.receiptId WHERE i.name LIKE '%' || :query || '%' " +
            "ORDER BY r.dateEpochDay DESC, i.id DESC LIMIT 300",
    )
    fun searchItems(query: String): Flow<List<ItemHit>>

    // Fill-ups
    @Query(
        "SELECT f.*, r.store, r.dateEpochDay, r.totalCents FROM fill_ups f " +
            "JOIN receipts r ON r.id = f.receiptId ORDER BY r.dateEpochDay DESC, f.id DESC",
    )
    fun fillUps(): Flow<List<FillUpRow>>

    @Query(
        "SELECT f.*, r.store, r.dateEpochDay, r.totalCents FROM fill_ups f " +
            "JOIN receipts r ON r.id = f.receiptId ORDER BY r.dateEpochDay DESC, f.id DESC",
    )
    suspend fun fillUpsNow(): List<FillUpRow>

    @Query("SELECT * FROM fill_ups WHERE receiptId = :receiptId")
    fun fillUp(receiptId: Long): Flow<FillUp?>

    @Query("SELECT * FROM fill_ups WHERE receiptId = :receiptId")
    suspend fun fillUpNow(receiptId: Long): FillUp?

    @Query(
        "SELECT f.odometer FROM fill_ups f JOIN receipts r ON r.id = f.receiptId " +
            "WHERE f.odometer IS NOT NULL ORDER BY r.dateEpochDay DESC, f.id DESC LIMIT 1",
    )
    suspend fun lastOdometer(): Int?

    @Insert
    suspend fun insertFillUp(fillUp: FillUp): Long

    @Query("DELETE FROM fill_ups WHERE receiptId = :receiptId")
    suspend fun deleteFillUp(receiptId: Long)

    @Query("SELECT categoryId FROM receipts WHERE store = :store COLLATE NOCASE ORDER BY id DESC LIMIT 1")
    suspend fun lastCategoryFor(store: String): Long?

    @Query(
        "SELECT COUNT(*) FROM receipts WHERE store = :store COLLATE NOCASE AND dateEpochDay = :day " +
            "AND totalCents = :total AND id != :exceptId",
    )
    suspend fun sameReceiptCount(store: String, day: Long, total: Long, exceptId: Long): Int

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM receipts WHERE categoryId = :categoryId AND dateEpochDay BETWEEN :from AND :to")
    suspend fun categorySpent(categoryId: Long, from: Long, to: Long): Long

    @Insert
    suspend fun insertReceipt(receipt: Receipt): Long

    @Update
    suspend fun updateReceipt(receipt: Receipt)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: Long)

    @Insert
    suspend fun insertItems(items: List<ReceiptItem>)

    @Query("DELETE FROM items WHERE receiptId = :receiptId")
    suspend fun deleteItems(receiptId: Long)

    /** Saves a new or edited receipt with its items as one change. */
    @Transaction
    suspend fun saveReceipt(receipt: Receipt, items: List<ReceiptItem>, fillUp: FillUp? = null): Long {
        val id = if (receipt.id == 0L) {
            insertReceipt(receipt)
        } else {
            updateReceipt(receipt)
            deleteItems(receipt.id)
            receipt.id
        }
        insertItems(items.mapIndexed { i, item -> item.copy(id = 0, receiptId = id, position = i) })
        deleteFillUp(id)
        if (fillUp != null) insertFillUp(fillUp.copy(id = 0, receiptId = id))
        return id
    }

    @Transaction
    suspend fun removeCategory(category: Category, fallbackId: Long) {
        moveReceipts(category.id, fallbackId)
        deleteCategory(category)
    }
}

@Database(entities = [Category::class, Receipt::class, ReceiptItem::class, FillUp::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): SpendDao

    companion object {
        /** Version 2 adds fill-ups. Everything already saved stays as it is. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fill_ups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`receiptId` INTEGER NOT NULL, `gallonsMilli` INTEGER, `priceMilli` INTEGER, `odometer` INTEGER, " +
                        "`fullTank` INTEGER NOT NULL, FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fill_ups_receiptId` ON `fill_ups` (`receiptId`)")
            }
        }

        /** Fills in the starting categories as part of creating the database, so nothing can read it half set up. */
        val StartingCategories = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val names = DEFAULT_CATEGORIES + FALLBACK_CATEGORY
                names.forEachIndexed { i, name ->
                    db.execSQL(
                        "INSERT INTO categories (name, position, fixed) VALUES (?, ?, ?)",
                        arrayOf<Any>(name, i, if (name == FALLBACK_CATEGORY) 1 else 0),
                    )
                }
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "spendscan.db")
                .addCallback(StartingCategories)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
