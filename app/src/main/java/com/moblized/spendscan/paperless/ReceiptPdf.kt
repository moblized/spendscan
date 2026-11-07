package com.moblized.spendscan.paperless

import java.io.ByteArrayOutputStream

/** What a PDF needs to know about a JPEG to show it. */
data class JpegInfo(val width: Int, val height: Int, val components: Int)

object Jpeg {
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /**
     * Reads the picture's size from its frame header. Null when it isn't a JPEG that every PDF reader can show as it
     * is (8-bit greyscale or colour, baseline or progressive), so it has to be re-encoded first.
     */
    fun info(bytes: ByteArray): JpegInfo? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 3 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> {
                    i++
                    continue
                }
                marker == 0x01 || marker in 0xD0..0xD8 -> {
                    i += 2
                    continue
                }
                // The picture data or the end came before any frame header.
                marker == 0xD9 || marker == 0xDA -> return null
            }
            val length = u16(bytes, i + 2)
            if (length < 2) return null
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (i + 9 >= bytes.size) return null
                val precision = bytes[i + 4].toInt() and 0xFF
                val height = u16(bytes, i + 5)
                val width = u16(bytes, i + 7)
                val components = bytes[i + 9].toInt() and 0xFF
                val readable = marker == 0xC0 || marker == 0xC1 || marker == 0xC2
                if (!readable || precision != 8 || width == 0 || height == 0 || (components != 1 && components != 3)) return null
                return JpegInfo(width, height, components)
            }
            i += 2 + length
        }
        return null
    }
}

/**
 * Puts receipt photos into one PDF, a page each, without re-compressing them. The same photos always make exactly the
 * same file, which is what lets Paperless spot one it already has.
 */
object ReceiptPdf {
    /** Pages are sized as if scanned at this resolution, which comes out near a real receipt's size. */
    const val DPI = 300

    class Page(val jpeg: ByteArray, val info: JpegInfo)

    fun write(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun begin(number: Int) {
            offsets += out.size()
            check(offsets.size == number)
            ascii("$number 0 obj\n")
        }

        ascii("%PDF-1.4\n")
        // Marks the file as binary for anything that guesses.
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        // 1 is the catalog, 2 the page list, then three objects a page: the page, what it draws, and its photo.
        begin(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2)
        ascii("<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }}] /Count ${pages.size} >>\nendobj\n")
        pages.forEachIndexed { k, page ->
            val w = points(page.info.width)
            val h = points(page.info.height)
            begin(3 + 3 * k)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $w $h] /Resources << /XObject << /Im0 ${5 + 3 * k} 0 R >> >> " +
                    "/Contents ${4 + 3 * k} 0 R >>\nendobj\n",
            )
            val draw = "q $w 0 0 $h 0 0 cm /Im0 Do Q"
            begin(4 + 3 * k)
            ascii("<< /Length ${draw.length} >>\nstream\n$draw\nendstream\nendobj\n")
            begin(5 + 3 * k)
            val colours = if (page.info.components == 1) "/DeviceGray" else "/DeviceRGB"
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.info.width} /Height ${page.info.height} /ColorSpace $colours " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
            )
            out.write(page.jpeg)
            ascii("\nendstream\nendobj\n")
        }

        val xref = out.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii("${it.toString().padStart(10, '0')} 00000 n \n") }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** A side in pixels as points (1/72 inch), to two places. Whole-number arithmetic, so no locale can change it. */
    fun points(px: Int): String {
        val hundredths = (px.toLong() * 7200 + DPI / 2) / DPI
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
