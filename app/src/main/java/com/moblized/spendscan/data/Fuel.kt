package com.moblized.spendscan.data

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Gallons and prices per gallon go to three decimals on a pump, so they're kept in thousandths. */
object FuelUnits {
    /** "12.345", "12.3", "$3.459". Null when it isn't a number, or has more than three decimals. */
    fun parseMilli(text: String): Long? {
        val t = text.trim().trimStart('$').trim().replace(",", "")
        if (t.isEmpty() || !Regex("""^\d*\.?\d{0,3}$""").matches(t) || t == ".") return null
        val whole = t.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
        val part = t.substringAfter('.', "").padEnd(3, '0').toLong()
        return whole * 1000 + part
    }

    /** For editing: 12345 is "12.345". */
    fun plainMilli(milli: Long): String = "${milli / 1000}.${(milli % 1000).toString().padStart(3, '0')}"

    /** 12300 is "12.3", 22000 is "22". */
    fun gallons(milli: Long): String = plainMilli(milli).trimEnd('0').trimEnd('.')

    fun pricePerGallon(milli: Long, locale: Locale = Locale.getDefault()): String {
        val f = NumberFormat.getCurrencyInstance(locale)
        f.minimumFractionDigits = 3
        f.maximumFractionDigits = 3
        return f.format(milli / 1000.0)
    }

    /** "52,310" or "52310" from a person; anything else is null. */
    fun parseOdometer(text: String): Int? = text.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it >= 0 }

    fun odometer(miles: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(miles)

    /** The total for some gallons at a price, in cents. */
    fun cost(gallonsMilli: Long, priceMilli: Long): Long = (gallonsMilli * priceMilli + 5000) / 10_000

    /** What was really paid per gallon, in thousandths of a dollar. */
    fun paidPerGallon(cents: Long, gallonsMilli: Long): Long? =
        if (gallonsMilli <= 0) null else (cents * 10_000 + gallonsMilli / 2) / gallonsMilli

    fun mpg(value: Double): String = String.format(Locale.US, "%.1f", value)
}

data class FillUpLine(val row: FillUpRow, val mpg: Double?)

data class FuelMonthBar(val month: YearMonth, val cents: Long)

/** One month of fill-ups, with the mileage worked out across all of them. */
data class FuelSummary(
    val month: YearMonth,
    val isCurrent: Boolean,
    val spentCents: Long,
    val gallonsMilli: Long,
    val count: Int,
    val averagePriceMilli: Long?,
    val comparison: Comparison?,
    val yearCents: Long,
    val averageMpg: Double?,
    val lastMpg: Double?,
    val centsPerMile: Double?,
    val bars: List<FuelMonthBar>,
    val lines: List<FillUpLine>,
    val allFillUps: Int,
) {
    companion object {
        private const val MOST_BELIEVABLE_MPG = 100.0

        /**
         * Mileage only means something from one full tank to the next: the miles between them, over all the fuel
         * bought since the first. Partial fills count toward the fuel. Anything that breaks the chain, like a
         * full tank with no odometer or a fill-up with no gallons, starts it over.
         */
        fun mileage(rows: List<FillUpRow>): Mileage {
            val perFill = HashMap<Long, Double>()
            var lastFull: Int? = null
            var gallons = 0L
            var cents = 0L
            var totalMiles = 0L
            var totalGallons = 0L
            var totalCents = 0L
            var lastMpg: Double? = null
            for (row in rows.sortedWith(compareBy({ it.dateEpochDay }, { it.fill.odometer ?: Int.MAX_VALUE }, { it.fill.id }))) {
                val f = row.fill
                val filled = f.gallonsMilli
                if (filled == null) {
                    lastFull = null
                    gallons = 0
                    cents = 0
                    continue
                }
                gallons += filled
                cents += row.totalCents
                if (!f.fullTank) continue
                val odometer = f.odometer
                val start = lastFull
                if (odometer != null && start != null && odometer > start && gallons > 0) {
                    val miles = odometer - start
                    val mpg = miles * 1000.0 / gallons
                    if (mpg <= MOST_BELIEVABLE_MPG) {
                        perFill[f.id] = mpg
                        lastMpg = mpg
                        totalMiles += miles
                        totalGallons += gallons
                        totalCents += cents
                    }
                }
                lastFull = odometer
                gallons = 0
                cents = 0
            }
            return Mileage(
                perFill = perFill,
                average = if (totalGallons > 0) totalMiles * 1000.0 / totalGallons else null,
                last = lastMpg,
                centsPerMile = if (totalMiles > 0) totalCents.toDouble() / totalMiles else null,
            )
        }

        fun of(rows: List<FillUpRow>, month: YearMonth, today: LocalDate): FuelSummary {
            val current = YearMonth.from(today)
            val isCurrent = month == current
            val mileage = mileage(rows)
            val inMonth = rows.filter { YearMonth.from(it.date) == month }
            val spent = inMonth.sumOf { it.totalCents }
            val measured = inMonth.filter { (it.fill.gallonsMilli ?: 0) > 0 }
            val gallons = measured.sumOf { it.fill.gallonsMilli ?: 0 }

            val before = rows.filter { YearMonth.from(it.date) == month.minusMonths(1) }
            val comparison = if (before.isEmpty()) null else {
                val cutoff = minOf(today.dayOfMonth, month.minusMonths(1).lengthOfMonth())
                val stretch = if (isCurrent) before.filter { it.date.dayOfMonth <= cutoff } else before
                Comparison(month.minusMonths(1), spent - stretch.sumOf { it.totalCents }, soFar = isCurrent)
            }

            val byMonth = rows.groupBy { YearMonth.from(it.date) }
            val lastBar = maxOf(current, month)
            return FuelSummary(
                month = month,
                isCurrent = isCurrent,
                spentCents = spent,
                gallonsMilli = gallons,
                count = inMonth.size,
                averagePriceMilli = FuelUnits.paidPerGallon(measured.sumOf { it.totalCents }, gallons),
                comparison = comparison,
                yearCents = rows.filter { it.date.year == month.year }.sumOf { it.totalCents },
                averageMpg = mileage.average,
                lastMpg = mileage.last,
                centsPerMile = mileage.centsPerMile,
                bars = (11L downTo 0L).map { back ->
                    val m = lastBar.minusMonths(back)
                    FuelMonthBar(m, byMonth[m].orEmpty().sumOf { it.totalCents })
                },
                lines = inMonth
                    .sortedWith(compareByDescending<FillUpRow> { it.dateEpochDay }.thenByDescending { it.fill.id })
                    .map { FillUpLine(it, mileage.perFill[it.fill.id]) },
                allFillUps = rows.size,
            )
        }
    }
}

data class Mileage(val perFill: Map<Long, Double>, val average: Double?, val last: Double?, val centsPerMile: Double?)
