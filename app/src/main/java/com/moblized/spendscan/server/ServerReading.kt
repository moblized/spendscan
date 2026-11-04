package com.moblized.spendscan.server

import com.moblized.spendscan.data.Money
import com.moblized.spendscan.parse.Chains
import com.moblized.spendscan.parse.ParsedItem
import com.moblized.spendscan.parse.ParsedReceipt
import com.moblized.spendscan.parse.ReceiptField
import com.moblized.spendscan.parse.ReceiptParser
import java.time.LocalDate
import kotlin.math.abs

/** Where your language model runs. Works with anything that speaks the OpenAI API: Ollama, LM Studio, llama.cpp, vLLM. */
data class ServerConfig(val url: String = "", val key: String = "", val model: String = "") {
    val isSet: Boolean get() = url.isNotBlank() && model.isNotBlank()

    /** The API's root. "192.168.1.20:11434" becomes "http://192.168.1.20:11434/v1"; an address with its own path stays as it is. */
    val apiBase: String
        get() {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.contains("://")) u = "http://$u"
            for (suffix in listOf("/chat/completions", "/models")) u = u.removeSuffix(suffix)
            return if (u.substringAfter("://").contains('/')) u else "$u/v1"
        }

    /** Plain http to somewhere beyond the home network, where anyone along the way could read the photos. */
    val sendsInTheClear: Boolean
        get() {
            val base = apiBase
            if (!base.startsWith("http://")) return false
            val host = base.removePrefix("http://").substringBefore('/').substringBeforeLast(':').lowercase()
            return !isPrivateHost(host)
        }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || !host.contains('.') || host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home.arpa") || host.endsWith(".ts.net")
        ) return true
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return false
        val a = parts[0]!!
        val b = parts[1]!!
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }
}

object ServerModels {
    private val VISION = Regex("""vl\b|vl[:-]|vision|llava|gemma[34]|pixtral|moondream|minicpm|paligemma|glm-?4v|ocr|smolvlm|internvl""", RegexOption.IGNORE_CASE)
    private val NOT_CHAT = Regex("""embed|rerank|whisper|tts""", RegexOption.IGNORE_CASE)

    fun looksLikeVision(name: String) = VISION.containsMatchIn(name)

    /** Leaves out models that can't chat, and puts the ones likely to see photos first. */
    fun tidy(names: List<String>): List<String> =
        names.filterNot { NOT_CHAT.containsMatchIn(it) }.distinct().sortedWith(compareBy({ !looksLikeVision(it) }, { it.lowercase() }))
}

data class ServerItem(val name: String, val quantity: Double, val amountCents: Long)

/** What the model said was on the receipt. */
data class ServerReceipt(
    val store: String?,
    val date: LocalDate?,
    val totalCents: Long?,
    val subtotalCents: Long?,
    val taxCents: Long?,
    val tipCents: Long?,
    val discountCents: Long?,
    val payment: String?,
    val category: String?,
    val items: List<ServerItem>,
) {
    val hasAnything: Boolean get() = !store.isNullOrBlank() || totalCents != null || items.isNotEmpty()
}

object ServerMerge {
    private fun close(a: Long?, b: Long?) = a != null && b != null && abs(a - b) <= 2

    /**
     * Prefers the model's reading and fills gaps from the phone's. A model can write a confident wrong number, so the
     * total is only left unmarked when something backs it up: it adds up, the phone read the same, or it's in the phone's text.
     */
    fun merge(server: ServerReceipt, phone: ParsedReceipt?, today: LocalDate): ParsedReceipt {
        val rawText = phone?.rawText.orEmpty()
        val name = server.store?.trim()?.takeIf { it.isNotEmpty() }
        val chain = name?.let { Chains.find(listOf(it)) }
        val store = chain?.name ?: name?.let(ReceiptParser::titleCase) ?: phone?.store

        val serverDate = server.date?.takeIf { !it.isBefore(today.minusYears(10)) && !it.isAfter(today.plusDays(1)) }
        val date = serverDate ?: phone?.date
        val datesDisagree = serverDate != null && phone?.date != null && serverDate != phone.date

        val items = server.items.filter { it.name.isNotBlank() && it.amountCents > 0 }
            .map { ParsedItem(it.name.trim(), it.quantity, it.amountCents) }
            .ifEmpty { phone?.items.orEmpty() }

        val total = server.totalCents ?: phone?.totalCents
        val subtotal = server.subtotalCents ?: phone?.subtotalCents
        val tax = server.taxCents ?: phone?.taxCents
        val tip = server.tipCents ?: phone?.tipCents
        val discount = server.discountCents ?: phone?.discountCents

        val base = subtotal ?: items.sumOf { it.amountCents }.takeIf { items.isNotEmpty() }
        val withExtras = base?.plus(tax ?: 0)?.plus(tip ?: 0)
        val addsUp = close(total, withExtras) || close(total, withExtras?.minus(discount ?: 0))
        val onPaper = total != null && rawText.replace(",", "").contains(Money.plain(total))
        val backedUp = addsUp || onPaper || close(total, phone?.totalCents)

        return ParsedReceipt(
            store = store,
            date = date,
            totalCents = total,
            subtotalCents = subtotal,
            taxCents = tax,
            tipCents = tip,
            discountCents = discount,
            payment = server.payment?.trim()?.takeIf { it.isNotEmpty() } ?: phone?.payment,
            items = items,
            suggestedCategory = server.category?.trim()?.takeIf { it.isNotEmpty() } ?: chain?.category ?: phone?.suggestedCategory,
            unsure = buildSet {
                if (store == null) add(ReceiptField.STORE)
                if (date == null || datesDisagree) add(ReceiptField.DATE)
                if (!backedUp) add(ReceiptField.TOTAL)
            },
            rawText = rawText,
        )
    }
}

object ServerPrompt {
    const val SYSTEM = "You read store receipts and report exactly what they say as JSON."

    fun user(hasPhoto: Boolean, phoneText: String?, categories: List<String>, today: LocalDate): String = buildString {
        appendLine("Read this receipt and fill in the JSON.")
        appendLine()
        appendLine("""- store: the business name, the way a person would say it.""")
        appendLine("""- date: the purchase date as YYYY-MM-DD. US receipts print the month first. Today is $today.""")
        appendLine("""- total: the final amount charged, including tax and any tip written in. Never use cash handed over, change, savings, rewards balances or printed tip suggestions.""")
        appendLine("""- subtotal, tax, tip, discount: only if printed. discount is a positive number.""")
        appendLine("""- payment: how it was paid, like "Visa 4242", "Debit" or "Cash".""")
        appendLine("""- category: the best fit from this list: ${categories.joinToString(", ")}.""")
        appendLine("""- items: everything bought, in order. name is as printed. quantity is how many, and 1 if not shown or if sold by weight. amount is the price of that whole line as a positive number. Leave out tax, totals, payments and discounts.""")
        appendLine("""- Money is a plain number like 12.99. Use null for anything not printed or that you can't read. Don't guess.""")
        if (phoneText != null) {
            appendLine()
            if (hasPhoto) appendLine("The phone also read this text from the receipt. It may have mistakes, so trust the photo when they differ:")
            else appendLine("You only have this text, which a phone read from the receipt. It may have mistakes:")
            append(phoneText.take(8000))
        }
    }
}
