package com.moblized.spendscan.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Monthly budget; null means no budget. */
    val budgetCents: Long? = null,
    val position: Int = 0,
    /** The catch-all that receipts move to when their category is deleted. Can't be deleted itself. */
    val fixed: Boolean = false,
)

@Entity(tableName = "receipts", indices = [Index("dateEpochDay"), Index("categoryId")])
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val store: String,
    val dateEpochDay: Long,
    val totalCents: Long,
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val tipCents: Long? = null,
    val discountCents: Long? = null,
    val categoryId: Long,
    val payment: String? = null,
    val note: String? = null,
    /** File names of the scanned pages, one per line. */
    val photos: String = "",
    /** The text as the phone read it. */
    val rawText: String? = null,
    val createdAt: Long = 0,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
    val photoList: List<String> get() = photos.lines().filter { it.isNotBlank() }
}

@Entity(
    tableName = "items",
    foreignKeys = [ForeignKey(entity = Receipt::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("receiptId")],
)
data class ReceiptItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val name: String,
    val quantity: Double = 1.0,
    val amountCents: Long,
    val position: Int = 0,
)

/** The fuel details of a receipt that was a fill-up. The money stays on the receipt, so it's only ever counted once. */
@Entity(
    tableName = "fill_ups",
    foreignKeys = [ForeignKey(entity = Receipt::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["receiptId"], unique = true)],
)
data class FillUp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    /** Thousandths of a gallon. */
    val gallonsMilli: Long? = null,
    /** The printed price per gallon, in thousandths of a dollar. */
    val priceMilli: Long? = null,
    val odometer: Int? = null,
    val fullTank: Boolean = true,
)

/** A fill-up with the receipt details it needs. */
data class FillUpRow(
    @Embedded val fill: FillUp,
    val store: String,
    val dateEpochDay: Long,
    val totalCents: Long,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}

data class ReceiptRow(
    @Embedded val receipt: Receipt,
    val itemCount: Int,
)

/** A line item found by searching, with the receipt it came from. */
data class ItemHit(
    val receiptId: Long,
    val name: String,
    val quantity: Double,
    val amountCents: Long,
    val store: String,
    val dateEpochDay: Long,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)
}

/** Where a new install starts; everything here can be renamed or removed except Other. */
val DEFAULT_CATEGORIES = listOf("Groceries", "Dining out", "Gas & auto", "Home", "Health", "Shopping", "Fun", "Work")
const val FALLBACK_CATEGORY = "Other"
