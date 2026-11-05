package com.moblized.spendscan.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

sealed interface CalcKey {
    data class Digit(val digit: Char) : CalcKey
    data class Op(val symbol: Char) : CalcKey
    data object Dot : CalcKey
    data object Percent : CalcKey
    data object Equals : CalcKey
    data object Backspace : CalcKey
    data object Clear : CalcKey
}

const val PLUS = '+'
const val MINUS = '−'
const val TIMES = '×'
const val DIVIDE = '÷'

/** A finished sum, printed on the tape. */
data class TapeLine(val expression: String, val result: String)

/** A number already typed and the operation after it. */
data class CalcTerm(val text: String, val op: Char)

/** An adding machine: what's been keyed in, what's on the tape, and the last answer. */
data class CalcState(
    val terms: List<CalcTerm> = emptyList(),
    val entry: String = "",
    val result: BigDecimal? = null,
    val tape: List<TapeLine> = emptyList(),
    val error: String? = null,
) {
    /** The running sum as keyed, like "12.50 + 3.49 ×". */
    val expression: String
        get() = buildList {
            terms.forEach {
                add(Calculator.format(it.text))
                add(it.op.toString())
            }
            if (entry.isNotEmpty()) add(Calculator.format(entry))
        }.joinToString(" ")

    /** What it comes to so far, or the last answer. Null when there's nothing to show. */
    val value: BigDecimal?
        get() = result ?: Calculator.evaluate(terms, entry)

    /** [value] as money. Null if there's nothing, or it isn't a positive amount. */
    val cents: Long?
        get() = value?.setScale(2, RoundingMode.HALF_UP)?.movePointRight(2)?.toLong()?.takeIf { it > 0 }
}

object Calculator {
    private const val MAX_DIGITS = 15
    private val context = MathContext(24, RoundingMode.HALF_UP)

    fun press(s: CalcState, key: CalcKey): CalcState {
        val state = s.copy(error = null)
        return when (key) {
            is CalcKey.Digit -> digit(state, key.digit)
            CalcKey.Dot -> dot(state)
            is CalcKey.Op -> op(state, key.symbol)
            CalcKey.Percent -> percent(state)
            CalcKey.Equals -> equals(state)
            CalcKey.Backspace -> backspace(state)
            CalcKey.Clear -> CalcState(tape = state.tape)
        }
    }

    fun clearTape(s: CalcState): CalcState = s.copy(tape = emptyList())

    private fun digit(s: CalcState, d: Char): CalcState {
        val base = if (s.result != null) s.copy(result = null, entry = "") else s
        val entry = base.entry
        if (entry.count { it.isDigit() } >= MAX_DIGITS) return base
        return base.copy(
            entry = when (entry) {
                "0" -> d.toString()
                "-0" -> "-$d"
                else -> entry + d
            },
        )
    }

    private fun dot(s: CalcState): CalcState {
        val base = if (s.result != null) s.copy(result = null, entry = "") else s
        val entry = base.entry
        return when {
            '.' in entry -> base
            entry.isEmpty() -> base.copy(entry = "0.")
            entry == "-" -> base.copy(entry = "-0.")
            else -> base.copy(entry = "$entry.")
        }
    }

    private fun isNumber(entry: String) = entry.isNotEmpty() && entry != "-"

    private fun op(s: CalcState, symbol: Char): CalcState {
        // Carry on from the last answer.
        val base = s.result?.let { s.copy(result = null, entry = plain(it)) } ?: s
        if (isNumber(base.entry)) {
            return base.copy(terms = base.terms + CalcTerm(base.entry.trimEnd('.'), symbol), entry = "")
        }
        if (base.entry == "-") return if (symbol == MINUS) base else base.copy(entry = "")
        val last = base.terms.lastOrNull()
        return when {
            // A minus sign starts a negative number: at the very start, or after × and ÷.
            symbol == MINUS && (last == null || last.op == TIMES || last.op == DIVIDE) -> base.copy(entry = "-")
            last == null -> base
            else -> base.copy(terms = base.terms.dropLast(1) + last.copy(op = symbol))
        }
    }

