package com.moblized.spendscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.ItemHit
import com.moblized.spendscan.data.ItemStats
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.ReceiptFilter
import com.moblized.spendscan.data.ReceiptRow
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.time.LocalDate

private sealed interface ListRow {
    data class Day(val date: LocalDate) : ListRow
    data class Entry(val row: ReceiptRow) : ListRow
}

@Composable
fun ReceiptsScreen(
    rows: List<ReceiptRow>,
    itemStats: ItemStats?,
    filter: ReceiptFilter,
    categories: List<Category>,
    hasAnyReceipts: Boolean,
    onFilter: (ReceiptFilter) -> Unit,
    onOpen: (Long) -> Unit,
    onAddByHand: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    val names = remember(categories) { categories.associate { it.id to it.name } }
    val list = remember(rows) {
        buildList<ListRow> {
            var day: LocalDate? = null
            for (r in rows) {
                if (r.receipt.date != day) {
                    day = r.receipt.date
                    add(ListRow.Day(r.receipt.date))
                }
                add(ListRow.Entry(r))
            }
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Receipts", style = Type.lead, color = c.onCounter, modifier = Modifier.weight(1f))
            CounterIcon(R.drawable.ic_add, "Add a receipt by hand", onAddByHand)
        }
        SearchField(filter.query, { onFilter(filter.copy(query = it)) }, Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filter.month?.let { m -> item { Chip("${m.withYear()}  x", true, { onFilter(filter.copy(month = null)) }) } }
            filter.day?.let { d -> item { Chip("${d.short()}  x", true, { onFilter(filter.copy(day = null)) }) } }
            filter.store?.let { s -> item { Chip("$s  x", true, { onFilter(filter.copy(store = null)) }) } }
            item { Chip("All", filter.categoryId == null, { onFilter(filter.copy(categoryId = null)) }) }
            items(categories, key = { it.id }) { cat ->
                Chip(cat.name, filter.categoryId == cat.id, {
                    onFilter(filter.copy(categoryId = if (filter.categoryId == cat.id) null else cat.id))
                })
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            item { SlipEdge(top = true) }
            item {
                if (itemStats != null) {
                    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(top = 12.dp)) {
                        ItemStatsBlock(itemStats, onOpen)
                    }
                }
                if (rows.isEmpty() && itemStats == null) {
                    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        if (hasAnyReceipts) {
                            Text("Nothing matches", style = Type.lead, color = c.ink)
                            Spacer(Modifier.height(4.dp))
                            Text("Try a different word, or clear the filters above.", style = Type.body, color = c.inkFaded)
                            Spacer(Modifier.height(12.dp))
                            PaperButton("Clear filters", { onFilter(ReceiptFilter()) }, Modifier.fillMaxWidth())
                        } else {
                            Text("No receipts yet", style = Type.lead, color = c.ink)
                            Spacer(Modifier.height(4.dp))
                            Text("Every receipt you scan is kept here, with its photo. Search finds stores and the items on them.", style = Type.body, color = c.inkFaded)
                            Spacer(Modifier.height(12.dp))
                            YellowButton("Scan a receipt", onScan, Modifier.fillMaxWidth(), icon = R.drawable.ic_scan)
                        }
                    }
                }
            }
            items(list, key = { row ->
                when (row) {
                    is ListRow.Day -> "d${row.date}"
                    is ListRow.Entry -> "r${row.row.receipt.id}"
                }
            }) { row ->
                when (row) {
                    is ListRow.Day -> Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 2.dp)) {
                        Text(row.date.full(), style = Type.small, color = c.inkFaded)
                    }
                    is ListRow.Entry -> ReceiptEntry(row.row, names[row.row.receipt.categoryId].orEmpty()) { onOpen(row.row.receipt.id) }
                }
            }
            item {
                Spacer(Modifier.fillMaxWidth().height(12.dp).background(c.paper))
            }
            item { SlipEdge(top = false) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().background(c.paper, RoundedCornerShape(5.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = c.inkFaded, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("Search stores and items", style = Type.body, color = c.inkFaded, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun ReceiptEntry(row: ReceiptRow, categoryName: String, onClick: () -> Unit) {
    val c = LocalSlip.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        LeaderRow(row.receipt.store, Money.format(row.receipt.totalCents), style = Type.strong, amountStyle = Type.strong)
        val detail = if (row.itemCount > 0) "$categoryName, ${count(row.itemCount, "item")}" else categoryName
        Text(detail, style = Type.small, color = c.inkFaded, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ItemStatsBlock(stats: ItemStats, onOpen: (Long) -> Unit) {
    val c = LocalSlip.current
    Text("\"${stats.query.trim()}\" on your receipts", style = Type.strong, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Text(
        "Bought ${count(stats.times, "time")} for ${Money.format(stats.totalCents)}, about ${Money.format(stats.averageCents)} each time",
        style = Type.small,
        color = c.inkFaded,
    )
    Spacer(Modifier.height(6.dp))
    stats.hits.take(6).forEach { hit -> ItemHitRow(hit) { onOpen(hit.receiptId) } }
    SlipRule()
}

@Composable
private fun ItemHitRow(hit: ItemHit, onClick: () -> Unit) {
    val c = LocalSlip.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        LeaderRow(hit.name, Money.format(hit.amountCents))
        Text("${hit.store}, ${hit.date.short()}", style = Type.small, color = c.inkFaded, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
