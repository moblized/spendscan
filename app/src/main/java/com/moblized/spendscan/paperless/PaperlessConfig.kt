package com.moblized.spendscan.paperless

import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.server.isHomeNetworkHost
import java.text.Normalizer
import java.time.LocalDate

/** Your Paperless-ngx server. [on] is whether receipts go to it; the address and token can be filled in first. */
data class PaperlessConfig(val url: String = "", val token: String = "", val on: Boolean = false) {
    val canConnect: Boolean get() = root.isNotEmpty() && token.isNotBlank()
    val isOn: Boolean get() = on && canConnect

    /**
     * The address as typed, with a scheme, and without the page or API path pasted on the end. A bare name like
     * "paperless.example.com" gets https, since that's how a server out on the internet answers; a home address gets http.
     */
    val root: String
        get() {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.contains("://")) {
                val host = u.substringBefore('/').substringBeforeLast(':').lowercase()
                u = (if (isHomeNetworkHost(host)) "http://" else "https://") + u
            }
            for (page in listOf("/api", "/dashboard", "/documents", "/accounts/login")) {
                val at = u.indexOf(page, startIndex = u.indexOf("://") + 3)
                if (at > 0 && (u.length == at + page.length || u[at + page.length] == '/' || u[at + page.length] == '?')) {
                    u = u.substring(0, at)
                    break
                }
            }
            return u.trimEnd('/')
        }

    val apiBase: String get() = "$root/api"

    /** Plain http to somewhere beyond the home network, where anyone along the way could read the receipts. */
    val sendsInTheClear: Boolean
        get() {
            val r = root
            if (!r.startsWith("http://")) return false
            return !isHomeNetworkHost(r.removePrefix("http://").substringBefore('/').substringBeforeLast(':').lowercase())
        }
}

/** What a receipt looks like as a Paperless document. */
data class PaperlessDetails(
    val title: String,
    val created: LocalDate,
    val correspondent: String,
    val tag: String?,
    /** The total as Paperless's money fields want it: "USD84.12", or just "84.12" without a currency. */
    val total: String,
    val fileName: String,
) {
    companion object {
        const val DOCUMENT_TYPE = "Receipt"
        const val TOTAL_FIELD = "Total"

        // Paperless keeps names to 128 characters.
        private const val MAX_NAME = 128

        fun of(r: Receipt, categoryName: String?, currencyCode: String?): PaperlessDetails {
            val store = r.store.trim().ifEmpty { "Receipt" }.take(MAX_NAME)
            val code = currencyCode?.takeIf { Regex("[A-Z]{3}").matches(it) }.orEmpty()
            return PaperlessDetails(
                title = "$store ${Money.format(r.totalCents)}",
                created = r.date,
                correspondent = store,
                tag = categoryName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME),
                total = code + Money.plain(r.totalCents),
                fileName = fileName(r.date, store),
            )
        }

        /** "2026-11-05 Costco.pdf". Only plain characters, so no server or file system trips over it. */
        fun fileName(date: LocalDate, store: String): String {
            val plain = Normalizer.normalize(store, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val safe = plain.map { if (it.isLetterOrDigit() && it.code < 128 || it == ' ' || it == '-' || it == '.') it else '_' }
                .joinToString("").replace(Regex("_+"), "_").trim(' ', '.', '_').take(60)
            return "$date ${safe.ifEmpty { "Receipt" }}.pdf"
        }
    }
}
