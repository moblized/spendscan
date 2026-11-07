package com.moblized.spendscan.paperless

import com.moblized.spendscan.data.Receipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class PaperlessConfigTest {
    private fun root(url: String) = PaperlessConfig(url, "t").root

    @Test
    fun tidiesUpTheAddress() {
        assertEquals("https://paperless.example.com", root("paperless.example.com"))
        assertEquals("https://paperless.example.com", root("https://paperless.example.com/"))
        assertEquals("https://paperless.example.com", root("https://paperless.example.com/api/"))
        assertEquals("https://paperless.example.com", root("https://paperless.example.com/dashboard"))
        assertEquals("https://paperless.example.com", root("https://paperless.example.com/documents/12/details"))
        assertEquals("http://192.168.1.20:30070", root("192.168.1.20:30070"))
        assertEquals("http://truenas.local:8000", root("truenas.local:8000"))
        // Paperless can live under a path of its own.
        assertEquals("https://example.com/paperless", root("https://example.com/paperless/dashboard"))
        assertEquals("https://apis.example.com", root("https://apis.example.com"))
        assertEquals("", root("  "))
        assertEquals("https://paperless.example.com/api", PaperlessConfig("paperless.example.com", "t").apiBase)
    }

    @Test
    fun onlyRunsWithAnAddressAndToken() {
        assertFalse(PaperlessConfig("", "token", on = true).isOn)
        assertFalse(PaperlessConfig("paperless.example.com", " ", on = true).isOn)
        assertFalse(PaperlessConfig("paperless.example.com", "token", on = false).isOn)
        assertTrue(PaperlessConfig("paperless.example.com", "token", on = true).isOn)
    }

    @Test
    fun warnsAboutPlainHttpOverTheInternet() {
        assertTrue(PaperlessConfig("http://paperless.example.com").sendsInTheClear)
        assertFalse(PaperlessConfig("http://192.168.1.20:8000").sendsInTheClear)
        assertFalse(PaperlessConfig("paperless.example.com").sendsInTheClear)
    }

    @Test
    fun aReceiptBecomesADocument() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val r = Receipt(store = "Trader Joe's #552", dateEpochDay = LocalDate.of(2026, 11, 5).toEpochDay(), totalCents = 8412, categoryId = 1)
            val d = PaperlessDetails.of(r, "Groceries", "USD")
            assertEquals("Trader Joe's #552 $84.12", d.title)
            assertEquals("Trader Joe's #552", d.correspondent)
            assertEquals("Groceries", d.tag)
            assertEquals("USD84.12", d.total)
            assertEquals(LocalDate.of(2026, 11, 5), d.created)
            assertEquals("2026-11-05 Trader Joe_s _552.pdf", d.fileName)

            assertEquals("84.12", PaperlessDetails.of(r, null, null).total)
            assertEquals(null, PaperlessDetails.of(r, " ", "USD").tag)
            assertEquals("USD-5.00", PaperlessDetails.of(r.copy(totalCents = -500), null, "USD").total)
            assertEquals("2026-11-05 Cafe.pdf", PaperlessDetails.fileName(LocalDate.of(2026, 11, 5), "Café"))
            assertEquals("2026-11-05 Receipt.pdf", PaperlessDetails.fileName(LocalDate.of(2026, 11, 5), "???"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
