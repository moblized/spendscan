package com.moblized.spendscan.ui

import com.moblized.spendscan.data.FuelComparison
import com.moblized.spendscan.data.FuelCycle
import com.moblized.spendscan.data.FuelReset
import com.moblized.spendscan.data.FuelSummary
import com.moblized.spendscan.data.Money
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

// The words on the Fuel page that change with how often it starts over.

/** Above the big total: "Fuel so far this week", "Fuel in October", "Fuel, Nov 8 – 14". */
fun fuelTotalsTitle(s: FuelSummary): String = when {
    s.isCurrent -> when (s.period.reset) {
        FuelReset.MONTH -> "Fuel so far"
        FuelReset.WEEK -> "Fuel so far this week"
        FuelReset.TWO_WEEKS -> "Fuel so far in these 2 weeks"
    }
    s.period.reset == FuelReset.MONTH -> "Fuel in ${YearMonth.from(s.period.first).name()}"
    else -> "Fuel, ${s.period.range()}"
}

/** "No fill-ups in November.", "No fill-ups yet this week.", "No fill-ups from Nov 1 to Nov 14." */
fun noFillUps(s: FuelSummary): String = when {
    s.period.reset == FuelReset.MONTH -> "No fill-ups in ${YearMonth.from(s.period.first).name()}."
    !s.isCurrent -> "No fill-ups from ${s.period.first.short()} to ${s.period.last.short()}."
    s.period.reset == FuelReset.WEEK -> "No fill-ups yet this week."
    else -> "No fill-ups yet in these 2 weeks."
}

/** "$30.00 more than this point last week" */
fun comparisonText(cmp: FuelComparison): String {
    val other = when (cmp.other.reset) {
        FuelReset.MONTH -> YearMonth.from(cmp.other.first).name().let { if (cmp.soFar) "this point in $it" else it }
        FuelReset.WEEK -> if (cmp.soFar) "this point last week" else "the week before"
        FuelReset.TWO_WEEKS -> if (cmp.soFar) "this point in the 2 weeks before" else "the 2 weeks before"
    }
    val amount = Money.format(abs(cmp.differenceCents))
    return when {
        cmp.differenceCents > 0 -> "$amount more than $other"
        cmp.differenceCents < 0 -> "$amount less than $other"
        else -> "The same as $other"
    }
}

/** Above the bars. */
fun fuelHistoryHeading(reset: FuelReset): String = when (reset) {
    FuelReset.MONTH -> "Last 12 months"
    FuelReset.WEEK -> "Last 12 weeks"
    FuelReset.TWO_WEEKS -> "Last 24 weeks, 2 to a bar"
}

/** At the foot of the page, above the button that goes to the setting. */
fun fuelResetLine(reset: FuelReset): String = "This page starts over every ${reset.noun()}."

/** In Settings: "Your fuel totals start again every Sunday. This week is Nov 15 – 21." */
fun fuelCycleNote(cycle: FuelCycle, today: LocalDate): String {
    val now = cycle.periodOf(today)
    return when (cycle.reset) {
        FuelReset.MONTH -> "Your fuel totals start again on the 1st of each month."
        FuelReset.WEEK -> "Your fuel totals start again every ${now.first.weekday()}. This week is ${now.range()}."
        FuelReset.TWO_WEEKS -> "Your fuel totals start again every other ${now.first.weekday()}. These 2 weeks are ${now.range()}."
    }
}
