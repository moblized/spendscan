package com.moblized.spendscan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.CategoryLine
import com.moblized.spendscan.data.Comparison
import com.moblized.spendscan.data.DayAmount
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.MonthSummary
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import kotlin.math.abs
import kotlin.math.max

/** The month, printed as one long till slip: what you spent, where it went, and a barcode of the days. */
@Composable
fun MonthScreen(
    summary: MonthSummary,
    animateIn: Boolean,
    onPrinted: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCategory: (Long) -> Unit,
    onStore: (String) -> Unit,
    onDay: (Int) -> Unit,
    onAllReceipts: () -> Unit,
    onScan: () -> Unit,
    onAddByHand: () -> Unit,
    modifier: Modifier = Modifier,
    onCalculator: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Previous month", onPrev)
            Text(
                summary.month.withYear(),
                style = Type.lead,
                color = c.onCounter,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            CounterIcon(R.drawable.ic_forward, "Next month", onNext, enabled = !summary.isCurrent)
            CounterIcon(R.drawable.ic_calculator, "Calculator", onCalculator)
            CounterIcon(R.drawable.ic_settings, "Settings", onSettings)
        }

        // The slip feeds out from under the header, like paper from a till.
        val feed = remember { Animatable(if (animateIn) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (animateIn) {
                feed.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                onPrinted()
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp)
                .graphicsLayer { translationY = -(1f - feed.value) * size.height },
        ) {
            if (summary.receiptCount == 0) {
                EmptySlip(summary, onScan, onAddByHand)
            } else {
                MonthSlip(summary, onCategory, onStore, onDay, onAllReceipts)
            }
        }
    }
}

