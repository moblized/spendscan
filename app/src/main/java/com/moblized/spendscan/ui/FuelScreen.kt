package com.moblized.spendscan.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.DayAmount
import com.moblized.spendscan.data.FillUpLine
import com.moblized.spendscan.data.FuelPeriod
import com.moblized.spendscan.data.FuelReset
import com.moblized.spendscan.data.FuelSummary
import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.time.format.DateTimeFormatter

/** What you spend on fuel: the month, week or two weeks on top, then each fill-up, on one slip. */
@Composable
fun FuelScreen(
    s: FuelSummary,
    onPeriod: (FuelPeriod) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onPumpPhoto: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeReset: () -> Unit = {},
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val noun = s.period.reset.noun()
            CounterIcon(R.drawable.ic_back, "Previous $noun", { onPeriod(s.period.previous()) })
            Text(s.period.title(s.today.year), style = Type.lead, color = c.onCounter, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            CounterIcon(R.drawable.ic_forward, "Next $noun", { onPeriod(s.period.next()) }, enabled = !s.isCurrent)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CounterAction("Photo of the pump", onPumpPhoto, Modifier.weight(1.35f), icon = R.drawable.ic_camera)
            CounterAction("Add by hand", onAdd, Modifier.weight(1f), icon = R.drawable.ic_add)
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
                            "Scan a gas receipt with the Scan button. If the station doesn't print one, take a photo of the pump instead.",
                            style = Type.body,
                            color = c.inkFaded,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Include your odometer to see your miles per gallon.",
                            style = Type.small,
                            color = c.inkFaded,
                        )
                    }
                }
            } else {
                item { FuelTotals(s) }
                item { FuelHistory(s, onPeriod) }
                if (s.lines.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                            Text(noFillUps(s), style = Type.body, color = c.inkFaded)
                        }
                    }
                }
                items(s.lines, key = { it.row.fill.id }) { line -> FillUpEntry(line) { onOpen(line.row.fill.receiptId) } }
                item {
                    // The setting lives in Settings, which is a long way from here.
                    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
                        SlipRule()
                        Text(fuelResetLine(s.period.reset), style = Type.small, color = c.inkFaded)
                        Spacer(Modifier.height(6.dp))
                        PaperButton("Change in Settings", onChangeReset, Modifier.fillMaxWidth())
                    }
                }
            }
            item { SlipEdge(top = false) }
        }
    }
}

/** An action on the counter, above the slip. */
@Composable
private fun CounterAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int) {
    val c = LocalSlip.current
    Row(
        modifier
            .defaultMinSize(minHeight = 46.dp)
            .border(1.dp, c.onCounter.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = c.onCounter, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.body, color = c.onCounter, maxLines = 1)
    }
}

@Composable
private fun FuelTotals(s: FuelSummary) {
    val c = LocalSlip.current
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(top = 16.dp)) {
        Text(
            fuelTotalsTitle(s),
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
        s.byType.singleOrNull()?.let { LeaderRow("Fuel", it.type.label, rowGap) }
        LeaderRow("So far in ${s.year}", Money.format(s.yearCents), rowGap)
        if (s.byType.size > 1) {
            SlipRule()
            Text("By fuel", style = Type.small, color = c.inkFaded)
            s.byType.forEach { t ->
                LeaderRow(t.type.label, Money.format(t.spentCents), Modifier.padding(top = 4.dp))
                val detail = buildList {
                    if (t.gallonsMilli > 0) add("${FuelUnits.gallons(t.gallonsMilli)} gal")
                    t.averagePriceMilli?.let { add("${FuelUnits.pricePerGallon(it)} a gallon") }
                }.joinToString(", ")
                if (detail.isNotEmpty()) Text(detail, style = Type.small, color = c.inkFaded)
            }
        }
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

/** A bar for each of the last twelve months, weeks or pairs of weeks. Tap one to go to it. */
@Composable
private fun FuelHistory(s: FuelSummary, onPeriod: (FuelPeriod) -> Unit) {
    val c = LocalSlip.current
    val reset = s.period.reset
    Column(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp)) {
        SlipRule()
        Text(fuelHistoryHeading(reset), style = Type.small, color = c.ink)
        Spacer(Modifier.height(8.dp))
        val highest = s.bars.maxByOrNull { it.cents }
        val each = when (reset) {
            FuelReset.MONTH -> "each of the last 12 months"
            FuelReset.WEEK -> "each of the last 12 weeks"
            FuelReset.TWO_WEEKS -> "the last 24 weeks, 2 weeks at a time"
        }
        val description = "Fuel spending for $each" +
            (highest?.takeIf { it.cents > 0 }?.let { ". Highest was ${it.period.title(s.today.year)} at ${Money.format(it.cents)}" } ?: "")
        val selected = s.bars.indexOfFirst { it.period == s.period }.takeIf { it >= 0 }?.plus(1)
        // The same printed bars as the month's days.
        DayBarcode(
            days = s.bars.mapIndexed { i, bar -> DayAmount(i + 1, bar.cents, future = false) },
            selected = selected,
            onSelect = { onPeriod(s.bars[it - 1].period) },
            modifier = Modifier.semantics { contentDescription = description },
            wide = true,
        )
        // Where the first and last bars start.
        val mark: (FuelPeriod) -> String = { if (reset == FuelReset.MONTH) it.first.format(SHORT_MONTH) else it.first.short() }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mark(s.bars.first().period), style = Type.small, color = c.inkFaded)
            Text(mark(s.bars.last().period), style = Type.small, color = c.inkFaded)
        }
        if (s.lines.isNotEmpty() || s.allFillUps > 0) SlipRule()
    }
}

/** "22.4 gal of diesel at $3.759, 14.8 mpg, 52,310 mi", leaving out whatever wasn't recorded. */
fun fillUpDetails(line: FillUpLine): String {
    val row = line.row
    val fill = row.fill
    val type = fill.type
    return buildList {
        val gallons = fill.gallonsMilli
        if (gallons != null) {
            val price = fill.priceMilli ?: FuelUnits.paidPerGallon(row.totalCents, gallons)
            add(
                "${FuelUnits.gallons(gallons)} gal" + (type?.let { " of ${it.word}" } ?: "") +
                    (price?.let { " at ${FuelUnits.pricePerGallon(it)}" } ?: ""),
            )
        } else if (type != null) {
            add(type.label)
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
