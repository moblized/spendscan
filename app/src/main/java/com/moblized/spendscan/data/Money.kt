package com.moblized.spendscan.data

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Amounts are whole cents everywhere, so they never pick up rounding errors. */
object Money {
    private var cached: Pair<Locale, NumberFormat>? = null

    private fun formatter(locale: Locale): NumberFormat {
        val c = cached
        if (c != null && c.first == locale) return c.second
        return NumberFormat.getCurrencyInstance(locale).also { cached = locale to it }
    }

    /** "$1,284.16" in the phone's currency. */
    @Synchronized
    fun format(cents: Long, locale: Locale = Locale.getDefault()): String = formatter(locale).format(cents / 100.0)

    /** "$500" when it's a round amount, as budgets usually are; otherwise the same as [format]. */
    @Synchronized
    fun short(cents: Long, locale: Locale = Locale.getDefault()): String {
        if (cents % 100 != 0L) return format(cents, locale)
        val f = NumberFormat.getCurrencyInstance(locale)
        f.maximumFractionDigits = 0
        return f.format(cents / 100)
    }

    /** "12.50", for editing and CSV files. */
    fun plain(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = abs(cents)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    /** What a person typed: "12", "12.5", "$1,234.50", ".99", "12,50". Null when it isn't an amount. */
    fun parse(text: String): Long? {
        val t = text.trim().trimStart('$', '€', '£').trim()
        if (t.isEmpty()) return null
        val decimalComma = Regex("""^\d+,\d{1,2}$""").matches(t)
        val s = if (decimalComma) t.replace(',', '.') else t.replace(",", "")
        if (!Regex("""^\d*\.?\d{0,2}$""").matches(s) || s == ".") return null
        val parts = s.split('.')
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = parts.getOrNull(1).orEmpty().padEnd(2, '0').toLong()
        return whole * 100 + frac
    }
}
