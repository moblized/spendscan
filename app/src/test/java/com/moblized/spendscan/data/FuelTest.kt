package com.moblized.spendscan.data

import com.moblized.spendscan.export.Csv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
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
        type: FuelType? = null,
    ): FillUpRow {
        val id = nextId++
        return FillUpRow(FillUp(id, id, gallons, price, odometer, full, type?.name), store, date.toEpochDay(), cents)
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
        assertEquals(FuelPeriod.month(YearMonth.of(2025, 12)), s.bars.first().period)
        assertEquals(FuelPeriod.month(nov), s.bars.last().period)
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
            gallonsText = "12.345", priceText = "3.459", odometerText = "52,310", fullTank = false, fuelType = FuelType.DIESEL,
        )
        val f = d.toFillUp()!!
        assertEquals(FuelType.DIESEL, f.type)
        assertEquals(12345L, f.gallonsMilli)
        assertEquals(3459L, f.priceMilli)
        assertEquals(52310, f.odometer)
        assertFalse(f.fullTank)
        assertNull(d.copy(isFillUp = false).toFillUp())
        // Round trip through saving.
        val back = Draft.fromSaved(d.toReceipt(1).copy(id = 5), emptyList(), f, 52000)
        assertTrue(back.isFillUp)
        assertEquals(FuelType.DIESEL, back.fuelType)
        assertEquals("12.345", back.gallonsText)
        assertEquals("52310", back.odometerText)
        assertFalse(back.fullTank)
        assertEquals(52000, back.lastOdometer)
        assertFalse(Draft.fromSaved(d.toReceipt(1), emptyList()).isFillUp)
    }

    @Test
    fun fuelCsv() {
        val rows = listOf(
            fill(LocalDate.of(2026, 11, 2), 4270, gallons = 12345, odometer = 52310, price = 3459, store = "Joe's, Gas", type = FuelType.DIESEL),
            fill(LocalDate.of(2026, 11, 9), 3000, gallons = null),
        )
        val csv = Csv.fuel(rows).trim().split("\r\n")
        assertEquals("Date,Station,Fuel type,Gallons,Price per gallon,Total,Odometer,Full tank", csv[0])
        assertEquals("2026-11-02,\"Joe's, Gas\",Diesel,12.345,3.459,42.70,52310,yes", csv[1])
        assertEquals("2026-11-09,Shell,,,,30.00,,yes", csv[2])
    }

    @Test
    fun readsWhichFuelWasBought() {
        assertEquals(FuelType.DIESEL, FuelType.read(listOf("PILOT #1234", "PUMP 07 DIESEL", "22.415 GAL @ 3.899")))
        assertEquals(FuelType.DIESEL, FuelType.read(listOf("AUTO DSL", "DEF")))
        assertEquals(FuelType.DIESEL, FuelType.read(listOf("ULSD #2 12.000 G")))
        assertEquals(FuelType.PREMIUM, FuelType.read(listOf("PREMIUM UNLEADED", "11.200 GAL")))
        assertEquals(FuelType.PREMIUM, FuelType.read(listOf("PUMP 3", "UNLEADED", "PREM 93")))
        assertEquals(FuelType.PREMIUM, FuelType.read(listOf("V-Power NiTRO+ 10.1 gal")))
        assertEquals(FuelType.MIDGRADE, FuelType.read(listOf("UNL PLUS 8.902 G")))
        assertEquals(FuelType.MIDGRADE, FuelType.read(listOf("MIDGRADE")))
        assertEquals(FuelType.REGULAR, FuelType.read(listOf("REGULAR 87")))
        assertEquals(FuelType.REGULAR, FuelType.read(listOf("PUMP 05 UNLEADED 12.345 GAL")))
        assertEquals(FuelType.E85, FuelType.read(listOf("E-85 FLEX")))
        // The language model's answer goes through the same reader.
        FuelType.entries.forEach { assertEquals(it, FuelType.read(listOf(it.word))) }
    }

    @Test
    fun aPumpWithEveryGradeOnItSaysNothing() {
        assertNull(FuelType.read(listOf("87 UNLEADED", "89 PLUS", "93 PREMIUM", "SALE 45.20")))
        assertNull(FuelType.read(listOf("UNLEADED 87", "PLUS 89", "SUPER 93")))
        assertNull(FuelType.read(listOf("SUPER AMERICA", "COFFEE 1.99")))
        assertNull(FuelType.read(listOf("TOTAL 42.70")))
    }

    @Test
    fun aMonthSplitsByFuel() {
        val rows = listOf(
            fill(d(2), 9000, gallons = 25_000, type = FuelType.DIESEL),
            fill(d(9), 8000, gallons = 20_000, type = FuelType.DIESEL),
            fill(d(12), 4000, gallons = 10_000, type = FuelType.PREMIUM),
            fill(d(14), 3000, gallons = 10_000),
        )
        val s = FuelSummary.of(rows, nov, today)
        assertEquals(
            listOf(
                FuelTypeLine(FuelType.DIESEL, 17000, 45_000, 3778, 2),
                FuelTypeLine(FuelType.PREMIUM, 4000, 10_000, 4000, 1),
            ),
            s.byType,
        )
    }

    @Test
    fun aFillUpStartsWithLastTimesFuel() {
        val blank = Draft.blank(today, 1).copy(lastFuelType = FuelType.DIESEL)
        assertEquals(FuelType.DIESEL, blank.asFillUp().fuelType)
        assertEquals(FuelType.REGULAR, blank.copy(fuelType = FuelType.REGULAR).asFillUp().fuelType)

        val read = com.moblized.spendscan.parse.ReceiptParser.parse(
            listOf("SHELL", "PUMP 4 PREMIUM", "10.000 GAL @ 4.000", "TOTAL 40.00"),
            today,
        )
        assertEquals(FuelType.PREMIUM, read.fuel?.type)
        assertEquals(FuelType.PREMIUM, Draft.fromParsed(read, emptyList(), today, 1, lastFuelType = FuelType.DIESEL).fuelType)
        val unnamed = read.copy(fuel = read.fuel!!.copy(type = null))
        assertEquals(FuelType.DIESEL, Draft.fromParsed(unnamed, emptyList(), today, 1, lastFuelType = FuelType.DIESEL).fuelType)
        // A receipt that isn't for fuel gets none.
        val groceries = com.moblized.spendscan.parse.ReceiptParser.parse(listOf("KROGER", "MILK 3.49", "TOTAL 3.49"), today)
        assertNull(Draft.fromParsed(groceries, emptyList(), today, 1, lastFuelType = FuelType.DIESEL).fuelType)
    }

    // Starting over every week or two.

    private fun day(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)
    private val wednesday = day(11, 18)
    private val fromSunday = FuelCycle(FuelReset.WEEK, day(11, 15).toEpochDay())
    private val twoFromSunday = FuelCycle(FuelReset.TWO_WEEKS, day(11, 8).toEpochDay())

    /** Two weeks of fill-ups and one from the week before those. */
    private fun threeWeeks() = listOf(
        fill(day(11, 3), 1000),
        fill(day(11, 9), 2000),
        fill(day(11, 11), 1500),
        fill(day(11, 12), 4000),
        fill(day(11, 14), 9999),
        fill(day(11, 15), 5000),
        fill(day(11, 18), 3000),
    )

    @Test
    fun byDefaultTheFuelPageGoesByTheMonth() {
        assertEquals(FuelPeriod.month(nov), FuelCycle().periodOf(today))
        assertEquals(FuelPeriod.month(YearMonth.of(2025, 12)), FuelPeriod.month(YearMonth.of(2026, 1)).previous())
        assertEquals(FuelPeriod.month(YearMonth.of(2026, 2)), FuelPeriod.month(YearMonth.of(2026, 1)).next())
        assertEquals(day(2, 28), FuelPeriod.month(YearMonth.of(2026, 3)).previous().last)
    }

    @Test
    fun weeksAreCountedFromTheStartDay() {
        // From a Friday, like a payday.
        val cycle = FuelCycle(FuelReset.WEEK, day(11, 13).toEpochDay())
        assertEquals(DayOfWeek.FRIDAY, cycle.start.dayOfWeek)
        val week = cycle.periodOf(day(11, 15))
        assertEquals(FuelPeriod(FuelReset.WEEK, day(11, 13), day(11, 19)), week)
        assertEquals(week, cycle.periodOf(day(11, 13)))
        assertEquals(week, cycle.periodOf(day(11, 19)))
        assertEquals(week.next(), cycle.periodOf(day(11, 20)))
        // Days before the start day count back from it the same way.
        assertEquals(week.previous(), cycle.periodOf(day(11, 12)))
        assertEquals(day(11, 6), week.previous().first)
        assertEquals(FuelPeriod(FuelReset.WEEK, day(12, 26, 2025), day(1, 1)), cycle.periodOf(day(1, 1)))
        assertTrue(day(11, 19) in week)
        assertFalse(day(11, 20) in week)
        assertFalse(day(11, 12) in week)
    }

    @Test
    fun twoWeeksAreCountedFromTheStartDay() {
        val cycle = FuelCycle(FuelReset.TWO_WEEKS, day(11, 13).toEpochDay())
        val now = cycle.periodOf(day(11, 20))
        assertEquals(FuelPeriod(FuelReset.TWO_WEEKS, day(11, 13), day(11, 26)), now)
        assertEquals(now, cycle.periodOf(day(11, 26)))
        assertEquals(day(11, 27), cycle.periodOf(day(11, 27)).first)
        assertEquals(FuelPeriod(FuelReset.TWO_WEEKS, day(10, 30), day(11, 12)), cycle.periodOf(day(11, 12)))
        assertEquals(now.previous(), cycle.periodOf(day(11, 12)))
        assertEquals(FuelPeriod(FuelReset.TWO_WEEKS, day(11, 27), day(12, 10)), now.next())
        // The week picked decides which pairs of weeks go together.
        val otherWeek = FuelCycle(FuelReset.TWO_WEEKS, day(11, 6).toEpochDay())
        assertEquals(day(11, 6), otherWeek.periodOf(day(11, 15)).first)
        assertEquals(day(11, 13), cycle.periodOf(day(11, 15)).first)
    }

    @Test
    fun aFreshStartCountsFromTheStartOfThisWeek() {
        assertEquals(day(11, 15), FuelCycle.startingThisWeek(FuelReset.WEEK, wednesday, DayOfWeek.SUNDAY).start)
        assertEquals(day(11, 16), FuelCycle.startingThisWeek(FuelReset.WEEK, wednesday, DayOfWeek.MONDAY).start)
        assertEquals(day(11, 15), FuelCycle.startingThisWeek(FuelReset.TWO_WEEKS, day(11, 15), DayOfWeek.SUNDAY).start)
    }

    @Test
    fun aWeekAddsUp() {
        val rows = threeWeeks()
        val week = fromSunday.periodOf(wednesday)
        val s = FuelSummary.of(rows, week, wednesday)
        assertEquals(FuelPeriod(FuelReset.WEEK, day(11, 15), day(11, 21)), s.period)
        assertTrue(s.isCurrent)
        assertEquals(8000L, s.spentCents)
        assertEquals(2, s.count)
        assertEquals(40_000L, s.gallonsMilli)
        assertEquals(26499L, s.yearCents)
        assertEquals(2026, s.year)
        assertEquals(7, s.allFillUps)
        // Last week only up to its Wednesday: $20 and $15.
        assertEquals(FuelComparison(week.previous(), 4500, soFar = true), s.comparison)
    }

    @Test
    fun anEarlierWeekIsComparedInFull() {
        val rows = threeWeeks()
        val lastWeek = fromSunday.periodOf(wednesday).previous()
        val s = FuelSummary.of(rows, lastWeek, wednesday)
        assertFalse(s.isCurrent)
        assertEquals(17499L, s.spentCents)
        assertEquals(4, s.count)
        assertEquals(FuelComparison(lastWeek.previous(), 16499, soFar = false), s.comparison)
        // Nothing the week before that one, so nothing to compare with.
        assertNull(FuelSummary.of(rows, lastWeek.previous(), wednesday).comparison)
    }

    @Test
    fun barsCoverTwelveWeeksEndingNow() {
        val rows = threeWeeks() + fill(day(8, 30), 700) + fill(day(8, 29), 999)
        val week = fromSunday.periodOf(wednesday)
        val s = FuelSummary.of(rows, week, wednesday)
        assertEquals(12, s.bars.size)
        assertEquals(week, s.bars.last().period)
        assertEquals(day(8, 30), s.bars.first().period.first)
        assertEquals(listOf(700L, 1000L, 17499L, 8000L), s.bars.map { it.cents }.filter { it > 0 })
        // Every day lands in exactly one bar.
        assertEquals(rows.sumOf { it.totalCents } - 999, s.bars.sumOf { it.cents })
        // Looking back at an earlier week keeps the same bars.
        assertEquals(s.bars, FuelSummary.of(rows, week.back(5), wednesday).bars)
    }

    @Test
    fun twoWeeksAddUp() {
        val rows = threeWeeks()
        val now = twoFromSunday.periodOf(wednesday)
        val s = FuelSummary.of(rows, now, wednesday)
        assertEquals(FuelPeriod(FuelReset.TWO_WEEKS, day(11, 8), day(11, 21)), s.period)
        assertEquals(25499L, s.spentCents)
        assertEquals(6, s.count)
        // Ten days in. Ten days into the two weeks before was November 4.
        assertEquals(FuelComparison(now.previous(), 24499, soFar = true), s.comparison)
        assertEquals(12, s.bars.size)
        assertEquals(day(6, 7), s.bars.first().period.first)
        assertEquals(now, s.bars.last().period)
        assertEquals(listOf(1000L, 25499L), s.bars.takeLast(2).map { it.cents })
    }

    @Test
    fun aWeekOverNewYearCountsTowardTheYearItEndsIn() {
        val rows = listOf(fill(day(12, 28), 4000), fill(day(1, 1, 2027), 3000), fill(day(6, 1), 500))
        val week = fromSunday.periodOf(day(1, 2, 2027))
        assertEquals(FuelPeriod(FuelReset.WEEK, day(12, 27), day(1, 2, 2027)), week)
        // While it's going, and looking back at it later.
        for (now in listOf(day(1, 2, 2027), day(1, 10, 2027))) {
            val s = FuelSummary.of(rows, week, now)
            assertEquals(7000L, s.spentCents)
            assertEquals(2027, s.year)
            assertEquals(3000L, s.yearCents)
        }
        // Still in December, the year is the one today is in.
        val december = FuelSummary.of(rows, week, day(12, 29))
        assertEquals(2026, december.year)
        assertEquals(4500L, december.yearCents)
    }

    @Test
    fun aWeekSplitsByFuel() {
        val rows = listOf(
            fill(day(11, 15), 9000, gallons = 25_000, type = FuelType.DIESEL),
            fill(day(11, 17), 4000, gallons = 10_000, type = FuelType.PREMIUM),
            fill(day(11, 14), 8000, gallons = 20_000, type = FuelType.DIESEL),
        )
        val s = FuelSummary.of(rows, fromSunday.periodOf(wednesday), wednesday)
        assertEquals(listOf(FuelType.DIESEL, FuelType.PREMIUM), s.byType.map { it.type })
        assertEquals(9000L, s.byType.first().spentCents)
        // Mileage still runs across every fill-up, not just this week's.
        assertEquals(listOf(day(11, 17), day(11, 15)), s.lines.map { it.row.date })
    }
}
