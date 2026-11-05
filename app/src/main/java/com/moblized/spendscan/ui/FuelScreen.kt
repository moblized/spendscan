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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.Comparison
import com.moblized.spendscan.data.DayAmount
import com.moblized.spendscan.data.FillUpLine
import com.moblized.spendscan.data.FuelSummary
import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** What you spend on fuel: the month on top, then each fill-up, on one slip. */
@Composable
fun FuelScreen(
    s: FuelSummary,
    onMonth: (YearMonth) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Previous month", { onMonth(s.month.minusMonths(1)) })
            Text(s.month.withYear(), style = Type.lead, color = c.onCounter, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            CounterIcon(R.drawable.ic_forward, "Next month", { onMonth(s.month.plusMonths(1)) }, enabled = !s.isCurrent)
            CounterIcon(R.drawable.ic_add, "Add a fill-up", onAdd)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
        ) {
            item { SlipEdge(top = true) }
            if (s.allFillUps == 0) {
                item {
                    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text("No fill-ups yet", style = Type.lead, color = c.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Scan a gas receipt or add a fill-up by hand. Include your odometer to see your miles per gallon.",
                            style = Type.body,
                            color = c.inkFaded,
                        )
                        Spacer(Modifier.height(16.dp))
                        YellowButton("Scan a gas receipt", onScan, Modifier.fillMaxWidth(), icon = R.drawable.ic_scan)
                        Spacer(Modifier.height(8.dp))
                        PaperButton("Add a fill-up", onAdd, Modifier.fillMaxWidth())
                    }
                }
            } else {
                item { FuelTotals(s) }
                item { FuelYear(s, onMonth) }
                if (s.lines.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                            Text("No fill-ups in ${s.month.name()}.", style = Type.body, color = c.inkFaded)
                        }
                    }
                }
                items(s.lines, key = { it.row.fill.id }) { line -> FillUpEntry(line) { onOpen(line.row.fill.receiptId) } }
                item { Spacer(Modifier.fillMaxWidth().height(10.dp).background(c.paper)) }
            }
            item { SlipEdge(top = false) }
        }
    }
}

private fun comparisonText(cmp: Comparison): String {
    val other = if (cmp.soFar) "this point in ${cmp.otherMonth.name()}" else cmp.otherMonth.name()
    val amount = Money.format(abs(cmp.differenceCents))
    return when {
        cmp.differenceCents > 0 -> "$amount more than $other"
        cmp.differenceCents < 0 -> "$amount less than $other"
        else -> "The same as $other"
    }
}

@Composable
private fun FuelTotals(s: FuelSummary) {
    val c = LocalSlip.current
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(top = 16.dp)) {
        Text(
            if (s.isCurrent) "Fuel so far" else "Fuel in ${s.month.name()}",
            style = Type.body,
            color = c.inkFaded,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            Money.format(s.spentCents),
            style = Type.figure.copy(color = c.ink, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 56.sp, stepSize = 2.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        s.comparison?.let {
            Text(comparisonText(it), style = Type.small, color = c.inkFaded, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        SlipRule()
        val rowGap = Modifier.padding(vertical = 2.dp)
        LeaderRow("Fill-ups", s.count.toString(), rowGap)
        if (s.gallonsMilli > 0) LeaderRow("Gallons", FuelUnits.gallons(s.gallonsMilli), rowGap)
        s.averagePriceMilli?.let { LeaderRow("Average per gallon", FuelUnits.pricePerGallon(it), rowGap) }
        LeaderRow("So far in ${s.month.year}", Money.format(s.yearCents), rowGap)
        SlipRule()
        val average = s.averageMpg
        if (average == null) {
            Text("Add your odometer at two full tanks in a row to see your miles per gallon.", style = Type.small, color = c.inkFaded)
        } else {
            LeaderRow("Miles per gallon", FuelUnits.mpg(average), rowGap, style = Type.strong, amountStyle = Type.strong)
            s.lastMpg?.let { LeaderRow("Last tank", FuelUnits.mpg(it), rowGap) }
            s.centsPerMile?.let { LeaderRow("Fuel cost per mile", Money.format(Math.round(it).coerceAtLeast(1)), rowGap) }
        }
    }
}

private val SHORT_MONTH = DateTimeFormatter.ofPattern("MMM")

@Composable
private fun FuelYear(s: FuelSummary, onMonth: (YearMonth) -> Unit) {
    val c = LocalSlip.current
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp)) {
        SlipRule()
        Text("Last 12 months", style = Type.small, color = c.ink)
        Spacer(Modifier.height(8.dp))
        val highest = s.bars.maxByOrNull { it.cents }
        val description = "Fuel spending for each of the last 12 months" +
            (highest?.takeIf { it.cents > 0 }?.let { ". Highest was ${it.month.withYear()} at ${Money.format(it.cents)}" } ?: "")
        val selected = s.bars.indexOfFirst { it.month == s.month }.takeIf { it >= 0 }?.plus(1)
        // The same printed bars as the month's days. Tap one to go to that month.
        DayBarcode(
            days = s.bars.mapIndexed { i, bar -> DayAmount(i + 1, bar.cents, future = false) },
            selected = selected,
            onSelect = { onMonth(s.bars[it - 1].month) },
            modifier = Modifier.semantics { contentDescription = description },
        )
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.bars.first().month.format(SHORT_MONTH), style = Type.small, color = c.inkFaded)
            Text(s.bars.last().month.format(SHORT_MONTH), style = Type.small, color = c.inkFaded)
        }
        if (s.lines.isNotEmpty() || s.allFillUps > 0) SlipRule()
    }
}

/** "22.4 gal at $3.759, 14.8 mpg, 52,310 mi", leaving out whatever wasn't recorded. */
fun fillUpDetails(line: FillUpLine): String {
    val row = line.row
    val fill = row.fill
    return buildList {
        fill.gallonsMilli?.let { gallons ->
            val price = fill.priceMilli ?: FuelUnits.paidPerGallon(row.totalCents, gallons)
            add("${FuelUnits.gallons(gallons)} gal" + (price?.let { " at ${FuelUnits.pricePerGallon(it)}" } ?: ""))
        }
        line.mpg?.let { add("${FuelUnits.mpg(it)} mpg") }
        fill.odometer?.let { add("${FuelUnits.odometer(it)} mi") }
        if (!fill.fullTank) add("partial")
    }.joinToString(", ")
}

@Composable
private fun FillUpEntry(line: FillUpLine, onClick: () -> Unit) {
    val c = LocalSlip.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(line.row.date.short(), style = Type.small, color = c.inkFaded)
        LeaderRow(line.row.store, Money.format(line.row.totalCents), style = Type.strong, amountStyle = Type.strong)
        val details = fillUpDetails(line)
        if (details.isNotEmpty()) {
            Text(details, style = Type.small, color = c.inkFaded, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
