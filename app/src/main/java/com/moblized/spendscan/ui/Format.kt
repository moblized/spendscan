package com.moblized.spendscan.ui

import com.moblized.spendscan.data.FuelPeriod
import com.moblized.spendscan.data.FuelReset
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val FULL = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val SHORT = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy")
private val MONTH = DateTimeFormatter.ofPattern("MMMM")

fun LocalDate.full(): String = format(FULL)
fun LocalDate.short(): String = format(SHORT)
fun YearMonth.withYear(): String = format(MONTH_YEAR)
fun YearMonth.name(): String = format(MONTH)

private val DAY = DateTimeFormatter.ofPattern("d")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE")
private val WEEKDAY_DATE = DateTimeFormatter.ofPattern("EEE, MMM d")

fun LocalDate.weekday(): String = format(WEEKDAY)
fun LocalDate.withWeekday(): String = format(WEEKDAY_DATE)

/** "Nov 15 – 21", or "Nov 29 – Dec 5" when it runs into the next month. */
fun FuelPeriod.range(): String =
    if (first.month == last.month && first.year == last.year) "${first.short()} \u2013 ${last.format(DAY)}" else "${first.short()} \u2013 ${last.short()}"

/** What to call it at the top of the page: "November 2026", or "Nov 15 – 21", with the year when it isn't [thisYear]. */
fun FuelPeriod.title(thisYear: Int): String = when {
    reset == FuelReset.MONTH -> YearMonth.from(first).withYear()
    last.year == thisYear -> range()
    else -> "${range()}, ${last.year}"
}

/** "month", "week", "2 weeks". */
fun FuelReset.noun(): String = when (this) {
    FuelReset.MONTH -> "month"
    FuelReset.WEEK -> "week"
    FuelReset.TWO_WEEKS -> "2 weeks"
}

fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