    /** Works like a phone calculator: 80 + 10 % is 88, and 45.20 × 18 % is the 18% tip. */
    private fun percent(s: CalcState): CalcState {
        val base = s.result?.let { s.copy(result = null, entry = plain(it)) } ?: s
        if (!isNumber(base.entry)) return base
        val number = BigDecimal(base.entry.trimEnd('.'))
        val last = base.terms.lastOrNull()
        val share = if (last != null && (last.op == PLUS || last.op == MINUS)) {
            val soFar = evaluate(base.terms, "") ?: return base
            soFar.multiply(number, context).movePointLeft(2)
        } else {
            number.movePointLeft(2)
        }
        return base.copy(entry = plain(share))
    }

    private fun equals(s: CalcState): CalcState {
        if (s.result != null || s.terms.isEmpty()) return s
        if (dividesByZero(s.terms, s.entry)) return s.copy(error = "Can't divide by zero")
        val answer = evaluate(s.terms, s.entry) ?: return s
        val expression = s.copy(terms = if (isNumber(s.entry)) s.terms else s.terms.dropLast(1).plus(s.terms.last().copy(op = ' ')), entry = s.entry)
            .expression.trim()
        return s.copy(terms = emptyList(), entry = "", result = answer, tape = s.tape + TapeLine(expression, format(plain(answer))))
    }

    private fun backspace(s: CalcState): CalcState {
        if (s.result != null) return s.copy(result = null, entry = "")
        if (s.entry.isNotEmpty()) return s.copy(entry = s.entry.dropLast(1))
        val last = s.terms.lastOrNull() ?: return s
        return s.copy(terms = s.terms.dropLast(1), entry = last.text)
    }

    private fun numbers(terms: List<CalcTerm>, entry: String): Pair<List<BigDecimal>, List<Char>>? {
        val values = terms.map { BigDecimal(it.text) }.toMutableList()
        val ops = terms.map { it.op }.toMutableList()
        if (isNumber(entry)) {
            values += BigDecimal(entry.trimEnd('.'))
        } else if (ops.isNotEmpty()) {
            ops.removeAt(ops.size - 1)
        }
        return if (values.isEmpty()) null else values to ops
    }

    private fun dividesByZero(terms: List<CalcTerm>, entry: String): Boolean {
        val (values, ops) = numbers(terms, entry) ?: return false
        return ops.indices.any { ops[it] == DIVIDE && values[it + 1].signum() == 0 }
    }

    /** Works out the sum, times and divides before plus and minus. Null when there's nothing to work out, or it can't be done. */
    fun evaluate(terms: List<CalcTerm>, entry: String): BigDecimal? {
        val (first, firstOps) = numbers(terms, entry) ?: return null
        val values = first.toMutableList()
        val ops = firstOps.toMutableList()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op == TIMES || op == DIVIDE) {
                if (op == DIVIDE && values[i + 1].signum() == 0) return null
                values[i] = if (op == TIMES) values[i].multiply(values[i + 1], context) else values[i].divide(values[i + 1], context)
                values.removeAt(i + 1)
                ops.removeAt(i)
            } else {
                i++
            }
        }
        var total = values[0]
        ops.forEachIndexed { index, op ->
            total = if (op == PLUS) total.add(values[index + 1], context) else total.subtract(values[index + 1], context)
        }
        return total
    }

    private fun plain(n: BigDecimal): String {
        val rounded = n.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros()
        return if (rounded.signum() == 0) "0" else rounded.toPlainString()
    }

    /** "1234.5" as "1,234.5". Keeps what's typed, including a trailing dot or zeros, so a number looks as it was keyed. */
    fun format(text: String): String {
        if (text.isEmpty() || text == "-") return text
        val negative = text.startsWith("-")
        val digits = text.removePrefix("-")
        val whole = digits.substringBefore('.')
        val grouped = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(BigDecimal(whole.ifEmpty { "0" }))
        val rest = if ('.' in digits) "." + digits.substringAfter('.') else ""
        return (if (negative) "-" else "") + grouped + rest
    }

    /** An answer for showing. Whole cents show two places, because they're usually money. */
    fun show(n: BigDecimal): String {
        val p = plain(n)
        val decimals = p.substringAfter('.', "")
        return format(if (decimals.length == 1) p + "0" else p)
    }
}
