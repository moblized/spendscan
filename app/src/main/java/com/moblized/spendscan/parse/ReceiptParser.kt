package com.moblized.spendscan.parse

import java.time.DateTimeException
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A line of text the phone read from a receipt photo, and where it sits (pixels; pages stacked top to bottom). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = max(1, bottom - top)
    val width: Int get() = max(1, right - left)
}

/** The parts of a receipt that decide what gets tracked, and so get flagged when the reading looks unsure. */
enum class ReceiptField { STORE, DATE, TOTAL }

data class ParsedItem(val name: String, val quantity: Double = 1.0, val amountCents: Long)

data class ParsedReceipt(
    val store: String?,
    val date: LocalDate?,
    val totalCents: Long?,
    val subtotalCents: Long?,
    val taxCents: Long?,
    val tipCents: Long?,
    val discountCents: Long?,
    /** How it was paid: "Visa 4242", "Debit", "Cash". */
    val payment: String?,
    val items: List<ParsedItem>,
    /** Category name the store usually belongs to, when the receipt makes that clear. */
    val suggestedCategory: String?,
    /** Fields a person should look at before saving. */
    val unsure: Set<ReceiptField>,
    val rawText: String,
)

/**
 * Turns the text of a receipt into what was spent. Works on rows of text; [rows] rebuilds them from where the
 * phone found each piece of text, because prices are usually read as a separate column from the item names.
 */
object ReceiptParser {
    private val ic = setOf(RegexOption.IGNORE_CASE)

    // --- Rebuilding rows ------------------------------------------------------------------------------------------

