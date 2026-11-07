package com.moblized.spendscan.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query(
        "SELECT f.fuelType FROM fill_ups f JOIN receipts r ON r.id = f.receiptId " +
            "WHERE f.fuelType IS NOT NULL ORDER BY r.dateEpochDay DESC, f.id DESC LIMIT 1",
    )
    suspend fun lastFuelTypeName(): String?

    /** What went in the tank last time, to start the next fill-up with. */
    suspend fun lastFuelType(): FuelType? = FuelType.of(lastFuelTypeName())

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
            markUploadChanged(receipt.id)
            receipt.id
        }
        insertItems(items.mapIndexed { i, item -> item.copy(id = 0, receiptId = id, position = i) })
        deleteFillUp(id)
        if (fillUp != null) insertFillUp(fillUp.copy(id = 0, receiptId = id))
        return id
    }

    // Paperless
    @Query(
        "SELECT * FROM receipts r WHERE r.photos != '' AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.receiptId = r.id) " +
            "ORDER BY r.id ASC LIMIT 100",
    )
    suspend fun receiptsToSend(): List<Receipt>

    @Query("SELECT * FROM uploads WHERE problem IS NULL AND (documentId IS NULL OR (documentId > 0 AND changes > 0)) ORDER BY receiptId ASC")
    suspend fun uploadsToFinish(): List<Upload>

    @Query("SELECT * FROM uploads WHERE receiptId = :receiptId")
    fun upload(receiptId: Long): Flow<Upload?>

    @Query("SELECT * FROM uploads WHERE receiptId = :receiptId")
    suspend fun uploadNow(receiptId: Long): Upload?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUpload(upload: Upload)

    @Query("UPDATE uploads SET documentId = :documentId WHERE receiptId = :receiptId")
    suspend fun setUploadDocument(receiptId: Long, documentId: Long)

    @Query("UPDATE uploads SET problem = :problem WHERE receiptId = :receiptId")
    suspend fun setUploadProblem(receiptId: Long, problem: String)

    /** Paperless has heard about [seen] edits. Any made while it was being told stay to go next time. */
    @Query("UPDATE uploads SET changes = MAX(0, changes - :seen), tagId = :tagId WHERE receiptId = :receiptId")
    suspend fun uploadUpdated(receiptId: Long, tagId: Long?, seen: Int)

    @Query("UPDATE uploads SET changes = changes + 1 WHERE receiptId = :receiptId")
    suspend fun markUploadChanged(receiptId: Long)

    @Query("DELETE FROM uploads WHERE problem IS NOT NULL")
    suspend fun clearUploadProblems()

    @Query(
        "SELECT (SELECT COUNT(*) FROM receipts r WHERE r.photos != '' AND NOT EXISTS (SELECT 1 FROM uploads u WHERE u.receiptId = r.id)) AS waiting, " +
            "(SELECT COUNT(*) FROM uploads WHERE problem IS NULL) AS sent, " +
            "(SELECT COUNT(*) FROM uploads WHERE problem IS NOT NULL) AS refused, " +
            "(SELECT COUNT(*) FROM receipts WHERE photos = '') AS noPhotos, " +
            "(SELECT problem FROM uploads WHERE problem IS NOT NULL ORDER BY sentAt DESC LIMIT 1) AS lastProblem",
    )
    fun uploadCounts(): Flow<UploadCounts>

    @Transaction
    suspend fun removeCategory(category: Category, fallbackId: Long) {
        moveReceipts(category.id, fallbackId)
        deleteCategory(category)
    }
}

@Database(entities = [Category::class, Receipt::class, ReceiptItem::class, FillUp::class, Upload::class], version = 4, exportSchema = false)
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

        /** Version 3 keeps track of what's gone to Paperless-ngx. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uploads` (`receiptId` INTEGER NOT NULL, `taskId` TEXT, `documentId` INTEGER, " +
                        "`tagId` INTEGER, `changes` INTEGER NOT NULL, `problem` TEXT, `sentAt` INTEGER NOT NULL, PRIMARY KEY(`receiptId`), " +
                        "FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** Version 4 notes what went in the tank. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `fill_ups` ADD COLUMN `fuelType` TEXT")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
