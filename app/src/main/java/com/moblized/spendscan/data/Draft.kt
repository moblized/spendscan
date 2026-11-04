package com.moblized.spendscan.data

import com.moblized.spendscan.parse.ParsedReceipt
import com.moblized.spendscan.parse.ReceiptField
import java.time.LocalDate
import kotlin.math.abs

data class DraftItem(val key: Long, val name: String, val quantity: Double = 1.0, val amountText: String = "")

/** A receipt being reviewed or edited. Amounts stay as typed text until saved. */
data class Draft(
    val receiptId: Long? = null,
    val store: String = "",
    val date: LocalDate,
    val totalText: String = "",
    val subtotalText: String = "",
    val taxText: String = "",
    val tipText: String = "",
    val discountText: String = "",
    val categoryId: Long? = null,
    val payment: String = "",
    val note: String = "",
    val items: List<DraftItem> = emptyList(),
    val photos: List<String> = emptyList(),
    val rawText: String? = null,
    /** Fields to look at, marked with a highlighter until they're edited. */
    val unsure: Set<ReceiptField> = emptySet(),
    /** Nothing could be read from the photo. */
    val readFailed: Boolean = false,
    /** Who read it, when you have a server: your model, or this phone because the server couldn't. */
    val readNote: String? = null,
    val createdAt: Long = 0,
) {
    val totalCents: Long? get() = Money.parse(totalText)
    val canSave: Boolean get() = store.isNotBlank() && totalCents != null && categoryId != null

    private fun cents(s: String): Long? = Money.parse(s)

    val itemsCents: Long get() = items.sumOf { cents(it.amountText) ?: 0 }

    /**
     * How far the items are from what they should add up to; zero when they match or there's nothing to compare.
     * Positive means the items come to more than the receipt says.
     */
    val itemsOffByCents: Long
        get() {
            if (items.isEmpty()) return 0
            val expected = cents(subtotalText) ?: totalCents?.let {
                it - (cents(taxText) ?: 0) - (cents(tipText) ?: 0)
            } ?: return 0
            // Discounts are sometimes already taken off the prices and sometimes printed separately.
            val discount = cents(discountText) ?: 0
            val off = itemsCents - expected
            return when {
                abs(off) <= 2 -> 0
                abs(off - discount) <= 2 -> 0
                else -> off
            }
        }

    fun toReceipt(now: Long): Receipt = Receipt(
        id = receiptId ?: 0,
        store = store.trim(),
        dateEpochDay = date.toEpochDay(),
        totalCents = totalCents ?: 0,
        subtotalCents = cents(subtotalText),
        taxCents = cents(taxText),
        tipCents = cents(tipText),
        discountCents = cents(discountText),
        categoryId = categoryId ?: 0,
        payment = payment.trim().ifEmpty { null },
        note = note.trim().ifEmpty { null },
        photos = photos.joinToString("\n"),
        rawText = rawText,
        createdAt = if (createdAt != 0L) createdAt else now,
    )

    fun toItems(): List<ReceiptItem> = items.mapNotNull { i ->
        val amount = cents(i.amountText)
        if (i.name.isBlank() || amount == null) null
        else ReceiptItem(receiptId = 0, name = i.name.trim(), quantity = i.quantity, amountCents = amount)
    }

    companion object {
        private fun text(c: Long?) = c?.let { Money.plain(it) }.orEmpty()

        fun blank(today: LocalDate, categoryId: Long?) = Draft(date = today, categoryId = categoryId)

        /** Starts a review from what was read off the receipt. */
        fun fromParsed(
            parsed: ParsedReceipt?,
            photos: List<String>,
            today: LocalDate,
            categoryId: Long?,
            readNote: String? = null,
        ): Draft {
            if (parsed == null || (parsed.totalCents == null && parsed.store == null && parsed.items.isEmpty())) {
                return Draft(
                    date = today,
                    categoryId = categoryId,
                    photos = photos,
                    rawText = parsed?.rawText?.ifBlank { null },
                    unsure = ReceiptField.entries.toSet(),
                    readFailed = true,
                    readNote = readNote,
                )
            }
            return Draft(
                store = parsed.store.orEmpty(),
                date = parsed.date ?: today,
                totalText = text(parsed.totalCents),
                subtotalText = text(parsed.subtotalCents),
                taxText = text(parsed.taxCents),
                tipText = text(parsed.tipCents),
                discountText = text(parsed.discountCents),
                categoryId = categoryId,
                payment = parsed.payment.orEmpty(),
                items = parsed.items.mapIndexed { i, it -> DraftItem(i.toLong(), it.name, it.quantity, Money.plain(it.amountCents)) },
                photos = photos,
                rawText = parsed.rawText,
                unsure = parsed.unsure,
                readNote = readNote,
            )
        }

        fun fromSaved(r: Receipt, items: List<ReceiptItem>) = Draft(
            receiptId = r.id,
            store = r.store,
            date = r.date,
            totalText = Money.plain(r.totalCents),
            subtotalText = text(r.subtotalCents),
            taxText = text(r.taxCents),
            tipText = text(r.tipCents),
            discountText = text(r.discountCents),
            categoryId = r.categoryId,
            payment = r.payment.orEmpty(),
            note = r.note.orEmpty(),
            items = items.mapIndexed { i, it -> DraftItem(i.toLong(), it.name, it.quantity, Money.plain(it.amountCents)) },
            photos = r.photoList,
            rawText = r.rawText,
            createdAt = r.createdAt,
        )
    }
}
