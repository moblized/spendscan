package com.moblized.spendscan.data

import com.moblized.spendscan.export.Csv
import com.moblized.spendscan.parse.ReceiptField
import com.moblized.spendscan.parse.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class LogicTest {
    @Test
    fun moneyFormats() {
        assertEquals("$1,284.16", Money.format(128416, Locale.US))
        assertEquals("$0.05", Money.format(5, Locale.US))
        assertEquals("$500", Money.short(50000, Locale.US))
        assertEquals("$500.50", Money.short(50050, Locale.US))
        assertEquals("12.05", Money.plain(1205))
        assertEquals("-0.50", Money.plain(-50))
    }

    @Test
    fun moneyParses() {
        assertEquals(1200L, Money.parse("12"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(1250L, Money.parse("12.50"))
        assertEquals(123450L, Money.parse("$1,234.50"))
        assertEquals(99L, Money.parse(".99"))
        assertEquals(1250L, Money.parse("12,50"))
        assertEquals(0L, Money.parse("0"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("."))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("-5"))
    }

    private val today = LocalDate.of(2026, 11, 15)
    private val groceries = Category(1, "Groceries", budgetCents = 50000, position = 0)
    private val dining = Category(2, "Dining out", position = 1)
    private val gas = Category(3, "Gas & auto", budgetCents = 10000, position = 2)
    private val other = Category(4, "Other", position = 3, fixed = true)
    private val cats = listOf(groceries, dining, gas, other)

    private fun r(store: String, date: LocalDate, cents: Long, category: Long) =
        Receipt(store = store, dateEpochDay = date.toEpochDay(), totalCents = cents, categoryId = category)

    @Test
    fun monthAddsUpByCategoryStoreAndDay() {
        val nov = YearMonth.of(2026, 11)
        val s = MonthSummary.of(
            nov,
            listOf(
                r("Kroger", nov.atDay(2), 4000, 1),
                r("KROGER ", nov.atDay(9), 6000, 1),
                r("Shell", nov.atDay(9), 3000, 3),
                r("Taqueria", nov.atDay(10), 2000, 2),
            ),
            emptyList(),
            cats,
            null,
            today,
        )
        assertEquals(15000L, s.totalCents)
        assertEquals(4, s.receiptCount)
        assertEquals(listOf("Groceries", "Gas & auto", "Dining out"), s.categories.map { it.category.name })
        assertEquals(10000L, s.categories[0].spentCents)
        // "KROGER " and "Kroger" are one store.
        assertEquals(10000L, s.stores[0].spentCents)
        assertEquals(2, s.stores[0].visits)
        assertEquals(30, s.days.size)
        assertEquals(9000L, s.days[8].cents)
        assertEquals(9, s.biggestDay?.day)
        assertFalse(s.days[14].future)
        assertTrue(s.days[15].future)
        // With no overall budget, the category budgets add up.
        assertEquals(60000L, s.budgetCents)
    }

    @Test
    fun overallBudgetWins() {
        val nov = YearMonth.of(2026, 11)
        val s = MonthSummary.of(nov, emptyList(), emptyList(), cats, 120000, today)
        assertEquals(120000L, s.budgetCents)
        // Budgeted categories show even with nothing spent; others stay out of the way.
        assertEquals(setOf("Groceries", "Gas & auto"), s.categories.map { it.category.name }.toSet())
    }

    @Test
    fun comparisonUsesTheSameStretchOfLastMonth() {
        val nov = YearMonth.of(2026, 11)
        val oct = nov.minusMonths(1)
        val s = MonthSummary.of(
            nov,
            listOf(r("A", nov.atDay(3), 5000, 1)),
            listOf(r("B", oct.atDay(10), 2000, 1), r("C", oct.atDay(28), 9000, 1)),
            cats,
            null,
            today,
        )
        // Only October 1-15 counts: today is the 15th.
        assertEquals(3000L, s.comparison?.differenceCents)
        assertEquals(true, s.comparison?.soFar)

        val past = MonthSummary.of(oct, listOf(r("B", oct.atDay(10), 2000, 1)), listOf(r("D", oct.minusMonths(1).atDay(29), 2500, 1)), cats, null, today)
        assertEquals(-500L, past.comparison?.differenceCents)
        assertEquals(false, past.comparison?.soFar)
        assertNull(MonthSummary.of(nov, emptyList(), emptyList(), cats, null, today).comparison)
    }

    @Test
    fun filters() {
        val row = ReceiptRow(r("Kroger", LocalDate.of(2026, 11, 2), 4000, 1).copy(id = 7, note = "party food"), 3)
        assertTrue(ReceiptFilter().matches(row, emptySet()))
        assertTrue(ReceiptFilter(query = "krog").matches(row, emptySet()))
        assertTrue(ReceiptFilter(query = "PARTY").matches(row, emptySet()))
        assertFalse(ReceiptFilter(query = "milk").matches(row, emptySet()))
        assertTrue(ReceiptFilter(query = "milk").matches(row, setOf(7L)))
        assertTrue(ReceiptFilter(categoryId = 1).matches(row, emptySet()))
        assertFalse(ReceiptFilter(categoryId = 2).matches(row, emptySet()))
        assertTrue(ReceiptFilter(month = YearMonth.of(2026, 11)).matches(row, emptySet()))
        assertFalse(ReceiptFilter(month = YearMonth.of(2026, 10)).matches(row, emptySet()))
        assertTrue(ReceiptFilter(store = "kroger").matches(row, emptySet()))
        assertFalse(ReceiptFilter(day = LocalDate.of(2026, 11, 3)).matches(row, emptySet()))
    }

    @Test
    fun budgetNotesOnlyWhenALineIsCrossed() {
        assertNull(budgetNote("Groceries", null, 0, 9000))
        assertNull(budgetNote("Groceries", 10000, 1000, 5000))
        assertEquals("Groceries is at 85% of its budget.", budgetNote("Groceries", 10000, 7000, 8500))
        assertNull(budgetNote("Groceries", 10000, 8500, 9000))
        assertTrue(budgetNote("Groceries", 10000, 9000, 11240)!!.startsWith("Groceries is over budget by"))
        assertNull(budgetNote("Groceries", 10000, 11000, 12000))
        // Editing a receipt downward says nothing.
        assertNull(budgetNote("Groceries", 10000, 12000, 8000))
    }

    @Test
    fun draftFromAReceiptAddsUp() {
        val parsed = ReceiptParser.parse(
            listOf("KROGER", "Milk 3.49", "Bread 2.99", "Subtotal 6.48", "Tax 0.45", "Total 6.93", "VISA 6.93", "11/02/2026"),
            today,
        )
        val d = Draft.fromParsed(parsed, listOf("a.jpg"), today, 1)
        assertEquals("Kroger", d.store)
        assertEquals("6.93", d.totalText)
        assertEquals(LocalDate.of(2026, 11, 2), d.date)
        assertEquals(0L, d.itemsOffByCents)
        assertTrue(d.canSave)
        assertFalse(d.readFailed)
        val saved = d.toReceipt(100)
        assertEquals(693L, saved.totalCents)
        assertEquals(45L, saved.taxCents)
        assertEquals(listOf("a.jpg"), saved.photoList)
        assertEquals(100L, saved.createdAt)
        assertEquals(listOf("Milk", "Bread"), d.toItems().map { it.name })

        val back = Draft.fromSaved(saved.copy(id = 9), d.toItems())
        assertEquals(9L, back.receiptId)
        assertEquals(d.totalText, back.totalText)
        assertEquals(d.items.map { it.amountText }, back.items.map { it.amountText })
    }

    @Test
    fun draftNoticesItemsThatDontAddUp() {
        val d = Draft(
            date = today, store = "X", categoryId = 1, totalText = "10.00", taxText = "1.00",
            items = listOf(DraftItem(0, "A", amountText = "5.00"), DraftItem(1, "B", amountText = "2.00")),
        )
        assertEquals(-200L, d.itemsOffByCents)
        assertEquals(0L, d.copy(items = d.items + DraftItem(2, "C", amountText = "2.00")).itemsOffByCents)
        // A printed discount explains the gap.
        assertEquals(0L, d.copy(items = d.items + DraftItem(2, "C", amountText = "4.00"), discountText = "2.00").itemsOffByCents)
        assertEquals(0L, d.copy(items = emptyList()).itemsOffByCents)
    }

    @Test
    fun draftWhenNothingCouldBeRead() {
        val nothing = Draft.fromParsed(ReceiptParser.parse(emptyList(), today), listOf("a.jpg"), today, 4)
        assertTrue(nothing.readFailed)
        assertEquals(ReceiptField.entries.toSet(), nothing.unsure)
        assertFalse(nothing.canSave)
        assertEquals(today, nothing.date)
        assertEquals(listOf("a.jpg"), nothing.photos)
        assertTrue(Draft.fromParsed(null, emptyList(), today, 4).readFailed)
    }

    @Test
    fun draftNeedsStoreTotalAndCategory() {
        val ok = Draft(date = today, store = "X", totalText = "1", categoryId = 1)
        assertTrue(ok.canSave)
        assertFalse(ok.copy(store = " ").canSave)
        assertFalse(ok.copy(totalText = "abc").canSave)
        assertFalse(ok.copy(categoryId = null).canSave)
    }

    @Test
    fun itemsWithNoNameOrPriceAreDropped() {
        val d = Draft(
            date = today, store = "X", totalText = "1", categoryId = 1,
            items = listOf(DraftItem(0, "", amountText = "1.00"), DraftItem(1, "B", amountText = ""), DraftItem(2, "C", amountText = "2.00")),
        )
        assertEquals(listOf("C"), d.toItems().map { it.name })
    }

    @Test
    fun csvQuotesAndGuardsCells() {
        assertEquals("plain", Csv.cell("plain"))
        assertEquals("\"a, b\"", Csv.cell("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""))
        assertEquals("'=1+1", Csv.cell("=1+1"))
        assertEquals("", Csv.cell(null))
        val receipt = r("Joe's, Inc", LocalDate.of(2026, 11, 2), 1250, 1).copy(id = 3, taxCents = 50, payment = "Visa 4242")
        val csv = Csv.receipts(listOf(receipt), cats).trim().split("\r\n")
        assertEquals("Date,Store,Category,Total,Subtotal,Tax,Tip,Discount,Paid with,Note", csv[0])
        assertEquals("2026-11-02,\"Joe's, Inc\",Groceries,12.50,,0.50,,,Visa 4242,", csv[1])
        val items = Csv.items(
            listOf(receipt),
            listOf(ReceiptItem(receiptId = 3, name = "Milk", quantity = 2.0, amountCents = 700), ReceiptItem(receiptId = 99, name = "Gone", amountCents = 1)),
            cats,
        ).trim().split("\r\n")
        assertEquals(2, items.size)
        assertEquals("2026-11-02,\"Joe's, Inc\",Groceries,Milk,2,7.00", items[1])
    }

    @Test
    fun itemStats() {
        assertNull(ItemStats.of("milk", emptyList()))
        val s = ItemStats.of(
            "milk",
            listOf(ItemHit(1, "Milk", 1.0, 300, "A", 1), ItemHit(2, "Milk", 1.0, 400, "B", 2)),
        )!!
        assertEquals(2, s.times)
        assertEquals(700L, s.totalCents)
        assertEquals(350L, s.averageCents)
    }
}
