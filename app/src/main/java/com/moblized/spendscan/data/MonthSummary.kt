package com.moblized.spendscan.data

import java.time.LocalDate
import java.time.YearMonth

data class CategoryLine(val category: Category, val spentCents: Long, val receipts: Int)

data class StoreLine(val store: String, val spentCents: Long, val visits: Int)

data class DayAmount(val day: Int, val cents: Long, val future: Boolean)

/** How this month compares with the one before it. */
data class Comparison(
    val otherMonth: YearMonth,
    /** Positive means more than last month. */
    val differenceCents: Long,
    /** Compared only up to the same day of the month, because this month isn't over. */
    val soFar: Boolean,
)

data class MonthSummary(
    val month: YearMonth,
    val totalCents: Long,
    val receiptCount: Int,
    val comparison: Comparison?,
    val categories: List<CategoryLine>,
    val stores: List<StoreLine>,
    val days: List<DayAmount>,
    val budgetCents: Long?,
    val isCurrent: Boolean,
) {
    val biggestDay: DayAmount? get() = days.filter { it.cents > 0 }.maxByOrNull { it.cents }

    companion object {
        fun of(
            month: YearMonth,
            receipts: List<Receipt>,
            previous: List<Receipt>,
            categories: List<Category>,
            overallBudgetCents: Long?,
            today: LocalDate,
        ): MonthSummary {
            val isCurrent = YearMonth.from(today) == month
            val byCategory = receipts.groupBy { it.categoryId }
            val lines = categories.mapNotNull { c ->
                val rs = byCategory[c.id].orEmpty()
                if (rs.isEmpty() && c.budgetCents == null) null
                else CategoryLine(c, rs.sumOf { it.totalCents }, rs.size)
            }.sortedWith(compareByDescending<CategoryLine> { it.spentCents }.thenBy { it.category.position })

            // Spelled differently on two receipts is still one store.
            val stores = receipts.groupBy { it.store.trim().lowercase() }.values.map { rs ->
                StoreLine(rs.first().store.trim(), rs.sumOf { it.totalCents }, rs.size)
            }.sortedByDescending { it.spentCents }

            val perDay = receipts.groupBy { it.date.dayOfMonth }.mapValues { (_, rs) -> rs.sumOf { it.totalCents } }
            val days = (1..month.lengthOfMonth()).map { d ->
                DayAmount(d, perDay[d] ?: 0, future = month.atDay(d).isAfter(today))
            }

            val total = receipts.sumOf { it.totalCents }
            val before = month.minusMonths(1)
            val comparison = if (previous.isEmpty()) null else {
                val cutoff = minOf(today.dayOfMonth, before.lengthOfMonth())
                val sameStretch = if (isCurrent) previous.filter { it.date.dayOfMonth <= cutoff } else previous
                Comparison(before, total - sameStretch.sumOf { it.totalCents }, soFar = isCurrent)
            }

            val categoryBudgets = categories.mapNotNull { it.budgetCents }
            val budget = overallBudgetCents ?: categoryBudgets.takeIf { it.isNotEmpty() }?.sum()

            return MonthSummary(month, total, receipts.size, comparison, lines, stores, days, budget, isCurrent)
        }
    }
}

/** What searching receipts for an item turned up. */
data class ItemStats(val query: String, val times: Int, val totalCents: Long, val averageCents: Long, val hits: List<ItemHit>) {
    companion object {
        fun of(query: String, hits: List<ItemHit>): ItemStats? {
            if (hits.isEmpty()) return null
            val total = hits.sumOf { it.amountCents }
            return ItemStats(query, hits.size, total, total / hits.size, hits)
        }
    }
}

data class ReceiptFilter(
    val query: String = "",
    val categoryId: Long? = null,
    val month: YearMonth? = null,
    val day: LocalDate? = null,
    val store: String? = null,
) {
    val isEmpty: Boolean get() = query.isBlank() && categoryId == null && month == null && day == null && store == null

    fun matches(row: ReceiptRow, itemReceiptIds: Set<Long>): Boolean {
        val r = row.receipt
        if (categoryId != null && r.categoryId != categoryId) return false
        if (month != null && YearMonth.from(r.date) != month) return false
        if (day != null && r.date != day) return false
        if (store != null && !r.store.trim().equals(store.trim(), ignoreCase = true)) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        return r.store.contains(q, ignoreCase = true) || r.note?.contains(q, ignoreCase = true) == true || r.id in itemReceiptIds
    }
}

/** A budget message worth showing when a save pushes a category past a line. */
fun budgetNote(categoryName: String, budgetCents: Long?, before: Long, after: Long): String? {
    if (budgetCents == null || budgetCents <= 0 || after <= before) return null
    return when {
        after > budgetCents && before <= budgetCents -> "$categoryName is over budget by ${Money.format(after - budgetCents)}."
        after >= budgetCents * 8 / 10 && before < budgetCents * 8 / 10 -> "$categoryName is at ${after * 100 / budgetCents}% of its budget."
        else -> null
    }
}
