package com.moblized.spendscan.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.roundToInt

class SegmentReaderTest {
    /** Draws a pump display: digits made of bars, the way the real ones are. */
    private class Display(
        val digitHeight: Int = 60,
        val slant: Double = 0.0,
        val lit: Int = 235,
        val background: Int = 30,
        /** Space between the bars of one digit. */
        val gap: Int = 2,
        val noise: Int = 0,
        val glare: Int = 0,
        val width: Int = 420,
        val height: Int = 300,
    ) {
        private val pixels = IntArray(width * height) { background }
        private val digitWidth = (digitHeight * 0.55).roundToInt()
        private val stroke = (digitHeight * 0.12).roundToInt().coerceAtLeast(2)
        private val spacing = (digitHeight * 0.22).roundToInt()

        private val bars = mapOf(
            '0' to "abcdef", '1' to "bc", '2' to "abdeg", '3' to "abcdg", '4' to "bcfg",
            '5' to "acdfg", '6' to "acdefg", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg",
        )

        private fun fill(x0: Int, y0: Int, x1: Int, y1: Int, baseline: Int) {
            for (y in y0 until y1) for (x in x0 until x1) {
                val sx = x + ((baseline - y) * slant).roundToInt()
                if (sx in 0 until width && y in 0 until height) pixels[y * width + sx] = lit
            }
        }

        private fun digit(ch: Char, left: Int, top: Int) {
            val w = digitWidth
            val h = digitHeight
            val t = stroke
            val bottom = top + h
            val half = top + h / 2
            for (bar in bars.getValue(ch)) when (bar) {
                'a' -> fill(left + t + gap, top, left + w - t - gap, top + t, bottom)
                'd' -> fill(left + t + gap, bottom - t, left + w - t - gap, bottom, bottom)
                'g' -> fill(left + t + gap, half - t / 2, left + w - t - gap, half - t / 2 + t, bottom)
                'f' -> fill(left, top + t + gap, left + t, half - t / 2 - gap, bottom)
                'b' -> fill(left + w - t, top + t + gap, left + w, half - t / 2 - gap, bottom)
                'e' -> fill(left, half - t / 2 + t + gap, left + t, bottom - t - gap, bottom)
                'c' -> fill(left + w - t, half - t / 2 + t + gap, left + w, bottom - t - gap, bottom)
            }
        }

        fun line(text: String, left: Int, top: Int): Display {
            var x = left
            for (ch in text) {
                when {
                    ch == '.' -> fill(x - spacing / 2 - stroke / 2, top + digitHeight - stroke, x - spacing / 2 + stroke / 2 + 1, top + digitHeight, top + digitHeight)
                    ch == ' ' -> x += digitWidth + spacing
                    else -> {
                        digit(ch, x, top)
                        x += digitWidth + spacing
                    }
                }
            }
            return this
        }

        fun gray(): Gray {
            var seed = 12345L
            val out = IntArray(width * height) { i ->
                var v = pixels[i]
                if (glare > 0) {
                    val x = i % width - width * 0.7
                    val y = i / width - height * 0.4
                    v += (glare * exp(-(x * x + y * y) / (2.0 * (width * 0.2) * (width * 0.2)))).roundToInt()
                }
                if (noise > 0) {
                    seed = (seed * 6364136223846793005L + 1442695040888963407L)
                    v += ((seed ushr 33) % (2 * noise + 1)).toInt() - noise
                }
                v.coerceIn(0, 255)
            }
            return Gray(width, height, out)
        }
    }

    private fun read(image: Gray) = SegmentReader.read(image).map { it.text }

    private fun pump(display: Display) = display
        .line("47.17", 30, 20)
        .line("12.448", 30, 110)
        .line("3.789", 30, 200)
        .gray()

    @Test
    fun readsLitDigitsThatLean() {
        assertEquals(listOf("47.17", "12.448", "3.789"), read(pump(Display(slant = 0.18))))
    }

    @Test
    fun readsDarkDigitsOnALightScreen() {
        assertEquals(listOf("47.17", "12.448", "3.789"), read(pump(Display(lit = 55, background = 175, noise = 10))))
    }

    @Test
    fun readsThroughGlareAndGrain() {
        assertEquals(
            listOf("47.17", "12.448", "3.789"),
            read(pump(Display(slant = 0.15, noise = 14, glare = 120))),
        )
    }

