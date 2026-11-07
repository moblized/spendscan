package com.moblized.spendscan.paperless

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ReceiptPdfTest {
    companion object {
        /** A real JPEG, made the way a camera would. */
        fun jpeg(width: Int, height: Int, grey: Boolean = false): ByteArray {
            val image = BufferedImage(width, height, if (grey) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            // Some lines of print.
            g.color = Color.BLACK
            for (y in 10 until height - 10 step 30) g.fillRect(8, y, width / 2, 6)
            g.dispose()
            return ByteArrayOutputStream().use {
                ImageIO.write(image, "jpg", it)
                it.toByteArray()
            }
        }

        fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use {
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
            it.toByteArray()
        }
    }

    @Test
    fun readsTheSizeOfAJpeg() {
        assertEquals(JpegInfo(320, 900, 3), Jpeg.info(jpeg(320, 900)))
        assertEquals(JpegInfo(64, 48, 1), Jpeg.info(jpeg(64, 48, grey = true)))
    }

    @Test
    fun anythingElseNeedsReEncodingFirst() {
        assertNull(Jpeg.info(png(10, 10)))
        assertNull(Jpeg.info(ByteArray(0)))
        assertNull(Jpeg.info("not a picture".toByteArray()))
        // Cut off before the frame header.
        assertNull(Jpeg.info(jpeg(40, 40).copyOf(20)))
    }

    @Test
    fun pageSizesComeFromThePhoto() {
        assertEquals("72.00", ReceiptPdf.points(300))
        assertEquals("360.00", ReceiptPdf.points(1500))
        assertEquals("0.24", ReceiptPdf.points(1))
    }

    private fun page(bytes: ByteArray) = ReceiptPdf.Page(bytes, Jpeg.info(bytes)!!)

    @Test
    fun makesAPdfWithAPagePerPhoto() {
        val first = jpeg(300, 900)
        val second = jpeg(600, 300, grey = true)
        val pdf = ReceiptPdf.write(listOf(page(first), page(second)))
        val text = String(pdf, Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 72.00 216.00]"))
        assertTrue(text.contains("/MediaBox [0 0 144.00 72.00]"))
        assertTrue(text.contains("/ColorSpace /DeviceGray"))

        // The photos go in untouched.
        val at = text.indexOf("/Length ${first.size} >>\nstream\n") + "/Length ${first.size} >>\nstream\n".length
        assertArrayEquals(first, pdf.copyOfRange(at, at + first.size))

        // Every entry in the cross-reference table points at its object, so readers can open it.
        val xref = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.startsWith("xref\n", xref))
        val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(8, entries.size)
        entries.forEachIndexed { i, line ->
            val offset = line.take(10).toInt()
            assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj\n", offset))
            assertEquals(20, line.length + 1)
        }
    }

    @Test
    fun theSamePhotosAlwaysMakeTheSameFile() {
        val photo = jpeg(200, 500)
        assertArrayEquals(ReceiptPdf.write(listOf(page(photo))), ReceiptPdf.write(listOf(page(photo))))
    }
}
