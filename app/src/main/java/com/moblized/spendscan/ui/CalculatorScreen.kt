package com.moblized.spendscan.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.spendscan.R
import com.moblized.spendscan.calc.CalcKey
import com.moblized.spendscan.calc.CalcState
import com.moblized.spendscan.calc.Calculator
import com.moblized.spendscan.calc.DIVIDE
import com.moblized.spendscan.calc.MINUS
import com.moblized.spendscan.calc.PLUS
import com.moblized.spendscan.calc.TIMES
import com.moblized.spendscan.calc.TapeLine
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type

/** An adding machine: answers print onto a tape above the keys. */
@Composable
fun CalculatorScreen(
    state: CalcState,
    forTotal: Boolean,
    onKey: (CalcKey) -> Unit,
    onClearTape: () -> Unit,
    onUse: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Back", onBack)
            Text(if (forTotal) "Work out the total" else "Calculator", style = Type.lead, color = c.onCounter, modifier = Modifier.weight(1f))
            if (state.tape.isNotEmpty()) {
                Text(
                    "Clear tape",
                    style = Type.small,
                    color = c.onCounterMuted,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClearTape).padding(vertical = 10.dp),
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            SlipEdge(top = true)
            // Newest at the bottom, feeding upward like paper from the machine.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().background(c.paper).padding(horizontal = 20.dp),
                reverseLayout = true,
            ) {
                items(state.tape.asReversed()) { line -> TapeRow(line) }
            }
            Column(
                Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 20.dp).padding(bottom = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (state.tape.isNotEmpty()) SlipRule(Modifier.padding(top = 2.dp))
                Text(
                    state.error ?: state.expression,
                    style = Type.body,
                    color = if (state.error != null) c.stamp else c.inkFaded,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicText(
                    state.value?.let(Calculator::show) ?: "0",
                    style = Type.figure.copy(color = c.ink, textAlign = TextAlign.End),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 52.sp, stepSize = 2.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SlipEdge(top = false)
        }

        if (forTotal) {
            val cents = state.cents
            YellowButton(
                if (cents != null) "Use ${Money.format(cents)}" else "Work out an amount to use it",
                onUse,
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                enabled = cents != null,
            )
        }
        Keypad(onKey, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp))
    }
}

@Composable
private fun TapeRow(line: TapeLine) {
    val c = LocalSlip.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End) {
        Text(line.expression, style = Type.small, color = c.inkFaded, textAlign = TextAlign.End)
        Text("= ${line.result}", style = Type.strong, color = c.ink, textAlign = TextAlign.End)
    }
}

@Composable
private fun Keypad(onKey: (CalcKey) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    val digit = { d: Char -> onKey(CalcKey.Digit(d)) }
    val op = { o: Char -> onKey(CalcKey.Op(o)) }
    val spacing = Arrangement.spacedBy(6.dp)
    Column(modifier, verticalArrangement = spacing) {
        Row(horizontalArrangement = spacing) {
            CalcButton("C", c.paperShade, c.ink, { onKey(CalcKey.Clear) })
            CalcButton("<", c.paperShade, c.ink, { onKey(CalcKey.Backspace) }, icon = R.drawable.ic_backspace, description = "Delete")
            CalcButton("%", c.paperShade, c.ink, { onKey(CalcKey.Percent) })
            CalcButton(DIVIDE.toString(), c.counterDeep, c.onCounter, { op(DIVIDE) })
        }
        for ((a, b, d, o) in listOf(listOf('7', '8', '9', TIMES), listOf('4', '5', '6', MINUS), listOf('1', '2', '3', PLUS))) {
            Row(horizontalArrangement = spacing) {
                CalcButton(a.toString(), c.paper, c.ink, { digit(a) })
                CalcButton(b.toString(), c.paper, c.ink, { digit(b) })
                CalcButton(d.toString(), c.paper, c.ink, { digit(d) })
                CalcButton(o.toString(), c.counterDeep, c.onCounter, { op(o) })
            }
        }
        Row(horizontalArrangement = spacing) {
            // Keeps the columns lined up: two keys' width plus the gap between them.
            CalcButton("0", c.paper, c.ink, { digit('0') }, width = 2.04f)
            CalcButton(".", c.paper, c.ink, { onKey(CalcKey.Dot) })
            CalcButton("=", c.highlighter, c.onHighlighter, { onKey(CalcKey.Equals) })
        }
    }
}

@Composable
private fun RowScope.CalcButton(
    label: String,
    fill: Color,
    text: Color,
    onClick: () -> Unit,
    width: Float = 1f,
    @DrawableRes icon: Int? = null,
    description: String? = null,
) {
    Box(
        Modifier
            .weight(width)
            .height(58.dp)
            .background(fill, RoundedCornerShape(5.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("key $label"),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), description, tint = text, modifier = Modifier.size(26.dp))
        } else {
            Text(label, style = Type.title, color = text)
        }
    }
}
