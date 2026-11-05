package com.moblized.spendscan.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SpendDao
    private val day = LocalDate.of(2026, 11, 2).toEpochDay()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .addCallback(AppDatabase.StartingCategories)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun close() = db.close()

    @Test
    fun startsWithTheDefaultCategories() = runTest {
        val cats = dao.categoriesNow()
        assertEquals(DEFAULT_CATEGORIES.size + 1, cats.size)
        assertEquals("Groceries", cats.first().name)
        assertEquals(FALLBACK_CATEGORY, cats.last().name)
        assertEquals(true, cats.last().fixed)
    }

    private fun receipt(store: String = "Kroger", total: Long = 693, category: Long = 1) =
        Receipt(store = store, dateEpochDay = day, totalCents = total, categoryId = category, createdAt = 1)

    private val milkAndBread = listOf(
        ReceiptItem(receiptId = 0, name = "2% Milk", amountCents = 349),
        ReceiptItem(receiptId = 0, name = "Bread", amountCents = 299),
    )

    @Test
    fun savesAndEditsAReceiptWithItems() = runTest {
        val id = dao.saveReceipt(receipt(), milkAndBread)
        assertEquals(listOf("2% Milk", "Bread"), dao.itemsNow(id).map { it.name })
        assertEquals(2, dao.receiptRows().first().single().itemCount)

        dao.saveReceipt(dao.receiptNow(id)!!.copy(totalCents = 500), listOf(ReceiptItem(receiptId = 0, name = "Eggs", amountCents = 500)))
        assertEquals(500L, dao.receiptNow(id)!!.totalCents)
        assertEquals(listOf("Eggs"), dao.itemsNow(id).map { it.name })
        assertEquals(1, dao.receiptRows().first().size)
    }

    @Test
    fun deletingAReceiptTakesItsItems() = runTest {
        val id = dao.saveReceipt(receipt(), milkAndBread)
        dao.deleteReceipt(id)
        assertNull(dao.receiptNow(id))
        assertEquals(0, dao.itemsNow(id).size)
    }

    @Test
    fun findsItemsAcrossReceipts() = runTest {
        dao.saveReceipt(receipt(), milkAndBread)
        dao.saveReceipt(receipt(store = "Aldi", total = 300), listOf(ReceiptItem(receiptId = 0, name = "MILK GAL", amountCents = 300)))
        val hits = dao.searchItems("milk").first()
        assertEquals(setOf("Kroger", "Aldi"), hits.map { it.store }.toSet())
        assertEquals(0, dao.searchItems("cheese").first().size)
    }

    @Test
    fun aFillUpIsSavedWithItsReceipt() = runTest {
        val fill = FillUp(receiptId = 0, gallonsMilli = 12_345, priceMilli = 3_459, odometer = 52_310)
        val id = dao.saveReceipt(receipt(store = "Shell", total = 4270), emptyList(), fill)
        val row = dao.fillUpsNow().single()
        assertEquals("Shell", row.store)
        assertEquals(4270L, row.totalCents)
        assertEquals(12_345L, row.fill.gallonsMilli)
        assertEquals(id, row.fill.receiptId)
        assertEquals(52_310, dao.lastOdometer())
        assertEquals(1, dao.fillUps().first().size)

        // Saving again replaces it rather than adding a second.
        dao.saveReceipt(dao.receiptNow(id)!!, emptyList(), fill.copy(odometer = 52_400))
        assertEquals(52_400, dao.fillUpNow(id)!!.odometer)
        assertEquals(1, dao.fillUpsNow().size)

        // Saving without one removes it, and leaves the receipt.
        dao.saveReceipt(dao.receiptNow(id)!!, emptyList())
        assertNull(dao.fillUpNow(id))
        assertEquals(1, dao.receiptRows().first().size)

        // Deleting the receipt takes its fill-up.
        dao.saveReceipt(dao.receiptNow(id)!!, emptyList(), fill)
        dao.deleteReceipt(id)
        assertEquals(0, dao.fillUpsNow().size)
        assertNull(dao.lastOdometer())
    }

    @Test
    fun lastOdometerIsFromTheLatestFillUpThatHasOne() = runTest {
        dao.saveReceipt(receipt().copy(dateEpochDay = day), emptyList(), FillUp(receiptId = 0, odometer = 100))
        dao.saveReceipt(receipt().copy(dateEpochDay = day + 5), emptyList(), FillUp(receiptId = 0, odometer = 500))
        dao.saveReceipt(receipt().copy(dateEpochDay = day + 2), emptyList(), FillUp(receiptId = 0, odometer = 300))
        dao.saveReceipt(receipt().copy(dateEpochDay = day + 9), emptyList(), FillUp(receiptId = 0))
        assertEquals(500, dao.lastOdometer())
    }

    @Test
    fun remembersCategoryAndSpotsDuplicates() = runTest {
        val id = dao.saveReceipt(receipt(category = 2), emptyList())
        assertEquals(2L, dao.lastCategoryFor("KROGER"))
        assertNull(dao.lastCategoryFor("Aldi"))
        assertEquals(1, dao.sameReceiptCount("kroger", day, 693, 0))
        assertEquals(0, dao.sameReceiptCount("kroger", day, 694, 0))
        // A receipt isn't a duplicate of itself while being edited.
        assertEquals(0, dao.sameReceiptCount("kroger", day, 693, id))
        assertEquals(693L, dao.categorySpent(2, day, day))
        assertEquals(0L, dao.categorySpent(2, day + 1, day + 2))
    }

    @Test
    fun deletingACategoryMovesItsReceipts() = runTest {
        val cats = dao.categoriesNow()
        val groceries = cats.first()
        val other = cats.last()
        val id = dao.saveReceipt(receipt(category = groceries.id), emptyList())
        dao.removeCategory(groceries, other.id)
        assertEquals(other.id, dao.receiptNow(id)!!.categoryId)
        assertEquals(DEFAULT_CATEGORIES.size, dao.categoriesNow().size)
    }
}
