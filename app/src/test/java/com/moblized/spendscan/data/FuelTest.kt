package com.moblized.spendscan.data

import com.moblized.spendscan.export.Csv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class FuelTest {
    private val today = LocalDate.of(2026, 11, 15)
    private var nextId = 1L

    private fun fill(
        date: LocalDate,
        cents: Long,
        gallons: Long? = 20_000,
        odometer: Int? = null,
        full: Boolean = true,
        store: String = "Shell",
        price: Long? = null,
    ): FillUpRow {
        val id = nextId++
        return FillUpRow(FillUp(id, id, gallons, price, odometer, full), store, date.toEpochDay(), cents)
    }

    private val nov = YearMonth.of(2026, 11)
    private fun d(day: Int) = nov.atDay(day)

    @Test
    fun unitsReadAndPrint() {
        assertEquals(12345L, FuelUnits.parseMilli("12.345"))
        assertEquals(12300L, FuelUnits.parseMilli("12.3"))
        assertEquals(12000L, FuelUnits.parseMilli("12"))
        assertEquals(3459L, FuelUnits.parseMilli("$3.459"))
        assertEquals(500L, FuelUnits.parseMilli(".5"))
        assertNull(FuelUnits.parseMilli(""))
        assertNull(FuelUnits.parseMilli("abc"))
        assertNull(FuelUnits.parseMilli("1.2345"))
        assertEquals("12.345", FuelUnits.plainMilli(12345))
        assertEquals("0.050", FuelUnits.plainMilli(50))
        assertEquals("12.3", FuelUnits.gallons(12300))
        assertEquals("22", FuelUnits.gallons(22000))
        assertEquals("$3.459", FuelUnits.pricePerGallon(3459, Locale.US))
        assertEquals("$3.500", FuelUnits.pricePerGallon(3500, Locale.US))
        assertEquals(52310, FuelUnits.parseOdometer("52,310"))
        assertNull(FuelUnits.parseOdometer(""))
        assertNull(FuelUnits.parseOdometer("-5"))
        assertEquals("52,310", FuelUnits.odometer(52310, Locale.US))
        // 12.345 gallons at $3.459 is $42.70.
        assertEquals(4270L, FuelUnits.cost(12345, 3459))
        assertEquals(3459L, FuelUnits.paidPerGallon(4270, 12345))
        assertNull(FuelUnits.paidPerGallon(4270, 0))
    }

    @Test
    fun aMonthAddsUp() {
        val rows = listOf(
            fill(d(2), 7000, gallons = 20_000),
            fill(d(9), 3500, gallons = 10_000),
            fill(d(9), 1000, gallons = null),
            fill(LocalDate.of(2026, 10, 3), 9000),
            fill(LocalDate.of(2025, 12, 3), 4000),
        )
        val s = FuelSummary.of(rows, nov, today)
        assertEquals(11500L, s.spentCents)
        assertEquals(30_000L, s.gallonsMilli)
        assertEquals(3, s.count)
        // Average price only counts fill-ups with gallons: $105 over 30 gallons.
        assertEquals(3500L, s.averagePriceMilli)
        assertEquals(20500L, s.yearCents)
        assertEquals(5, s.allFillUps)
        assertTrue(s.isCurrent)
    }

    @Test
    fun comparesWithTheSameStretchOfLastMonth() {
        val rows = listOf(
            fill(d(3), 5000),
            fill(LocalDate.of(2026, 10, 10), 2000),
            fill(LocalDate.of(2026, 10, 28), 9000),
        )
        val s = FuelSummary.of(rows, nov, today)
        assertEquals(3000L, s.comparison?.differenceCents)
        assertEquals(true, s.comparison?.soFar)
        val october = FuelSummary.of(rows, YearMonth.of(2026, 10), today)
        assertNull(october.comparison)
    }

    @Test
    fun barsCoverTwelveMonthsEndingNow() {
        val rows = listOf(fill(d(3), 5000), fill(LocalDate.of(2026, 1, 5), 100), fill(LocalDate.of(2025, 11, 5), 200), fill(LocalDate.of(2025, 10, 5), 999))
        val s = FuelSummary.of(rows, nov, today)
        assertEquals(12, s.bars.size)
        assertEquals(YearMonth.of(2025, 12), s.bars.first().month)
        assertEquals(nov, s.bars.last().month)
        assertEquals(5000L, s.bars.last().cents)
        assertEquals(100L, s.bars[1].cents)
        assertEquals(5100L, s.bars.sumOf { it.cents })
    }

    @Test
    fun fillUpsAreListedNewestFirst() {
        val first = fill(d(2), 1000)
        val second = fill(d(9), 2000)
        val s = FuelSummary.of(listOf(first, second), nov, today)
        assertEquals(listOf(second.fill.id, first.fill.id), s.lines.map { it.row.fill.id })
    }

    @Test
    fun mileageRunsFromFullTankToFullTank() {
        val a = fill(d(1), 7000, gallons = 20_000, odometer = 50_000)
        val b = fill(d(8), 7500, gallons = 25_000, odometer = 50_400)
        val c = fill(d(15), 6000, gallons = 20_000, odometer = 50_700)
        val m = FuelSummary.mileage(listOf(c, a, b))
        // The first tank is the starting point: its fuel was burned before anyone measured.
        assertNull(m.perFill[a.fill.id])
        assertEquals(16.0, m.perFill[b.fill.id]!!, 0.001)
        assertEquals(15.0, m.perFill[c.fill.id]!!, 0.001)
        assertEquals(15.0, m.last!!, 0.001)
        assertEquals(700 / 45.0, m.average!!, 0.001)
        // $135 over 700 miles.
        assertEquals(13500 / 700.0, m.centsPerMile!!, 0.001)
    }

    @Test
    fun partialFillsCountTowardTheNextFullTank() {
        val a = fill(d(1), 7000, gallons = 20_000, odometer = 50_000)
        val b = fill(d(4), 2000, gallons = 5_000, odometer = 50_100, full = false)
        val c = fill(d(8), 5000, gallons = 15_000, odometer = 50_300)
        val m = FuelSummary.mileage(listOf(a, b, c))
        assertNull(m.perFill[b.fill.id])
        assertEquals(15.0, m.perFill[c.fill.id]!!, 0.001)
        assertEquals(7000 / 300.0, m.centsPerMile!!, 0.001)
    }

    @Test
    fun aGapInTheRecordsStartsMileageOver() {
        // A full tank with no odometer.
        val a = fill(d(1), 7000, odometer = 50_000)
        val b = fill(d(2), 7000)
        val c = fill(d(3), 7000, odometer = 50_400)
        val d = fill(d(4), 7000, odometer = 50_700)
        var m = FuelSummary.mileage(listOf(a, b, c, d))
        assertNull(m.perFill[c.fill.id])
        assertEquals(15.0, m.perFill[d.fill.id]!!, 0.001)

        // A fill-up with no gallons.
        val e = fill(d(1), 7000, odometer = 60_000)
        val f = fill(d(2), 7000, gallons = null, odometer = 60_200)
        val g = fill(d(3), 7000, odometer = 60_500)
        m = FuelSummary.mileage(listOf(e, f, g))
        assertTrue(m.perFill.isEmpty())
        assertNull(m.average)
    }

    @Test
    fun typosInTheOdometerDontSkewMileage() {
        // Went backwards.
        val a = fill(d(1), 7000, odometer = 50_000)
        val b = fill(d(2), 7000, odometer = 49_000)
        // An extra digit makes 3,000 miles on 20 gallons: not believable.
        val c = fill(d(3), 7000, odometer = 52_000)
        val m = FuelSummary.mileage(listOf(a, b, c))
        assertTrue(m.perFill.isEmpty())
        assertNull(m.average)
        assertNull(m.centsPerMile)
    }

    @Test
    fun aFillUpDraftCanWorkOutItsTotal() {
        val d = Draft(date = today, store = "Shell", categoryId = 1, isFillUp = true, gallonsText = "12.345", priceText = "3.459")
        assertEquals(4270L, d.fuelCostCents)
        assertEquals(4270L, d.totalCents)
        assertTrue(d.canSave)
        assertEquals(4270L, d.toReceipt(1).totalCents)
        // A typed total wins, because fuel rewards can make it lower than gallons times price.
        assertEquals(4000L, d.copy(totalText = "40.00").totalCents)
        // Only for fill-ups.
        assertNull(d.copy(isFillUp = false).totalCents)
        assertFalse(d.copy(isFillUp = false).canSave)
    }

    @Test
    fun aFillUpDraftSavesItsDetails() {
        val d = Draft(
            date = today, store = "Shell", categoryId = 1, totalText = "42.70", isFillUp = true,
            gallonsText = "12.345", priceText = "3.459", odometerText = "52,310", fullTank = false,
        )
        val f = d.toFillUp()!!
        assertEquals(12345L, f.gallonsMilli)
        assertEquals(3459L, f.priceMilli)
        assertEquals(52310, f.odometer)
        assertFalse(f.fullTank)
        assertNull(d.copy(isFillUp = false).toFillUp())
        // Round trip through saving.
        val back = Draft.fromSaved(d.toReceipt(1).copy(id = 5), emptyList(), f, 52000)
        assertTrue(back.isFillUp)
        assertEquals("12.345", back.gallonsText)
        assertEquals("52310", back.odometerText)
        assertFalse(back.fullTank)
        assertEquals(52000, back.lastOdometer)
        assertFalse(Draft.fromSaved(d.toReceipt(1), emptyList()).isFillUp)
    }

    @Test
    fun fuelCsv() {
        val rows = listOf(fill(LocalDate.of(2026, 11, 2), 4270, gallons = 12345, odometer = 52310, price = 3459, store = "Joe's, Gas"))
        val csv = Csv.fuel(rows).trim().split("\r\n")
        assertEquals("Date,Station,Gallons,Price per gallon,Total,Odometer,Full tank", csv[0])
        assertEquals("2026-11-02,\"Joe's, Gas\",12.345,3.459,42.70,52310,yes", csv[1])
    }
}
