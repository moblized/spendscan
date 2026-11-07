package com.moblized.spendscan.paperless

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class ReceiptPdfTest {
    companion object {
        /**
         * A JPEG's headers around a little stand-in picture data, at any size. Android's unit tests have no image
         * encoder, and the PDF never decodes the picture.
         */
        fun jpeg(width: Int, height: Int, grey: Boolean = false, frame: Int = 0xC0): ByteArray {
            val out = ByteArrayOutputStream()
            fun bytes(vararg b: Int) = b.forEach { out.write(it) }
            fun u16(v: Int) = bytes((v shr 8) and 0xFF, v and 0xFF)
            bytes(0xFF, 0xD8)
            // JFIF header
            bytes(0xFF, 0xE0)
            u16(16)
            "JFIF".forEach { out.write(it.code) }
            bytes(0, 1, 1, 0)
            u16(72)
            u16(72)
            bytes(0, 0)
            // A quantisation table
            bytes(0xFF, 0xDB)
            u16(67)
            bytes(0)
            repeat(64) { out.write(1) }
            // The frame header, with the size
            val components = if (grey) 1 else 3
            bytes(0xFF, frame)
            u16(8 + 3 * components)
            bytes(8)
            u16(height)
            u16(width)
            bytes(components)
            repeat(components) { bytes(it + 1, 0x11, 0) }
            // The scan, and the end
            bytes(0xFF, 0xDA)
            u16(6 + 2 * components)
            bytes(components)
            repeat(components) { bytes(it + 1, 0) }
            bytes(0, 63, 0)
            repeat(32) { out.write(0x55) }
            bytes(0xFF, 0xD9)
            return out.toByteArray()
        }

        /** Real photos, 32 x 48 in colour and 24 x 16 in grey (progressive), as an ordinary encoder makes them. */
        val REAL_COLOUR: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYn" +
            "KSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgo" +
            "KCgoKCgoKCj/wAARCAAwACADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
            "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
            "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
            "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
            "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
            "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
            "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD6poryr46f8LP/AOJJ/wAKq/6b/b/+PX/pn5f+v/7afd/HtXlX/GTv+f7LoA+qqK+Vf+Mn" +
            "f8/2XX0r4T/tT/hFdG/4SH/kNfYoft33f9fsHmfc+X72fu8enFAGrRWH4n8WaJ4X+zf27e/ZftO7yv3Tvu24z91Tj7w6+tYX/C2f" +
            "BX/Qa/8AJWf/AOIqHUinZs6aeDxFWPPTpyafVJs7miub8N+N/D3iW+ks9E1D7TcpGZmTyJEwgIBOWUDqw/Oukqk1LVGVSlOlLlqR" +
            "afmrGH4n8J6J4o+zf27Zfavs27yv3rpt3Yz91hn7o6+lYX/CpvBX/QF/8mp//i67mipdOLd2jWnjMRSjyU6kkl0TaOb8N+CPD3hq" +
            "+kvNE0/7NcvGYWfz5HyhIJGGYjqo/KukooqklHRGVSrOrLmqSbfm7n//2Q==",
        )
        val REAL_GREY_PROGRESSIVE: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYn" +
            "KSopGR8tMC0oMCUoKSj/wgALCAAQABgBAREA/8QAFgABAQEAAAAAAAAAAAAAAAAABAMG/9oACAEBAAAAAdEV0yu//8QAGBABAQEB" +
            "AQAAAAAAAAAAAAAABAUDABX/2gAIAQEAAQUCRVGbb3J3EolXoiUNO3hzuJOKTT//xAAeEAABBAEFAAAAAAAAAAAAAAABAAIDEhEE" +
            "MTSi0f/aAAgBAQAGPwJ0U01ZG7ipXI6O8RZp5buAzipCdLNDaR25sVx+7vUX6eKjiMZsSv/EABsQAAICAwEAAAAAAAAAAAAAAAEh" +
            "EUEAEGEx/9oACAEBAAE/IVRgziRNDugH9l+AhY6MVGCOYEUeaAf2X4CNngz/2gAIAQEAAAAQb//EABsQAQEAAgMBAAAAAAAAAAAA" +
            "AAEhETEAEEFh/9oACAEBAAE/EPnRjIFUYGPvVmRKkiKjkjcN3nzoxkCCEBDzqzIlSxNQwxum5z//2Q==",
        )

        /** The start of a PNG. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 10)
    }

    @Test
    fun readsTheSizeOfAJpeg() {
        assertEquals(JpegInfo(32, 48, 3), Jpeg.info(REAL_COLOUR))
        assertEquals(JpegInfo(24, 16, 1), Jpeg.info(REAL_GREY_PROGRESSIVE))
        assertEquals(JpegInfo(320, 900, 3), Jpeg.info(jpeg(320, 900)))
        assertEquals(JpegInfo(64, 48, 1), Jpeg.info(jpeg(64, 48, grey = true)))
    }

    @Test
    fun anythingElseNeedsReEncodingFirst() {
        assertNull(Jpeg.info(PNG))
        // Lossless JPEG, which hardly any PDF reader shows.
        assertNull(Jpeg.info(jpeg(40, 40, frame = 0xC3)))
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
        val first = REAL_COLOUR
        val second = jpeg(600, 300, grey = true)
        val pdf = ReceiptPdf.write(listOf(page(first), page(second)))
        val text = String(pdf, Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 7.68 11.52]"))
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
