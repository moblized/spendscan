package com.moblized.spendscan.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CalculatorTest {
    private fun key(c: Char): CalcKey = when (c) {
        '.' -> CalcKey.Dot
        '=' -> CalcKey.Equals
        '%' -> CalcKey.Percent
        '<' -> CalcKey.Backspace
        'C' -> CalcKey.Clear
        '+', '−', '×', '÷' -> CalcKey.Op(c)
        else -> CalcKey.Digit(c)
    }

    private fun run(keys: String, from: CalcState = CalcState()) =
        keys.filter { it != ' ' }.fold(from) { s, c -> Calculator.press(s, key(c)) }

    private fun answer(keys: String) = run(keys).value?.let { Calculator.show(it) }

    @Test
    fun adds() {
        assertEquals("15.99", answer("12.50 + 3.49 ="))
        assertEquals("3", answer("1 + 2 ="))
        assertEquals("0.30", answer("0.1 + 0.2 ="))
    }

    @Test
    fun timesAndDivideComeFirst() {
        assertEquals("14", answer("2 + 3 × 4 ="))
        assertEquals("7", answer("10 − 6 ÷ 2 ="))
        assertEquals("2", answer("8 ÷ 2 ÷ 2 ="))
    }

    @Test
    fun divisionKeepsItsDecimals() {
        assertEquals("3.33333333", answer("10 ÷ 3 ="))
        assertEquals("15.06666667", answer("45.20 ÷ 3 ="))
    }

    @Test
    fun percentWorksLikeAPhoneCalculator() {
        assertEquals("88", answer("80 + 10 % ="))
        assertEquals("72", answer("80 − 10 % ="))
        assertEquals("8.136", answer("45.20 × 18 % ="))
        assertEquals("0.50", answer("50 % ="))
    }

    @Test
    fun aDividedByZeroSaysSoAndKeepsTheSum() {
        val s = run("5 ÷ 0 =")
        assertEquals("Can't divide by zero", s.error)
        assertEquals("5 ÷ 0", s.expression)
        assertTrue(s.tape.isEmpty())
        assertNull(s.value)
        // Fix it and carry on.
        val fixed = run("< 2 =", s)
        assertNull(fixed.error)
        assertEquals(BigDecimal("2.5"), fixed.value?.stripTrailingZeros())
    }

    @Test
    fun answersGoOnTheTape() {
        val s = run("12.50 + 3.49 = 2 × 3 =")
        assertEquals(
            listOf(TapeLine("12.50 + 3.49", "15.99"), TapeLine("2 × 3", "6")),
            s.tape,
        )
        assertTrue(Calculator.clearTape(s).tape.isEmpty())
        // Clearing the sum leaves the tape alone.
        assertEquals(2, run("C", s).tape.size)
    }

    @Test
    fun anOperatorCarriesOnFromTheAnswerAndADigitStartsOver() {
        assertEquals("18", answer("5 + 4 = × 2 ="))
        val fresh = run("5 + 4 = 7")
        assertEquals("7", fresh.entry)
        assertNull(fresh.result)
    }

    @Test
    fun pressingAnotherOperatorChangesIt() {
        assertEquals("5 ×", run("5 + ×").expression)
        assertEquals("10", answer("5 + × 2 ="))
    }

    @Test
    fun aFinalOperatorIsIgnored() {
        val s = run("5 + 3 + =")
        assertEquals("8", s.value?.let { Calculator.show(it) })
        assertEquals("5 + 3", s.tape.single().expression)
    }

    @Test
    fun negativeNumbers() {
        assertEquals("-2", answer("− 5 + 3 ="))
        assertEquals("-15", answer("5 × − 3 ="))
        // After a minus, another minus just stays a minus.
        assertEquals("2", answer("5 − − 3 ="))
    }

    @Test
    fun oddTyping() {
        assertEquals("0.5", run(". 5").entry)
        assertEquals("1.5", run("1 . . 5").entry)
        assertEquals("7", run("0 0 7").entry)
        assertEquals("3", run("3 + =").tape.single().expression)
        // A sum with nothing in it does nothing.
        assertEquals(CalcState(), run("="))
        assertEquals(CalcState(), run("+"))
        assertEquals(CalcState(), run("<"))
        assertEquals(CalcState(), run("%"))
        // Stops at fifteen digits.
        assertEquals(15, run("1234567890123456789").entry.length)
    }

    @Test
    fun backspaceWalksBackThroughTheSum() {
        assertEquals("12", run("123 <").entry)
        assertEquals("5", run("5 + <").entry)
        assertEquals(emptyList<CalcTerm>(), run("5 + <").terms)
        assertEquals("5 + 1", run("5 + 12 <").expression)
        // After an answer, it clears the answer.
        assertNull(run("1 + 1 = <").value)
    }

    @Test
    fun theRunningValueShowsAsYouGo() {
        assertEquals(BigDecimal("15"), run("12 + 3").value?.stripTrailingZeros()?.setScale(0))
        assertEquals(BigDecimal("12"), run("12 +").value?.stripTrailingZeros()?.setScale(0))
        assertNull(CalcState().value)
    }

    @Test
    fun numbersAreGrouped() {
        assertEquals("1,234.50", Calculator.format("1234.50"))
        assertEquals("12.", Calculator.format("12."))
        assertEquals("-1,000", Calculator.format("-1000"))
        assertEquals("1,234,567 + 1", run("1234567 + 1").expression)
        assertEquals("1,234.50", Calculator.show(BigDecimal("1234.5")))
        assertEquals("1,234", Calculator.show(BigDecimal("1234")))
    }

    @Test
    fun theAnswerAsMoney() {
        assertEquals(1599L, run("12.50 + 3.49 =").cents)
        assertEquals(814L, run("45.20 × 18 % =").cents)
        assertEquals(1507L, run("45.20 ÷ 3 =").cents)
        assertNull(run("5 − 8 =").cents)
        assertNull(CalcState().cents)
    }
}
