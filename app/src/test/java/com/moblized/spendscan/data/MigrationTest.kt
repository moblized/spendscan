package com.moblized.spendscan.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Installing an update over an older version has to keep every receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    /** A database exactly as version 1 of the app created it, with something in it. */
    private fun versionOneDatabase(name: String) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        listOf(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `budgetCents` INTEGER, `position` INTEGER NOT NULL, `fixed` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `store` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `totalCents` INTEGER NOT NULL, `subtotalCents` INTEGER, `taxCents` INTEGER, `tipCents` INTEGER, `discountCents` INTEGER, `categoryId` INTEGER NOT NULL, `payment` TEXT, `note` TEXT, `photos` TEXT NOT NULL, `rawText` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_receipts_dateEpochDay` ON `receipts` (`dateEpochDay`)",
            "CREATE INDEX IF NOT EXISTS `index_receipts_categoryId` ON `receipts` (`categoryId`)",
            "CREATE TABLE IF NOT EXISTS `items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `receiptId` INTEGER NOT NULL, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `amountCents` INTEGER NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_items_receiptId` ON `items` (`receiptId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'version-one')",
            "INSERT INTO categories (id, name, budgetCents, position, fixed) VALUES (1, 'Groceries', 50000, 0, 0), (2, 'Gas & auto', NULL, 1, 0), (3, 'Other', NULL, 2, 1)",
            "INSERT INTO receipts (id, store, dateEpochDay, totalCents, taxCents, categoryId, photos, createdAt) VALUES (7, 'Kroger', 20000, 693, 45, 1, 'a.jpg', 1)",
            "INSERT INTO items (receiptId, name, quantity, amountCents, position) VALUES (7, 'Milk', 1.0, 349, 0)",
        ).forEach(db::execSQL)
        db.version = 1
        db.close()
    }

    @Test
    fun upgradingKeepsEverythingAndAddsFillUps() = runBlocking {
        versionOneDatabase("upgrade.db")
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java, "upgrade.db")
            .addCallback(AppDatabase.StartingCategories)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val dao = db.dao()

        // Room checks the upgraded tables match what the app expects, and fails here if they don't.
        val receipt = dao.receiptNow(7)
        assertNotNull(receipt)
        assertEquals("Kroger", receipt!!.store)
        assertEquals(693L, receipt.totalCents)
        assertEquals(listOf("a.jpg"), receipt.photoList)
        assertEquals(listOf("Milk"), dao.itemsNow(7).map { it.name })
        assertEquals(1, dao.receiptRows().first().single().itemCount)

        // Categories and budgets stay as they were, without the starting set added again.
        val categories = dao.categoriesNow()
        assertEquals(listOf("Groceries", "Gas & auto", "Other"), categories.map { it.name })
        assertEquals(50000L, categories.first().budgetCents)

        // Old receipts can become fill-ups, and new ones work.
        assertEquals(0, dao.fillUpsNow().size)
        dao.saveReceipt(receipt, dao.itemsNow(7), FillUp(receiptId = 0, gallonsMilli = 2_000))
        assertEquals(2_000L, dao.fillUpNow(7)?.gallonsMilli)
        dao.deleteReceipt(7)
        assertEquals(0, dao.fillUpsNow().size)
        db.close()
    }
}
