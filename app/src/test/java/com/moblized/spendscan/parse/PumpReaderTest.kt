package com.moblized.spendscan.parse

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
}
