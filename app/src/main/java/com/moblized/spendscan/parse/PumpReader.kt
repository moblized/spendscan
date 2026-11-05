package com.moblized.spendscan.parse

import kotlin.math.abs
import kotlin.math.max

/**
 * Reads a photo of a gas pump's display, for when the station gives no receipt. Pumps rarely label their numbers in a
 * way a text reader catches, and digital digits get misread, so this leans on the one thing that's always true:
 * gallons times price equals the sale. Three numbers that fit that are almost certainly the right three.
 */
object PumpReader {
    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""(?<![\w.,])\$?([0-9OoIlSBZ]+[.,:][0-9OoIlSBZ]{1,3}|[0-9OoIlSBZ]*\d[0-9OoIlSBZ]*)(?![\w])""")
    private val SPLIT_NUMBER = Regex("""(?<![\d.,])(\d{1,3}) (\d{2,3})(?![\d.,])""")
    private val SALE_WORD = Regex("""\b(sale|total|amount|dollars?|cost)\b|\$""", ic)
    private val GALLON_WORD = Regex("""\b(gallons?|gals?|volume)\b""", ic)
    private val PRICE_WORD = Regex("""\b(price|ppg)\b|per\s*gal|/\s*gal""", ic)

    /** One way of reading a number on the display, in thousandths. */
    private data class Reading(val token: Int, val row: Int, val milli: Long, val decimals: Int, val typedDot: Boolean)

    private fun fixDigits(s: String) = s
        .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
        .replace('S', '5').replace('B', '8').replace('Z', '2')

    private fun toMilli(whole: String, part: String): Long? {
        val w = whole.ifEmpty { "0" }.toLongOrNull() ?: return null
        val p = part.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return w * 1000 + p
    }

    private fun readings(rows: List<String>): List<Reading> {
        val out = ArrayList<Reading>()
        var token = 0
        rows.forEachIndexed { rowIndex, row ->
            for (m in NUMBER.findAll(row)) {
                val text = fixDigits(m.groupValues[1]).replace(',', '.').replace(':', '.')
                if ('.' in text) {
                    val part = text.substringAfter('.')
                    toMilli(text.substringBefore('.'), part)?.let { out += Reading(token, rowIndex, it, part.length, true) }
                } else if (text.length in 3..6) {
                    // The decimal point is often lost, so it could be either kind of number.
                    val digits = text
                    toMilli(digits.dropLast(2), digits.takeLast(2))?.let { out += Reading(token, rowIndex, it, 2, false) }
                    toMilli(digits.dropLast(3), digits.takeLast(3))?.let { out += Reading(token, rowIndex, it, 3, false) }
                }
                token++
            }
            for (m in SPLIT_NUMBER.findAll(row)) {
                val part = m.groupValues[2]
                toMilli(m.groupValues[1], part)?.let { out += Reading(token, rowIndex, it, part.length, false) }
                token++
            }
        }
        return out
    }

    private val GALLONS_RANGE = 500L..400_000L
    private val PRICE_RANGE = 1_500L..12_000L
    private val TOTAL_RANGE = 1_000L..5_000_000L

    private fun cents(milli: Long) = (milli + 5) / 10

    private data class Match(val total: Reading, val gallons: Reading, val price: Reading, val score: Int)

    private fun matches(rows: List<String>, readings: List<Reading>): Match? {
        var best: Match? = null
        val prices = readings.filter { it.milli in PRICE_RANGE && it.decimals >= 2 }
        val gallons = readings.filter { it.milli in GALLONS_RANGE }
        val totals = readings.filter { it.milli in TOTAL_RANGE && it.decimals <= 3 }
        for (p in prices) for (g in gallons) {
            if (g.token == p.token) continue
            val expected = g.milli * p.milli / 1000 // thousandths of a dollar
            for (t in totals) {
                if (t.token == g.token || t.token == p.token) continue
                val off = abs(expected - t.milli)
                // A two-decimal price is missing its last digit, so it gets some room.
                val allowed = max(if (p.decimals == 3) 30L else 60L, t.milli / (if (p.decimals == 3) 400 else 150))
                if (off > allowed) continue
                var score = 0
                if (t.decimals == 2) score += 3
                if (g.decimals == 3) score += 3
                if (p.decimals == 3) score += 3 else score += 1
                if (t.typedDot) score += 1
                if (g.typedDot) score += 1
                if (p.typedDot) score += 1
                if (SALE_WORD.containsMatchIn(rows[t.row])) score += 4
                if (GALLON_WORD.containsMatchIn(rows[g.row])) score += 4
                if (PRICE_WORD.containsMatchIn(rows[p.row])) score += 4
                // With nothing else to go on, people usually pump more gallons than a gallon costs.
                if (g.milli > p.milli) score += 1
                val current = best
                if (current == null || score > current.score) best = Match(t, g, p, score)
            }
        }
        return best
    }

    /** The number on, or just under, a row with this word. */
    private fun labeled(rows: List<String>, readings: List<Reading>, word: Regex, range: LongRange): Reading? {
        for ((i, row) in rows.withIndex()) {
            if (!word.containsMatchIn(row)) continue
            readings.firstOrNull { it.row == i && it.milli in range && it.typedDot }?.let { return it }
            readings.firstOrNull { it.row == i + 1 && it.milli in range }?.let { return it }
            readings.firstOrNull { it.row == i && it.milli in range }?.let { return it }
        }
        return null
    }

    fun parse(rows: List<String>): ParsedReceipt {
        val clean = rows.map { it.trim() }.filter { it.isNotEmpty() }
        val readings = readings(clean)
        val match = matches(clean, readings)

        var total: Long?
        var gallons: Long?
        var price: Long?
        if (match != null) {
            total = cents(match.total.milli)
            gallons = match.gallons.milli
            price = match.price.milli
        } else {
            val t = labeled(clean, readings, SALE_WORD, TOTAL_RANGE)
            val g = labeled(clean, readings, GALLON_WORD, GALLONS_RANGE)
            val p = labeled(clean, readings, PRICE_WORD, PRICE_RANGE)
            total = t?.let { cents(it.milli) }
            gallons = g?.milli
            price = p?.milli
            if (total == null && gallons == null && price == null) {
                // No words to go on: a pump shows the sale to two places and the others to three.
                val dotted = readings.filter { it.typedDot }
                total = dotted.firstOrNull { it.decimals == 2 && it.milli in TOTAL_RANGE }?.let { cents(it.milli) }
                val threes = dotted.filter { it.decimals == 3 }
                price = threes.filter { it.milli in PRICE_RANGE }.minOfOrNull { it.milli }
                gallons = threes.filter { it.milli in GALLONS_RANGE && it.milli != price }.maxOfOrNull { it.milli }
            }
            // Two out of three gives the missing total.
            if (total == null && gallons != null && price != null) total = (gallons * price + 5000) / 10_000
        }

        val chain = Chains.find(clean)
        return ParsedReceipt(
            store = chain?.name,
            date = null,
            totalCents = total,
            subtotalCents = null,
            taxCents = null,
            tipCents = null,
            discountCents = null,
            payment = null,
            items = emptyList(),
            suggestedCategory = Chains.AUTO,
            unsure = buildSet {
                if (chain == null) add(ReceiptField.STORE)
                add(ReceiptField.DATE)
                if (match == null) add(ReceiptField.TOTAL)
            },
            rawText = clean.joinToString("\n"),
            fuel = gallons?.let { ParsedFuel(it, price) },
        )
    }
}