    /** Puts words printed on the same row back together ("Milk" on the left, "3.49" on the right). */
    fun rows(lines: List<OcrLine>): List<String> {
        val ls = lines.filter { it.text.isNotBlank() }
        if (ls.isEmpty()) return emptyList()
        val typicalHeight = ls.map { it.height }.sorted()[ls.size / 2].toDouble()
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in ls.sortedBy { it.centerY }) {
            val row = rows.filter { r ->
                abs(r.map { it.centerY }.average() - l.centerY) <= 0.6 * typicalHeight &&
                    r.none { o -> overlapX(o, l) > 0.3 * min(o.width, l.width) }
            }.minByOrNull { r -> abs(r.map { it.centerY }.average() - l.centerY) }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows.sortedBy { r -> r.map { it.centerY }.average() }
            .map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    private fun overlapX(a: OcrLine, b: OcrLine) = max(0, min(a.right, b.right) - max(a.left, b.left))

    // --- Reading one row ------------------------------------------------------------------------------------------

    private data class Line(val index: Int, val raw: String, val label: String, val cents: Long?, val negative: Boolean) {
        val low: String = label.lowercase()
        val letters: Int = label.count { it.isLetter() }
    }

    private val AMOUNT = Regex("""^[$€£]?([(-])?[$€£]?(\d{1,3}(?:,\d{3})+|\d{0,6})[.,](\d{2})([)-])?([A-Za-z*]{0,2})$""")
    private val MAYBE_AMOUNT = Regex("""^[$€£(-]{0,3}[0-9OoIlSB]{1,6}[.,][0-9OoIlSB]{2}[)-]?[A-Z*]{0,2}$""")
    private val FLAG = Regex("""^[A-Za-z]{1,2}\*?$|^\*+$|^USD$|^#$""")
    private val TIME_WORD = Regex("""^[ap]\.?m\.?$""", ic)
    private val SKU = Regex("""^#?\d{5,}[A-Za-z]{0,2}$""")
    private val ANGLE_FLAG = Regex("""^<[A-Za-z]{1,2}>$""")

    /** Common misreads inside a number: "1O.99" is 10.99, "4.5O" is 4.50. */
    private fun fixDigits(token: String): String {
        if (!MAYBE_AMOUNT.matches(token) || token.none { it.isDigit() }) return token
        // A leading S is a misread dollar sign rather than a 5.
        val t = if (token.first() == 'S' && token.getOrNull(1)?.isDigit() == true) token.substring(1) else token
        val end = t.indexOfLast { it == '.' || it == ',' } + 3
        val number = t.substring(0, end.coerceAtMost(t.length))
            .replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('S', '5').replace('B', '8')
        return number + t.substring(end.coerceAtMost(t.length))
    }

    private fun amountOf(token: String): Pair<Long, Boolean>? {
        val m = AMOUNT.matchEntire(fixDigits(token)) ?: return null
        val whole = m.groupValues[2].replace(",", "").ifEmpty { "0" }.toLongOrNull() ?: return null
        val cents = whole * 100 + m.groupValues[3].toLong()
        if (cents > 9_999_999) return null
        val negative = m.groupValues[1].isNotEmpty() || m.groupValues[4].isNotEmpty()
        return cents to negative
    }

    private fun parseLine(index: Int, raw: String): Line {
        val cleaned = raw.trim()
            .replace(Regex("""[$€£]\s+(?=\d)"""), "\\$")
            .replace(Regex("""(\d)\s*[.,]\s+(\d{2})\b"""), "$1.$2")
            .replace(Regex("""(\d)\s+[.,](\d{2})\b"""), "$1.$2")
            .replace(Regex("""([A-Za-z:])\$"""), "$1 \\$")
            .replace(Regex("""([A-Za-z]):(\d)"""), "$1: $2")
            .replace(Regex("""\s+"""), " ")
        val tokens = cleaned.split(' ').filter { it.isNotEmpty() }
        var i = tokens.size - 1
        while (i > 0 && FLAG.matches(tokens[i]) && amountOf(tokens[i]) == null) i--
        val trailing = tokens.subList((i + 1).coerceAtMost(tokens.size), tokens.size)
        val amount = tokens.getOrNull(i)
            ?.takeIf { trailing.none { t -> TIME_WORD.matches(t) } }
            ?.let { amountOf(it) }
            // "2 @ 0.98" gives a unit price, not an amount for the row.
            ?.takeIf { i == 0 || !tokens[i - 1].endsWith("@") }
        val labelTokens = (if (amount != null) tokens.subList(0, i) else tokens).toMutableList()
        if (amount != null) {
            // A tax flag just before the price ("MILK F 3.12").
            if (labelTokens.size > 1 && labelTokens.last().matches(Regex("""^[A-Z]\*?$"""))) labelTokens.removeAt(labelTokens.size - 1)
            // A department letter before a code ("E 1234567 WATER").
            if (labelTokens.size > 2 && labelTokens[0].length == 1 && labelTokens[1].first().isDigit()) labelTokens.removeAt(0)
        }
        val label = labelTokens.filterNot { SKU.matches(it) || ANGLE_FLAG.matches(it) }.joinToString(" ").trim(' ', '.', ':', '*', '#', '=', '-', '_', '>')
        return Line(index, cleaned, label, amount?.first, amount?.second ?: false)
    }

    // --- What a row means -----------------------------------------------------------------------------------------

    private const val TOTAL_WORD = """t\s?[o0]\s?t\s?a\s?[l1i]|totl|ttl"""
    private val NOT_TOTAL = Regex(
        """sub\s*-?\s*($TOTAL_WORD|tl)|total\s*(tax|sav|saved|disc|items?|qty|quant|points?|sold|number|no\b|coupon|weight|tender|gal|cards?|rewards?|credit\b|net\b)|""" +
            """(tax|sav\w*|discount|items?|points?|rewards?)\s*total|items?\s*sold|(before|pre|excl\w*)\s*-?\s*tax""",
        ic,
    )
    private val TOTAL_STRONG = Regex(
        """(grand|order|sale|check|bill|invoice|receipt|purchase|final)\s*(total|amount|amt)|""" +
            """total\s*(due|amount|amt|charged?|sale|purchase|to pay|payable|usd|price|cost|owed)|""" +
            """(amount|amt|balance|bal)\s*(due|owed|to pay|charged)|you pay|\bto pay\b|please pay|pay this amount""",
        ic,
    )
    private val TOTAL_PLAIN = Regex("""\b($TOTAL_WORD)\b""", ic)
    /** "Total paid" can be the cash handed over, so it only counts when nothing better is printed. */
    private val TOTAL_PAID = Regex("""(total|amount|amt)\s*paid|you paid""", ic)
    private val TOTAL_WEAK = Regex("""^(amount|amt|balance|bal|sale|purchase|charged?|fuel sale|fuel)\b""", ic)
    private val STORED_VALUE = Regex("""loyalty|gift|rewards?|points?|remaining|available|beginning|ending|previous|new bal|card bal|prepaid|snap bal|ebt bal""", ic)
    private val SUBTOTAL = Regex("""sub\s*-?\s*($TOTAL_WORD|tl)|^net sales?\b|^merchandise\b""", ic)
    private val TAX = Regex("""\b(tax(es)?|tx|vat|hst|gst|pst|qst)\b|^tax\d""", ic)
    private val NOT_TAX = Regex("""taxable|non-?\s*tax|tax\s*exempt|before tax|pre-?\s*tax|taxes? paid at pump""", ic)
    private val TIP = Regex("""^(tip|gratuity|grat|service charge|auto\s*grat)\b""", ic)
    private val TIP_GUIDE = Regex("""(suggested|recommended)\s*(tip|gratuity)|(tip|gratuity)\s*(guide|calc\w*|suggest\w*)|add a tip""", ic)
    private val TIP_PERCENT = Regex("""^(tip|gratuity)?\s*:?\s*\d{1,2}\s?%""", ic)
    private val DISCOUNT = Regex("""\b(discount|coupon|cpn|promo|markdown|mfr|manufacturer|mvp|bogo|instant sav\w*|member sav\w*|savings?|you saved|saved)\b""", ic)
    private val SAVED_SUMMARY = Regex("""you saved|total sav|saved today|your savings|savings today|total disc""", ic)
    private val CARD = Regex("""\b(visa|mastercard|master card|m/?c|amex|american express|discover|debit|credit|ebt|apple pay|google pay|gpay|paypal|venmo|contactless)\b""", ic)
    private val CASH = Regex("""\b(cash|tend|tendered|change|chg)\b""", ic)
    private val MASKED_CARD = Regex("""(?:[*xX#•]{2,}[\s-]*){1,4}(\d{4})\b|(?:ending in|ending|last 4)\D{0,3}(\d{4})\b|(?:^|\s)\*(\d{4})\b""", ic)

    /** Rows that are never something bought. */
    private val NOT_ITEM = Regex(
        """\b(tender(ed)?|tend|cash|credit|debit|visa|mastercard|master card|amex|american express|discover|ebt|""" +
            """auth(orization)?|approval|approved|reference|transaction|terminal|merchant|tvr|tsi|""" +
            """cashier|receipt|invoice|thank(s| you)?|survey|www|http|https|items? sold|item count|items in|""" +
            """signature|contactless|swiped?|points|rewards?|loyalty|acct|account|balance)\b|""" +
            """^(change|due|paid|payment|amount|amt|card\b|store\b|date\b|time\b|server\b|host\b|member\b|customer\b|copy\b|lane\b|""" +
            """operator\b|manager\b|mgr\b|ref\b|tip\b|visit\b|guests?\b)|""" +
            """^(table|tbl|check|chk|ticket|reg(ister)?|order|tel|phone|fax|aid)\s*[#:\d(]|\.com\b""",
        ic,
    )
    private val PHONE = Regex("""\(?\d{3}\)?[-. ]\d{3}[-. ]\d{4}""")
    private val ADDRESS = Regex(
        """^\d+\s+\w+.*\b(st|street|ave|avenue|blvd|boulevard|rd|road|dr|drive|ln|lane|hwy|highway|pkwy|parkway|way|ct|court|pl|place|suite|ste|plaza|pike|sq|square)\b""",
        ic,
    )
    private val CITY = Regex("""[A-Za-z .]+,?\s+[A-Z]{2},?\s+\d{5}""")

    // Quantities and weights
    private val AT_QTY = Regex("""(?<![\d.])(\d{1,3})\s*(?:@|[xX]|\bat\b)\s*\$?(\d{0,4}[.,]\d{2})(?:\s*/?\s*(?:ea|each))?""", ic)
    private val AT_WEIGHT = Regex(
        """(\d{0,3}[.,]\d{1,3})\s*(?:lbs?|kg|oz)\b\s*(?:@|[xX*]|\bat\b)?\s*(?:\d\s*(?:lbs?|kg)\s*/\s*)?\$?(\d{0,4}[.,]\d{2})(?:\s*/\s*(?:lbs?|kg|oz))?""",
        ic,
    )
    private val LEAD_QTY = Regex("""^(\d{1,2})\s*[xX]?\s+(?=\S*[A-Za-z])""")
    private val TRAIL_QTY = Regex("""\s+[xX]\s*(\d{1,2})$|\bqty:?\s*(\d{1,2})\b""", ic)

    private data class Meta(val qty: Double, val unitCents: Long, val rowCents: Long?, val weighed: Boolean)

    /** "2 @ 0.98" or "1.18 lb @ 0.69/lb 0.81": details about an item rather than an item. */
    private fun metaOf(raw: String): Meta? {
        val weight = AT_WEIGHT.find(raw)
        val at = if (weight == null) AT_QTY.find(raw) else null
        val m = weight ?: at ?: return null
        val rest = raw.removeRange(m.range).trim()
        val words = rest.replace(Regex("""\b(ea|each|lbs?|kg|oz|net|wt|gross|tare)\b""", ic), "")
        val tokens = rest.split(' ').filter { it.isNotEmpty() }
        val rowAmount = tokens.lastOrNull { amountOf(it) != null }?.let { amountOf(it)?.first }
        if (words.count { it.isLetter() } >= 3) return null
        return Meta(
            qty = if (weight != null) 1.0 else m.groupValues[1].toDouble(),
            unitCents = amountOf(m.groupValues[2])?.first ?: 0,
            rowCents = rowAmount,
            weighed = weight != null,
        )
    }

    // --- The whole receipt ----------------------------------------------------------------------------------------

    fun parse(rows: List<String>, today: LocalDate = LocalDate.now()): ParsedReceipt {
        val raw = rows.map { it.trim() }.filter { it.isNotEmpty() }
        val rawText = raw.joinToString("\n")
        // Printed tip suggestions repeat "Total" with amounts that were never charged.
        val tipRows = raw.indices.filter { TIP_PERCENT.containsMatchIn(raw[it]) }
        val guideAt = listOfNotNull(
            raw.indexOfFirst { TIP_GUIDE.containsMatchIn(it) }.takeIf { it >= 0 },
            tipRows.firstOrNull().takeIf { tipRows.size >= 2 },
        ).minOrNull() ?: -1
        val usable = if (guideAt >= 0) raw.take(guideAt) else raw
        val lines = usable.mapIndexed { i, r -> parseLine(i, r) }

        val isSubtotal = { l: Line -> SUBTOTAL.containsMatchIn(l.low) }
        val subtotal = lines.lastOrNull { it.cents != null && it.cents > 0 && isSubtotal(it) }

        val total = pickTotal(lines)
        val itemsEnd = listOfNotNull(subtotal?.index, total?.line?.index).minOrNull() ?: lines.size

        val taxLines = lines.filter {
            it.cents != null && !it.negative && TAX.containsMatchIn(it.low) && !NOT_TAX.containsMatchIn(it.low) &&
                !isSubtotal(it) && (!TOTAL_PLAIN.containsMatchIn(it.low) || NOT_TOTAL.containsMatchIn(it.low)) &&
                it.index >= itemsEnd
        }
        // "Total tax" repeats the rates above it, so it wins alone.
        val totalTax = taxLines.lastOrNull { TOTAL_PLAIN.containsMatchIn(it.low) }
        val tax = totalTax?.cents ?: taxLines.mapNotNull { it.cents }.takeIf { it.isNotEmpty() }?.sum()
        val tip = lines.lastOrNull { it.cents != null && it.cents > 0 && TIP.containsMatchIn(it.low) }?.cents

        val items = readItems(lines, itemsEnd)
        val negatives = lines.filter {
            it.cents != null && it.cents > 0 && it.index < (total?.line?.index ?: lines.size) &&
                (it.negative || (DISCOUNT.containsMatchIn(it.low) && !SAVED_SUMMARY.containsMatchIn(it.low)))
        }
        val summary = lines.lastOrNull { it.cents != null && SAVED_SUMMARY.containsMatchIn(it.low) }?.cents
        val discount = negatives.mapNotNull { it.cents }.takeIf { it.isNotEmpty() }?.sum() ?: summary

        val itemSum = items.sumOf { it.amountCents }
        val payment = readPayment(lines)
        val cardPaid = lines.filter { it.cents != null && CARD.containsMatchIn(it.low) }.mapNotNull { it.cents }

        var totalCents = total?.line?.cents
        // No readable total: work it out from what's there.
        if (totalCents == null) {
            totalCents = cardPaid.maxOrNull()
                ?: subtotal?.cents?.let { it + (tax ?: 0) + (tip ?: 0) }
                ?: lines.filter {
                    it.cents != null && !it.negative && !CASH.containsMatchIn(it.low) && !STORED_VALUE.containsMatchIn(it.low)
                }.mapNotNull { it.cents }.maxOrNull()
        }

        val close = { a: Long?, b: Long? -> a != null && b != null && abs(a - b) <= 2 }
        val base = subtotal?.cents ?: itemSum.takeIf { items.isNotEmpty() }
        val adds = (tax ?: 0) + (tip ?: 0)
        val confirmed = totalCents != null && total != null && (
            close(totalCents, base?.plus(adds)) ||
                close(totalCents, base?.plus(adds)?.minus(discount ?: 0)) ||
                cardPaid.any { close(it, totalCents) } ||
                total.strength == 3 && base == null
            )

        val chain = Chains.find(lines.take(10).filter { it.cents == null }.map { it.raw })
        val store = chain?.name ?: guessStoreName(lines.take(7))
        val date = readDate(raw, today)

        val unsure = buildSet {
            if (store == null || chain == null) add(ReceiptField.STORE)
            if (date == null) add(ReceiptField.DATE)
            if (!confirmed) add(ReceiptField.TOTAL)
        }

        return ParsedReceipt(
            store = store,
            date = date,
            totalCents = totalCents,
            subtotalCents = subtotal?.cents,
            taxCents = tax,
            tipCents = tip,
            discountCents = discount,
            payment = payment,
            items = items,
            suggestedCategory = chain?.category ?: Chains.categoryFromText(raw),
            unsure = unsure,
            rawText = rawText,
        )
    }

    private data class TotalPick(val line: Line, val strength: Int)

    /** Finds the amount that was actually charged: strongest wording first, then the largest of those. */
    private fun pickTotal(lines: List<Line>): TotalPick? {
        val candidates = ArrayList<TotalPick>()
        lines.forEachIndexed { i, l ->
            if (NOT_TOTAL.containsMatchIn(l.low) || STORED_VALUE.containsMatchIn(l.low)) return@forEachIndexed
            val strength = when {
                TOTAL_PAID.containsMatchIn(l.low) -> 1
                TOTAL_STRONG.containsMatchIn(l.low) -> 3
                TOTAL_PLAIN.containsMatchIn(l.low) -> 2
                TOTAL_WEAK.containsMatchIn(l.low) -> 1
                else -> 0
            }
            if (strength == 0) return@forEachIndexed
            if (l.cents != null) {
                if (l.cents > 0 && !l.negative) candidates += TotalPick(l, strength)
            } else if (l.letters <= 24) {
                // The amount can land on the next row when the word and number don't line up.
                val next = lines.getOrNull(i + 1)
                if (next?.cents != null && next.cents > 0 && next.letters == 0) {
                    candidates += TotalPick(l.copy(cents = next.cents), strength)
                }
            }
        }
        if (candidates.isEmpty()) return null
        val top = candidates.maxOf { it.strength }
        // A plain "Total" and a "Total due" on one receipt are usually the same; the higher amount includes any tip.
        val pool = candidates.filter { it.strength >= min(top, 2) }
        return pool.maxWithOrNull(compareBy<TotalPick> { it.line.cents }.thenBy { it.strength })
    }

    private fun readItems(lines: List<Line>, end: Int): List<ParsedItem> {
        val items = ArrayList<ParsedItem>()
        var pendingName: Line? = null
        var pendingQty: Double? = null
        var firstPriced = false
        for (l in lines) {
            if (l.index >= end) break
            val meta = metaOf(l.raw)
            if (meta != null) {
                if (meta.rowCents != null && pendingName != null) {
                    items += ParsedItem(cleanName(pendingName.label), meta.qty, meta.rowCents)
                    pendingName = null
                } else if (meta.rowCents == null && items.isNotEmpty() &&
                    abs(items.last().amountCents - (meta.qty * meta.unitCents).roundToLong()) <= 2 && !meta.weighed
                ) {
                    items[items.size - 1] = items.last().copy(quantity = meta.qty)
                } else if (meta.rowCents == null && !meta.weighed) {
                    pendingQty = meta.qty
                }
                continue
            }
            if (l.cents == null) {
                pendingName = l.takeIf { isItemName(it) && inlineQtyFree(it) }
                continue
            }
            if (l.letters < 2) {
                // A price alone on its row belongs to the name above it.
                val name = pendingName
                if (name != null && !l.negative && l.cents > 0) items += ParsedItem(cleanName(name.label), pendingQty ?: 1.0, l.cents)
                pendingName = null
                pendingQty = null
                continue
            }
            pendingName = null
            if (l.negative || l.cents == 0L || !isItemName(l) || DISCOUNT.containsMatchIn(l.low)) continue
            firstPriced = true
            items += itemFrom(l, pendingQty)
            pendingQty = null
        }
        return if (firstPriced || items.isNotEmpty()) items else emptyList()
    }

    private fun inlineQtyFree(l: Line) = l.letters >= 3

    private fun isItemName(l: Line): Boolean =
        l.letters >= 2 && !NOT_ITEM.containsMatchIn(l.low) && !TAX.containsMatchIn(l.low) && !TOTAL_PLAIN.containsMatchIn(l.low) &&
            !SUBTOTAL.containsMatchIn(l.low) && !PHONE.containsMatchIn(l.raw) && !ADDRESS.containsMatchIn(l.raw) &&
            !CITY.containsMatchIn(l.raw) && !DATE_ANY.containsMatchIn(l.raw)

    private fun itemFrom(l: Line, pendingQty: Double?): ParsedItem {
        var qty = pendingQty ?: 1.0
        var name = l.label
        AT_WEIGHT.find(name)?.let { name = name.removeRange(it.range) }
        AT_QTY.find(name)?.let {
            qty = it.groupValues[1].toDouble()
            name = name.removeRange(it.range)
        }
        name = name.replace(Regex("""\s*@\s*\$?\d*[.,]\d{2}.*$"""), "").trim()
        LEAD_QTY.find(name)?.let {
            val n = it.groupValues[1].toInt()
            if (n in 1..50) {
                qty = n.toDouble()
                name = name.substring(it.range.last + 1)
            }
        }
        TRAIL_QTY.find(name)?.let {
            qty = it.groupValues[1].ifEmpty { it.groupValues[2] }.toDouble()
            name = name.removeRange(it.range)
        }
        return ParsedItem(cleanName(name), qty, l.cents ?: 0)
    }

    private fun cleanName(s: String) = s.split(' ').filter { it.isNotBlank() }.joinToString(" ").trim(' ', '.', ':', '*', '-', '_')

    private fun readPayment(lines: List<Line>): String? {
        val last4 = lines.firstNotNullOfOrNull { l ->
            MASKED_CARD.find(l.raw)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } } }
        }
        val brand = lines.firstNotNullOfOrNull { l ->
            when {
                Regex("""\bvisa\b""", ic).containsMatchIn(l.raw) -> "Visa"
                Regex("""\b(mastercard|master card)\b""", ic).containsMatchIn(l.raw) -> "Mastercard"
                Regex("""\b(amex|american express)\b""", ic).containsMatchIn(l.raw) -> "Amex"
                Regex("""\bdiscover\b""", ic).containsMatchIn(l.raw) -> "Discover"
                Regex("""\bapple pay\b""", ic).containsMatchIn(l.raw) -> "Apple Pay"
                Regex("""\b(google pay|gpay)\b""", ic).containsMatchIn(l.raw) -> "Google Pay"
                Regex("""\bebt\b""", ic).containsMatchIn(l.raw) -> "EBT"
                Regex("""\bdebit\b""", ic).containsMatchIn(l.raw) -> "Debit"
                Regex("""\bcredit\b""", ic).containsMatchIn(l.raw) && !l.low.contains("store credit") -> "Credit"
                else -> null
            }
        }
        if (brand != null) return listOfNotNull(brand, last4).joinToString(" ")
        if (last4 != null) return "Card $last4"
        if (lines.any { it.cents != null && Regex("""\bcash\b""", ic).containsMatchIn(it.low) }) return "Cash"
        return null
    }

    // --- Store name -----------------------------------------------------------------------------------------------

    private val NOT_A_NAME = Regex(
        """receipt|welcome|thank|survey|www\.|http|\.com|tel\b|phone|fax|\bdate\b|\btime\b|order|invoice|cashier|server|table|guest|customer copy|merchant copy|store\s*#|open\s|hours|^\W*$""",
        ic,
    )

    private fun guessStoreName(top: List<Line>): String? {
        for (l in top) {
            if (l.cents != null) continue
            val text = l.label
            val letters = text.count { it.isLetter() }
            if (letters < 3 || letters < text.length * 0.5) continue
            if (NOT_A_NAME.containsMatchIn(text) || PHONE.containsMatchIn(text) || ADDRESS.containsMatchIn(text) ||
                CITY.containsMatchIn(text) || DATE_ANY.containsMatchIn(text)
            ) continue
            val trimmed = text.replace(Regex("""\s*(#|no\.?|store)\s*\d+.*$""", ic), "").trim()
            if (trimmed.count { it.isLetter() } < 3) continue
            return titleCase(trimmed)
        }
        return null
    }

    fun titleCase(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        return s.lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            w.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercase() } }
        }
    }

    // --- Dates ----------------------------------------------------------------------------------------------------

    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val DATE_NUMERIC = Regex("""(?<!\d)(\d{1,2})\s?[/\-.]\s?(\d{1,2})\s?[/\-.]\s?(\d{4}|\d{2})(?!\d)""")
    private val DATE_ISO = Regex("""(?<!\d)(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})(?!\d)""")
    private val DATE_WORD_MDY = Regex("""\b($MONTHS)\.?\s*(\d{1,2})(?:st|nd|rd|th)?[,\s'-]*(\d{4}|\d{2})\b""", ic)
    private val DATE_WORD_DMY = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s-]*($MONTHS)\.?[,\s'-]*(\d{4}|\d{2})\b""", ic)
    private val DATE_ANY = Regex("""${DATE_NUMERIC.pattern}|${DATE_ISO.pattern}""")
    private val NOT_PURCHASE_DATE = Regex("""\bexp\w*|valid|return|refund by|good (through|thru|until)|birth|since|due date|offer|ends?\b""", ic)
    private val DATE_HINT = Regex("""\bdate\b|\d{1,2}:\d{2}""", ic)

    private fun monthNumber(s: String): Int = when (s.take(3).lowercase()) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun fullYear(s: String): Int = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    private fun dateOrNull(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (_: DateTimeException) {
        null
    }

    private fun datesIn(row: String): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        DATE_ISO.findAll(row).forEach { m ->
            dateOrNull(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { out += it }
        }
        if (out.isEmpty()) {
            DATE_NUMERIC.findAll(row).forEach { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val y = fullYear(m.groupValues[3])
                // US receipts: month first, unless that can't be a month.
                (if (a > 12) dateOrNull(y, b, a) else dateOrNull(y, a, b))?.let { out += it }
            }
        }
        DATE_WORD_MDY.findAll(row).forEach { m ->
            dateOrNull(fullYear(m.groupValues[3]), monthNumber(m.groupValues[1]), m.groupValues[2].toInt())?.let { out += it }
        }
        DATE_WORD_DMY.findAll(row).forEach { m ->
            dateOrNull(fullYear(m.groupValues[3]), monthNumber(m.groupValues[2]), m.groupValues[1].toInt())?.let { out += it }
        }
        return out
    }

    private fun readDate(rows: List<String>, today: LocalDate): LocalDate? {
        val earliest = today.minusYears(10)
        val latest = today.plusDays(1)
        val found = rows.flatMap { row ->
            if (NOT_PURCHASE_DATE.containsMatchIn(row)) emptyList()
            else datesIn(row).filter { !it.isBefore(earliest) && !it.isAfter(latest) }.map { it to row }
        }
        return (found.firstOrNull { DATE_HINT.containsMatchIn(it.second) } ?: found.firstOrNull())?.first
    }
}
