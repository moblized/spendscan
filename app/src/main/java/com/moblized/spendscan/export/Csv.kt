package com.moblized.spendscan.export

import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.FillUpRow
import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.ReceiptItem

/** Spreadsheet-ready copies of the receipts. */
object Csv {
    /** Quotes a cell. Text from a photo can start with "=", which spreadsheets would run as a formula. */
    fun cell(value: String?): String {
        val v = value.orEmpty().let { if (it.firstOrNull() in setOf('=', '+', '-', '@')) "'$it" else it }
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private fun money(c: Long?) = c?.let { Money.plain(it) }.orEmpty()

    fun receipts(receipts: List<Receipt>, categories: List<Category>): String {
        val names = categories.associate { it.id to it.name }
        val sb = StringBuilder("Date,Store,Category,Total,Subtotal,Tax,Tip,Discount,Paid with,Note\r\n")
        for (r in receipts) {
            sb.append(
                listOf(
                    r.date.toString(),
                    cell(r.store),
                    cell(names[r.categoryId]),
                    money(r.totalCents),
                    money(r.subtotalCents),
                    money(r.taxCents),
                    money(r.tipCents),
                    money(r.discountCents),
                    cell(r.payment),
                    cell(r.note),
                ).joinToString(","),
            ).append("\r\n")
        }
        return sb.toString()
    }

    fun fuel(rows: List<FillUpRow>): String {
        val sb = StringBuilder("Date,Station,Fuel type,Gallons,Price per gallon,Total,Odometer,Full tank\r\n")
        for (r in rows) {
            sb.append(
                listOf(
                    r.date.toString(),
                    cell(r.store),
                    r.fill.type?.label.orEmpty(),
                    r.fill.gallonsMilli?.let(FuelUnits::plainMilli).orEmpty(),
                    r.fill.priceMilli?.let(FuelUnits::plainMilli).orEmpty(),
                    money(r.totalCents),
                    r.fill.odometer?.toString().orEmpty(),
                    if (r.fill.fullTank) "yes" else "no",
                ).joinToString(","),
            ).append("\r\n")
        }
        return sb.toString()
    }

    fun items(receipts: List<Receipt>, items: List<ReceiptItem>, categories: List<Category>): String {
        val names = categories.associate { it.id to it.name }
        val byId = receipts.associateBy { it.id }
        val sb = StringBuilder("Date,Store,Category,Item,Quantity,Amount\r\n")
        for (i in items) {
            val r = byId[i.receiptId] ?: continue
            val qty = if (i.quantity % 1.0 == 0.0) i.quantity.toLong().toString() else i.quantity.toString()
            sb.append(
                listOf(r.date.toString(), cell(r.store), cell(names[r.categoryId]), cell(i.name), qty, money(i.amountCents))
                    .joinToString(","),
            ).append("\r\n")
        }
        return sb.toString()
    }
}
