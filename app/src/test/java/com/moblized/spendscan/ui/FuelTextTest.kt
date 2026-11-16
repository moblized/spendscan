package com.moblized.spendscan.ui

import com.moblized.spendscan.data.FillUp
import com.moblized.spendscan.data.FillUpRow
import com.moblized.spendscan.data.FuelComparison
import com.moblized.spendscan.data.FuelCycle
import com.moblized.spendscan.data.FuelPeriod
import com.moblized.spendscan.data.FuelReset
import com.moblized.spendscan.data.FuelSummary
import com.moblized.spendscan.data.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The words on the Fuel page for each way it can start over. */
class FuelTextTest {
    private val sunday = LocalDate.of(2026, 11, 15)
    private val wednesday = LocalDate.of(2026, 11, 18)
    private val weekly = FuelCycle(FuelReset.WEEK, sunday.toEpochDay())
    private val twoWeekly = FuelCycle(FuelReset.TWO_WEEKS, sunday.toEpochDay())
    private val monthly = FuelCycle()
    private val november = FuelPeriod.month(YearMonth.of(2026, 11))

    private fun summary(period: FuelPeriod, today: LocalDate = wednesday, vararg fills: Pair<LocalDate, Long>) = FuelSummary.of(
        fills.mapIndexed { i, (date, cents) -> FillUpRow(FillUp(i + 1L, i + 1L, 10_000, null, null, true, null), "Shell", date.toEpochDay(), cents) },
        period,
        today,
    )

    @Test
    fun aStretchIsNamedByItsDates() {
        val week = weekly.periodOf(wednesday)
        assertEquals("Nov 15 – 21", week.range())
        assertEquals("Nov 15 – 28", twoWeekly.periodOf(wednesday).range())
        assertEquals("Nov 29 – Dec 5", weekly.periodOf(LocalDate.of(2026, 12, 1)).range())
        assertEquals("Nov 15 – 21", week.title(2026))
        assertEquals("Nov 15 – 21, 2026", week.title(2027))
        val overNewYear = weekly.periodOf(LocalDate.of(2027, 1, 1))
        assertEquals("Dec 27 – Jan 2", overNewYear.title(2027))
        assertEquals("Dec 27 – Jan 2, 2027", overNewYear.title(2026))
        assertEquals("November 2026", november.title(2026))
        assertEquals("November 2026", november.title(2027))
        assertEquals(listOf("month", "week", "2 weeks"), FuelReset.entries.map { it.noun() })
        assertEquals("Sunday", sunday.weekday())
        assertEquals("Sun, Nov 15", sunday.withWeekday())
    }

    @Test
    fun theTotalSaysWhatItCovers() {
        assertEquals("Fuel so far", fuelTotalsTitle(summary(november)))
        assertEquals("Fuel so far this week", fuelTotalsTitle(summary(weekly.periodOf(wednesday))))
        assertEquals("Fuel so far in these 2 weeks", fuelTotalsTitle(summary(twoWeekly.periodOf(wednesday))))
        assertEquals("Fuel in October", fuelTotalsTitle(summary(november.previous())))
        assertEquals("Fuel, Nov 8 – 14", fuelTotalsTitle(summary(weekly.periodOf(wednesday).previous())))
        assertEquals("Fuel, Nov 1 – 14", fuelTotalsTitle(summary(twoWeekly.periodOf(wednesday).previous())))
    }

    @Test
    fun anEmptyStretchSaysSo() {
        assertEquals("No fill-ups in November.", noFillUps(summary(november)))
        assertEquals("No fill-ups in October.", noFillUps(summary(november.previous())))
        assertEquals("No fill-ups yet this week.", noFillUps(summary(weekly.periodOf(wednesday))))
        assertEquals("No fill-ups yet in these 2 weeks.", noFillUps(summary(twoWeekly.periodOf(wednesday))))
        assertEquals("No fill-ups from Nov 8 to Nov 14.", noFillUps(summary(weekly.periodOf(wednesday).previous())))
        assertEquals("No fill-ups from Oct 18 to Oct 31.", noFillUps(summary(twoWeekly.periodOf(wednesday).back(2))))
    }

    @Test
    fun comparesWithTheStretchBefore() {
        val thirty = Money.format(3000)
        val week = weekly.periodOf(wednesday)
        assertEquals("$thirty more than this point last week", comparisonText(FuelComparison(week.previous(), 3000, soFar = true)))
        assertEquals("$thirty less than the week before", comparisonText(FuelComparison(week.back(2), -3000, soFar = false)))
        val two = twoWeekly.periodOf(wednesday)
        assertEquals("$thirty more than this point in the 2 weeks before", comparisonText(FuelComparison(two.previous(), 3000, soFar = true)))
        assertEquals("The same as the 2 weeks before", comparisonText(FuelComparison(two.back(2), 0, soFar = false)))
        assertEquals("$thirty less than this point in October", comparisonText(FuelComparison(november.previous(), -3000, soFar = true)))
        assertEquals("$thirty more than September", comparisonText(FuelComparison(november.back(2), 3000, soFar = false)))

        // Straight from fill-ups: $50 this week by Wednesday against $20 by last Wednesday.
        val s = summary(
            week, wednesday,
            LocalDate.of(2026, 11, 16) to 5000L, LocalDate.of(2026, 11, 10) to 2000L, LocalDate.of(2026, 11, 13) to 9000L,
        )
        assertEquals("$thirty more than this point last week", comparisonText(s.comparison!!))
    }

    @Test
    fun theBarsAndTheFootSayHowOftenItStartsOver() {
        assertEquals("Last 12 months", fuelHistoryHeading(FuelReset.MONTH))
        assertEquals("Last 12 weeks", fuelHistoryHeading(FuelReset.WEEK))
        assertEquals("Last 24 weeks, 2 to a bar", fuelHistoryHeading(FuelReset.TWO_WEEKS))
        assertEquals("This page starts over every month.", fuelResetLine(FuelReset.MONTH))
        assertEquals("This page starts over every week.", fuelResetLine(FuelReset.WEEK))
        assertEquals("This page starts over every 2 weeks.", fuelResetLine(FuelReset.TWO_WEEKS))
    }

    @Test
    fun settingsSayWhenItStartsOver() {
        assertEquals("Your fuel totals start again on the 1st of each month.", fuelCycleNote(monthly, wednesday))
        assertEquals("Your fuel totals start again every Sunday. This week is Nov 15 – 21.", fuelCycleNote(weekly, wednesday))
        assertEquals(
            "Your fuel totals start again every other Sunday. These 2 weeks are Nov 15 – 28.",
            fuelCycleNote(twoWeekly, wednesday),
        )
        // Started on a Friday, some weeks ago.
        val friday = LocalDate.of(2026, 10, 2)
        assertEquals(
            "Your fuel totals start again every Friday. This week is Nov 13 – 19.",
            fuelCycleNote(FuelCycle(FuelReset.WEEK, friday.toEpochDay()), wednesday),
        )
        assertEquals(
            "Your fuel totals start again every other Friday. These 2 weeks are Nov 13 – 26.",
            fuelCycleNote(FuelCycle(FuelReset.TWO_WEEKS, friday.toEpochDay()), wednesday),
        )
    }
}