@Composable
private fun EmptySlip(summary: MonthSummary, onScan: () -> Unit, onAddByHand: () -> Unit) {
    val c = LocalSlip.current
    Slip {
        Text(
            "No receipts in ${summary.month.name()}${if (summary.isCurrent) " yet" else ""}",
            style = Type.lead,
            color = c.ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Scan one and it shows up here, added to your month and sorted by category.",
            style = Type.body,
            color = c.inkFaded,
        )
        Spacer(Modifier.height(16.dp))
        YellowButton("Scan a receipt", onScan, Modifier.fillMaxWidth(), icon = R.drawable.ic_scan)
        Spacer(Modifier.height(8.dp))
        PaperButton("Add one by hand", onAddByHand, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MonthSlip(
    s: MonthSummary,
    onCategory: (Long) -> Unit,
    onStore: (String) -> Unit,
    onDay: (Int) -> Unit,
    onAllReceipts: () -> Unit,
) {
    val c = LocalSlip.current
    Slip {
        Text(
            if (s.isCurrent) "Spent so far" else "Spent in ${s.month.name()}",
            style = Type.body,
            color = c.inkFaded,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            Money.format(s.totalCents),
            style = Type.figure.copy(color = c.ink, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 56.sp, stepSize = 2.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        s.comparison?.let {
            Text(comparisonText(it), style = Type.small, color = c.inkFaded, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        s.budgetCents?.let { budget ->
            Spacer(Modifier.height(12.dp))
            val over = s.totalCents > budget
            TickMeter(if (budget > 0) s.totalCents.toFloat() / budget else 1f, over)
            Spacer(Modifier.height(5.dp))
            BudgetWords(s.totalCents, budget)
        }

        SlipRule()

        s.categories.forEachIndexed { i, line ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            CategoryRow(line, onClick = { onCategory(line.category.id) })
        }

        SlipRule()

        Text("Top stores", style = Type.small, color = c.inkFaded)
        Spacer(Modifier.height(4.dp))
        s.stores.take(5).forEach { store ->
            LeaderRow(
                if (store.visits > 1) "${store.store} x${store.visits}" else store.store,
                Money.format(store.spentCents),
                Modifier.clickable { onStore(store.store) }.padding(vertical = 3.dp),
            )
        }

        SlipRule()

        DaySection(s, onDay)

        Spacer(Modifier.height(14.dp))
        PaperButton("See all ${count(s.receiptCount, "receipt")}", onAllReceipts, Modifier.fillMaxWidth())
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
private fun BudgetWords(spent: Long, budget: Long) {
    val c = LocalSlip.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (spent > budget) {
            Text("${Money.format(spent - budget)} over ${Money.short(budget)}", style = Type.small, color = c.ink, modifier = Modifier.weight(1f))
            Stamp("OVER")
        } else {
            Text("${Money.format(budget - spent)} left of ${Money.short(budget)}", style = Type.small, color = c.inkFaded)
        }
    }
}

@Composable
private fun CategoryRow(line: CategoryLine, onClick: () -> Unit) {
    val budget = line.category.budgetCents
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)) {
        LeaderRow(line.category.name, Money.format(line.spentCents))
        if (budget != null) {
            Spacer(Modifier.height(4.dp))
            TickMeter(if (budget > 0) line.spentCents.toFloat() / budget else 1f, line.spentCents > budget)
            Spacer(Modifier.height(4.dp))
            BudgetWords(line.spentCents, budget)
        }
    }
}

@Composable
private fun DaySection(s: MonthSummary, onDay: (Int) -> Unit) {
    val c = LocalSlip.current
    var selected by remember(s.month) { mutableStateOf<Int?>(null) }
    val shown = selected?.let { d -> s.days.getOrNull(d - 1) }
    val biggest = s.biggestDay

    Text(
        when {
            shown != null -> "${s.month.atDay(shown.day).short()}: ${Money.format(shown.cents)}"
            biggest != null -> "Biggest day: ${s.month.atDay(biggest.day).short()}, ${Money.format(biggest.cents)}"
            else -> "By day"
        },
        style = Type.small,
        color = c.ink,
    )
    Spacer(Modifier.height(8.dp))
    val description = "Spending for each day of ${s.month.name()}" +
        (biggest?.let { ". Biggest day was ${s.month.atDay(it.day).short()} at ${Money.format(it.cents)}" } ?: "")
    DayBarcode(s.days, selected, onSelect = { selected = it }, Modifier.semantics { contentDescription = description })
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("1", style = Type.small, color = c.inkFaded)
        Text("${s.days.size / 2}", style = Type.small, color = c.inkFaded)
        Text("${s.days.size}", style = Type.small, color = c.inkFaded)
    }
    if (shown != null && shown.cents > 0) {
        Spacer(Modifier.height(8.dp))
        PaperButton("See ${s.month.atDay(shown.day).short()} receipts", { onDay(shown.day) }, Modifier.fillMaxWidth())
    }
}

/**
 * One bar per day, tall when more was spent, printed like the barcode at the bottom of a receipt.
 * Tap or drag across it to read a day.
 */
@Composable
fun DayBarcode(days: List<DayAmount>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    val top = max(1L, days.maxOfOrNull { it.cents } ?: 1L)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .pointerInput(days.size) {
                detectTapGestures { o -> onSelect(((o.x / size.width) * days.size).toInt().coerceIn(0, days.size - 1) + 1) }
            }
            .pointerInput(days.size) {
                detectHorizontalDragGestures { change, _ ->
                    onSelect(((change.position.x / size.width) * days.size).toInt().coerceIn(0, days.size - 1) + 1)
                }
            },
    ) {
        val slot = size.width / days.size
        val gap = 2.dp.toPx()
        val bar = slot - gap
        days.forEachIndexed { i, d ->
            val x = i * slot + gap / 2
            if (selected == d.day) {
                drawRect(c.highlighter, Offset(i * slot, 0f), Size(slot, size.height))
            }
            when {
                d.cents > 0 -> {
                    val h = max(4.dp.toPx(), size.height * d.cents / top)
                    drawRect(if (selected == d.day) c.onHighlighter else c.ink, Offset(x, size.height - h), Size(bar, h))
                }
                d.future -> drawRect(c.inkFaded.copy(alpha = 0.3f), Offset(x, size.height - 1.dp.toPx()), Size(bar, 1.dp.toPx()))
                else -> drawRect(c.inkFaded.copy(alpha = 0.6f), Offset(x, size.height - 2.dp.toPx()), Size(bar, 2.dp.toPx()))
            }
        }
    }
}