    @Test
    fun readsDigitsWhoseBarsRunTogether() {
        assertEquals(listOf("47.17", "12.448", "3.789"), read(pump(Display(gap = 0))))
    }

    @Test
    fun readsEveryDigit() {
        val d = Display(digitHeight = 50, width = 700, height = 200, slant = 0.1)
            .line("0123456789", 20, 20)
            .line("98765.43210", 20, 110)
            .gray()
        assertEquals(listOf("0123456789", "98765.43210"), read(d))
    }

    @Test
    fun aBigPhotoIsShrunkFirst() {
        val big = Display(digitHeight = 240, width = 1800, height = 1200, slant = 0.12, noise = 8)
            .line("47.17", 120, 80)
            .line("12.448", 120, 440)
            .line("3.789", 120, 800)
            .gray()
        assertEquals(listOf("47.17", "12.448", "3.789"), read(big))
    }

    @Test
    fun aBlankOrGrainyPictureGivesNothing() {
        assertEquals(emptyList<String>(), read(Display(noise = 30).gray()))
        assertEquals(emptyList<String>(), read(Gray(50, 50, IntArray(2500) { 128 })))
    }

    @Test
    fun whatItReadsIsEnoughForThePumpReader() {
        val rows = SegmentReader.read(pump(Display(slant = 0.18, noise = 10)))
        val r = PumpReader.parseDisplay(emptyList(), rows)
        assertEquals(4717L, r.totalCents)
        assertEquals(12_448L, r.fuel?.gallonsMilli)
        assertEquals(3_789L, r.fuel?.priceMilli)
        assertFalse(ReceiptField.TOTAL in r.unsure)
    }

    private fun resource(name: String): Gray {
        val stream = javaClass.getResourceAsStream("/$name")!!
        val bytes = (if (name.endsWith(".gz")) GZIPInputStream(stream) else stream).use { it.readBytes() }
        val w = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val h = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return Gray(w, h, IntArray(w * h) { bytes[4 + it].toInt() and 0xFF })
    }

    /**
     * A real pump, from a photo: white leaning digits on dark blue, with a face and a window reflected in the glass.
     * It's a small copy, cut off on the right, so only the digits that are whole can be asked for.
     */
    @Test
    fun readsARealPump() {
        assertEquals(listOf("47", "12.4"), read(resource("pump_lcd.gray")))
    }

    // The next four are drawn to look like photos of pumps: bars with pointed ends, the display turned a little,
    // labels printed beside the numbers, a frame, blur, grain and soft patches of glare.

    private fun pumpPhoto(name: String) = PumpReader.parseDisplay(emptyList(), SegmentReader.read(resource(name)))

    private fun assertPump(reading: ParsedReceipt, cents: Long, gallons: Long, price: Long) {
        assertEquals(cents, reading.totalCents)
        assertEquals(ParsedFuel(gallons, price), reading.fuel)
        assertFalse("adds up, so no need to check", ReceiptField.TOTAL in reading.unsure)
    }

    @Test
    fun readsDarkDigitsOnATiltedScreen() {
        assertEquals(listOf("113.15", "30.189", "3.748"), read(resource("pump_dark_digits.gray.gz")))
        assertPump(pumpPhoto("pump_dark_digits.gray.gz"), 11315, 30_189, 3_748)
    }

    @Test
    fun readsLitDigitsWithRowsOfDifferentSizes() {
        assertEquals(listOf("84.79", "28.122", "3.015"), read(resource("pump_lit_digits.gray.gz")))
        assertPump(pumpPhoto("pump_lit_digits.gray.gz"), 8479, 28_122, 3_015)
    }

    @Test
    fun barsThatAreOffButStillShowFaintlyArentRead() {
        assertPump(pumpPhoto("pump_ghost_bars.gray.gz"), 4270, 12_260, 3_483)
    }

    @Test
    fun unclearDigitsAreSettledByTheArithmetic() {
        // Two digits of the sale can't be told, and the 7 of the gallons has lost its top bar and reads as a 1.
        val rows = SegmentReader.read(resource("pump_unclear_digits.gray.gz"))
        assertEquals(listOf("??.06", "1.004", "4.720"), rows.map { it.text })
        assertPump(PumpReader.parseDisplay(emptyList(), rows), 3306, 7_004, 4_720)
    }
}
