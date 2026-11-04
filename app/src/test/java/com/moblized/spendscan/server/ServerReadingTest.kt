package com.moblized.spendscan.server

import com.moblized.spendscan.parse.Chains
import com.moblized.spendscan.parse.ReceiptField
import com.moblized.spendscan.parse.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ServerReadingTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun addressesBecomeTheApiRoot() {
        fun base(url: String) = ServerConfig(url).apiBase
        assertEquals("http://192.168.1.20:11434/v1", base("192.168.1.20:11434"))
        assertEquals("http://192.168.1.20:11434/v1", base("http://192.168.1.20:11434"))
        assertEquals("http://192.168.1.20:11434/v1", base("http://192.168.1.20:11434/"))
        assertEquals("http://192.168.1.20:1234/v1", base("http://192.168.1.20:1234/v1"))
        assertEquals("https://llm.moblized.org/v1", base(" https://llm.moblized.org "))
        assertEquals("http://x:5080/v1", base("x:5080"))
        // Pasted the whole endpoint, or a server with its own path.
        assertEquals("http://x:1234/v1", base("http://x:1234/v1/chat/completions"))
        assertEquals("http://x:3000/api", base("http://x:3000/api"))
        assertEquals("", base("  "))
    }

    @Test
    fun aServerNeedsAnAddressAndAModel() {
        assertTrue(ServerConfig("x", model = "m").isSet)
        assertFalse(ServerConfig("x").isSet)
        assertFalse(ServerConfig(model = "m").isSet)
    }

    @Test
    fun warnsAboutPlainHttpBeyondHome() {
        fun clear(url: String) = ServerConfig(url).sendsInTheClear
        assertFalse(clear("http://192.168.1.20:11434"))
        assertFalse(clear("10.0.0.5:1234"))
        assertFalse(clear("http://172.20.1.1:1234"))
        assertFalse(clear("http://100.101.1.1:1234"))
        assertFalse(clear("http://gaming-pc:11434"))
        assertFalse(clear("http://gaming-pc.local:11434"))
        assertFalse(clear("https://llm.moblized.org"))
        assertTrue(clear("http://llm.moblized.org"))
        assertTrue(clear("http://8.8.8.8:11434"))
        assertTrue(clear("http://172.32.0.1:1234"))
    }

    @Test
    fun modelsAreTidied() {
        assertEquals(
            listOf("gemma3:12b", "qwen3-vl:8b", "llama3.2:latest"),
            ServerModels.tidy(listOf("nomic-embed-text", "llama3.2:latest", "qwen3-vl:8b", "gemma3:12b", "llama3.2:latest")),
        )
        assertTrue(ServerModels.looksLikeVision("qwen2.5vl:7b"))
        assertTrue(ServerModels.looksLikeVision("llama3.2-vision"))
        assertFalse(ServerModels.looksLikeVision("llama3.2:latest"))
    }

    private val phone = ReceiptParser.parse(
        listOf("KROGER", "MILK 3.49", "BREAD 2.99", "SUBTOTAL 6.48", "TAX 0.45", "TOTAL 6.93", "VISA 6.93", "11/02/2026"),
        today,
    )

    private fun server(
        store: String? = "Kroger",
        date: LocalDate? = LocalDate.of(2026, 11, 2),
        total: Long? = 693,
        subtotal: Long? = 648,
        tax: Long? = 45,
        items: List<ServerItem> = listOf(ServerItem("2% Milk", 1.0, 349), ServerItem("Bread", 1.0, 299)),
        category: String? = "Groceries",
    ) = ServerReceipt(store, date, total, subtotal, tax, null, null, "Visa 4242", category, items)

    @Test
    fun theModelsReadingWinsWhenItChecksOut() {
        val r = ServerMerge.merge(server(), phone, today)
        assertEquals("Kroger", r.store)
        assertEquals(693L, r.totalCents)
        assertEquals(listOf("2% Milk", "Bread"), r.items.map { it.name })
        assertEquals("Visa 4242", r.payment)
        assertEquals("Groceries", r.suggestedCategory)
        assertTrue(r.unsure.isEmpty())
        // The phone's text stays available for checking.
        assertTrue(r.rawText.contains("KROGER"))
    }

    @Test
    fun gapsAreFilledFromThePhone() {
        val r = ServerMerge.merge(server(store = null, date = null, total = null, subtotal = null, tax = null, items = emptyList(), category = null), phone, today)
        assertEquals("Kroger", r.store)
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(693L, r.totalCents)
        assertEquals(45L, r.taxCents)
        assertEquals(listOf("MILK", "BREAD"), r.items.map { it.name })
        assertEquals(Chains.GROCERIES, r.suggestedCategory)
    }

    @Test
    fun aTotalNothingBacksUpGetsFlagged() {
        // Doesn't add up, the phone disagrees, and it isn't anywhere in the phone's text.
        val r = ServerMerge.merge(server(total = 999), phone, today)
        assertEquals(999L, r.totalCents)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun aTotalIsBackedUpByAnyOneCheck() {
        // Adds up with no help from the phone.
        assertTrue(ServerMerge.merge(server(), null, today).unsure.isEmpty())
        // Doesn't add up, but it's printed in the phone's text.
        val odd = ServerMerge.merge(server(subtotal = 100, items = emptyList()), phone, today)
        assertFalse(ReceiptField.TOTAL in odd.unsure)
        // Thousands separators in the text still match.
        val big = ReceiptParser.parse(listOf("BEST BUY", "TV 1,799.99", "TOTAL 1,930.49"), today)
        assertFalse(ReceiptField.TOTAL in ServerMerge.merge(server(total = 193049, subtotal = 5, items = emptyList()), big, today).unsure)
    }

    @Test
    fun noPhoneReadingAndNoArithmeticMeansCheckIt() {
        val r = ServerMerge.merge(server(subtotal = null, tax = null, items = emptyList()), null, today)
        assertTrue(ReceiptField.TOTAL in r.unsure)
    }

    @Test
    fun datesThatDisagreeOrCantBeRightGetFlagged() {
        val differ = ServerMerge.merge(server(date = LocalDate.of(2026, 11, 3)), phone, today)
        assertEquals(LocalDate.of(2026, 11, 3), differ.date)
        assertTrue(ReceiptField.DATE in differ.unsure)
        // A date from the future falls back to the phone's.
        val future = ServerMerge.merge(server(date = LocalDate.of(2031, 1, 1)), phone, today)
        assertEquals(LocalDate.of(2026, 11, 2), future.date)
        assertFalse(ReceiptField.DATE in future.unsure)
        assertTrue(ReceiptField.DATE in ServerMerge.merge(server(date = null), null, today).unsure)
    }

    @Test
    fun storeNamesAreTidied() {
        assertEquals("Walmart", ServerMerge.merge(server(store = "WAL-MART SUPERCENTER"), phone, today).store)
        assertEquals("Blue Heron Books", ServerMerge.merge(server(store = "BLUE HERON BOOKS"), phone, today).store)
        assertEquals("Joe's Pizza", ServerMerge.merge(server(store = " Joe's Pizza "), phone, today).store)
    }

    @Test
    fun itemsWithNoNameOrPriceAreDropped() {
        val r = ServerMerge.merge(server(items = listOf(ServerItem(" ", 1.0, 100), ServerItem("Free", 1.0, 0), ServerItem("Tea", 2.0, 300))), phone, today)
        assertEquals(listOf("Tea"), r.items.map { it.name })
        assertEquals(2.0, r.items.single().quantity, 0.0)
    }

    @Test
    fun thePromptOffersYourCategoriesAndThePhonesText() {
        val withPhoto = ServerPrompt.user(true, "KROGER 6.93", listOf("Groceries", "Vet"), today)
        assertTrue(withPhoto.contains("Groceries, Vet"))
        assertTrue(withPhoto.contains("trust the photo"))
        assertTrue(withPhoto.contains("KROGER 6.93"))
        assertTrue(withPhoto.contains("2026-11-04"))
        assertTrue(ServerPrompt.user(false, "KROGER", emptyList(), today).contains("You only have this text"))
        assertFalse(ServerPrompt.user(true, null, emptyList(), today).contains("The phone also read"))
    }

    @Test
    fun anEmptyReadingCountsAsNothing() {
        assertFalse(ServerReceipt(null, null, null, null, null, null, null, null, null, emptyList()).hasAnything)
        assertNull(ServerConfig("x").model.takeIf { it.isNotBlank() })
    }
}
