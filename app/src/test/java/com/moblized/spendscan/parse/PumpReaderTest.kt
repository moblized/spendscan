package com.moblized.spendscan.parse

import com.moblized.spendscan.data.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpReaderTest {
    private fun read(vararg rows: String) = PumpReader.parse(rows.toList())

    private fun assertRead(r: ParsedReceipt, total: Long, gallons: Long, price: Long) {
        assertEquals(total, r.totalCents)
        assertEquals(ParsedFuel(gallons, price), r.fuel)
        assertFalse(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun aLabeledDisplay() {
        val r = read("TOTAL SALE", "$ 42.70", "GALLONS", "12.345", "PRICE PER GALLON", "3.459")
        assertRead(r, 4270, 12345, 3459)
        assertEquals(Chains.AUTO, r.suggestedCategory)
    }

    @Test
    fun noLabelsAtAll() {
        assertRead(read("42.70", "12.345", "3.459"), 4270, 12345, 3459)
        assertRead(read("84.20 22.400", "3.759"), 8420, 22400, 3759)
        // In any order.
        assertRead(read("3.459", "42.70", "12.345"), 4270, 12345, 3459)
    }

    @Test
    fun theDecimalPointWasLost() {
        assertRead(read("4270", "12345", "3459"), 4270, 12345, 3459)
        assertRead(read("42 70", "12 345", "3 459"), 4270, 12345, 3459)
        assertRead(read("42:70", "12:345", "3:459"), 4270, 12345, 3459)
    }

    @Test
    fun digitalDigitsMisreadAsLetters() {
        assertRead(read("42.7O", "I2.345", "3.4S9"), 4270, 12345, 3459)
        assertRead(read("BO.OO", "2O.OOO", "4.OOO"), 8000, 20000, 4000)
    }

    @Test
    fun otherNumbersOnThePumpAreIgnored() {
        // Grade prices, octane numbers, the pump number and a phone number, with the real three among them.
        val r = read(
            "SHELL",
            "PUMP 7",
            "87 REGULAR 3.459",
            "89 PLUS 3.659",
            "93 PREMIUM 3.859",
            "SALE 45.17",
            "GAL 12.345",
            "Call 1-800-555-0100",
        )
        assertRead(r, 4517, 12345, 3659)
        assertEquals("Shell", r.store)
    }

    @Test
    fun aPriceMissingItsLastDigit() {
        // Prices often end in a small 9 that doesn't get read.
        assertRead(read("42.70", "12.345", "3.45"), 4270, 12345, 3450)
    }

    @Test
    fun aRoundFillIsStillSortedOut() {
        // $50 of fuel: which is gallons and which is price, when both fit? Words decide.
        assertRead(read("Sale 50.00", "Gallons 5.000", "Price 10.000"), 5000, 5000, 10000)
        // With no words, gallons are usually the bigger number.
        assertRead(read("50.00", "12.500", "4.000"), 5000, 12500, 4000)
    }

    @Test
    fun whenTheMathDoesntFitItAsksForACheck() {
        // A misread digit: it's a best guess from the words.
        val r = read("SALE 42.70", "GALLONS 19.345", "PRICE 3.459")
        assertEquals(4270L, r.totalCents)
        assertEquals(ParsedFuel(19345, 3459), r.fuel)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun partOfTheDisplay() {
        // The sale and gallons are enough; the app works the price out.
        val two = read("SALE 42.70", "GALLONS 12.345")
        assertEquals(4270L, two.totalCents)
        assertEquals(ParsedFuel(12345, null), two.fuel)
        assertTrue(ReceiptField.TOTAL in two.unsure)
        // Gallons and price give the total.
        val other = read("GALLONS 12.345", "PRICE 3.459")
        assertEquals(4270L, other.totalCents)
        // Only the sale.
        val one = read("SALE 42.70")
        assertEquals(4270L, one.totalCents)
        assertNull(one.fuel)
    }

    @Test
    fun noWordsAndNoMatchStillTakesABestGuess() {
        val r = read("42.70", "19.345", "3.459")
        assertEquals(4270L, r.totalCents)
        assertEquals(ParsedFuel(19345, 3459), r.fuel)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun aPhotoWithNothingUseful() {
        val r = read("Please pay inside", "Thank you")
        assertNull(r.totalCents)
        assertNull(r.fuel)
        assertNull(r.store)
        assertTrue(PumpReader.parse(emptyList()).unsure.contains(ReceiptField.TOTAL))
    }

    @Test
    fun theStationNeedsConfirmingUnlessItsOnThePump() {
        assertTrue(ReceiptField.STORE in read("42.70", "12.345", "3.459").unsure)
        assertFalse(ReceiptField.STORE in read("Exxon", "42.70", "12.345", "3.459").unsure)
    }

    // What the digit reader hands over: its best reading of each row, and every reading the photo allows.

    private fun row(text: String, vararg others: String) = DigitRow(text, if ('?' in text) others.toList() else listOf(text) + others)

    private fun display(vararg rows: DigitRow, text: List<String> = emptyList()) = PumpReader.parseDisplay(text, rows.toList())

    @Test
    fun aDigitGlareLeftOpenIsSettledByWhatAddsUp() {
        val r = display(row("47.1?", "47.13", "47.17", "47.19"), row("12.448"), row("3.?89", "3.189", "3.789"))
        assertEquals(4717L, r.totalCents)
        assertEquals(ParsedFuel(12_448, 3_789), r.fuel)
        assertFalse(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun oneBarReadWrongIsMended() {
        // The top bar of the 7 in the price was lost, leaving a 1. Nothing adds up until it's put back.
        val r = display(row("47.17"), row("12.448"), row("3.189"))
        assertEquals(4717L, r.totalCents)
        assertEquals(ParsedFuel(12_448, 3_789), r.fuel)
        assertFalse(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun missingDecimalPointsAreWorkedOut() {
        val r = display(row("4717"), row("12448"), row("3789"))
        assertEquals(4717L, r.totalCents)
        assertEquals(ParsedFuel(12_448, 3_789), r.fuel)
        assertFalse(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun aDisplayWithNoPriceStillGivesTheSaleAndGallons() {
        for (r in listOf(display(row("47.17"), row("12.448")), display(row("4717"), row("12448")))) {
            assertEquals(4717L, r.totalCents)
            assertEquals(ParsedFuel(12_448, null), r.fuel)
            // Nothing to check it against, so it's marked for a look.
            assertTrue(ReceiptField.TOTAL in r.unsure)
        }
    }

    @Test
    fun gallonsComeAboveThePrice() {
        // 2.809 x 4.381 and 4.381 x 2.809 both come to 12.31. The row order says which is which.
        val r = display(row("12.31"), row("2.809"), row("4.381"))
        assertEquals(ParsedFuel(2_809, 4_381), r.fuel)
        assertEquals(ParsedFuel(12_448, 3_789), display(row("47.17"), row("12.448"), row("3.789")).fuel)
    }

    @Test
    fun aMisreadingDoesntGetToAddUpByLuck() {
        // Off by a cent or two is no pump's sum, and neither are rounder numbers than a pump shows.
        assertTrue(ReceiptField.TOTAL in display(row("47.17"), row("12.458"), row("3.789")).unsure)
        assertTrue(ReceiptField.TOTAL in display(row("1??", "110", "170"), row("199"), row("3.438")).unsure)
        // Two changes at once could be made to fit almost anything, so only one is ever tried.
        assertTrue(ReceiptField.TOTAL in display(row("47.11"), row("12.448"), row("3.189")).unsure)
    }

    @Test
    fun theTextReadersLabelsAndBrandStillCount() {
        val r = display(row("47.17"), row("12.448"), row("3.789"), text = listOf("Shell", "DIESEL", "THIS SALE", "GALLONS"))
        assertEquals("Shell", r.store)
        assertEquals(ParsedFuel(12_448, 3_789, FuelType.DIESEL), r.fuel)
        assertEquals(4717L, r.totalCents)
    }

    @Test
    fun noDigitsLeavesItToTheText() {
        assertEquals(PumpReader.parse(listOf("SALE 42.70", "GALLONS 12.345", "PRICE 3.459")), PumpReader.parseDisplay(listOf("SALE 42.70", "GALLONS 12.345", "PRICE 3.459"), emptyList()))
    }
}
