package com.moblized.spendscan.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.R
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import kotlin.math.max
import kotlin.math.roundToInt

/** The torn edge of a till slip: the top points up, the bottom points down. */
@Composable
fun SlipEdge(top: Boolean, modifier: Modifier = Modifier) {
    val paper = LocalSlip.current.paper
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        val toothCount = max(1, (size.width / 14.dp.toPx()).roundToInt())
        val tooth = size.width / toothCount
        val h = size.height
        val path = Path()
        if (top) {
            path.moveTo(0f, h + 1f)
            path.lineTo(0f, h)
            for (i in 0 until toothCount) {
                path.lineTo(i * tooth + tooth / 2, 0f)
                path.lineTo((i + 1) * tooth, h)
            }
            path.lineTo(size.width, h + 1f)
        } else {
            path.moveTo(0f, -1f)
            path.lineTo(0f, 0f)
            for (i in 0 until toothCount) {
                path.lineTo(i * tooth + tooth / 2, h)
                path.lineTo((i + 1) * tooth, 0f)
            }
            path.lineTo(size.width, -1f)
        }
        path.close()
        drawPath(path, paper)
    }
}

/** A strip of paper with torn top and bottom edges. */
@Composable
fun Slip(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SlipEdge(top = true)
        Column(
            Modifier.fillMaxWidth().background(LocalSlip.current.paper).padding(horizontal = 20.dp, vertical = 16.dp),
            content = content,
        )
        SlipEdge(top = false)
    }
}

/** The dashed line a till prints between sections. */
@Composable
fun SlipRule(modifier: Modifier = Modifier) {
    val ink = LocalSlip.current.inkFaded
    Canvas(modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp)) {
        drawLine(
            color = ink,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )
    }
}

/** Label on the left, amount on the right, printed dots between them. Long labels get cut short, never the amount. */
@Composable
fun LeaderRow(
    label: String,
    amount: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    amountStyle: TextStyle = style,
    labelColor: Color = LocalSlip.current.ink,
    amountColor: Color = LocalSlip.current.ink,
) {
    val dots = LocalSlip.current.inkFaded
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Text(label, style = style, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier) {
                val step = 6.dp.toPx()
                val y = size.height * 0.7f
                var x = step / 2
                while (x < size.width) {
                    drawCircle(dots, radius = 0.9.dp.toPx(), center = Offset(x, y))
                    x += step
                }
            }
            Text(amount, style = amountStyle, color = amountColor, maxLines = 1)
        },
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val width = constraints.maxWidth
        val amountP = measurables[2].measure(Constraints(maxWidth = width))
        val labelP = measurables[0].measure(Constraints(maxWidth = (width - amountP.width - gap * 2).coerceAtLeast(0)))
        val h = max(labelP.height, amountP.height)
        val dotsP = measurables[1].measure(Constraints.fixed((width - labelP.width - amountP.width - gap * 2).coerceAtLeast(0), h))
        layout(width, h) {
            labelP.place(0, h - labelP.height)
            dotsP.place(labelP.width + gap, 0)
            amountP.place(width - amountP.width, h - amountP.height)
        }
    }
}

/** How much of a budget is used, as a row of printed ticks. Text next to it always says the same thing in words. */
@Composable
fun TickMeter(fraction: Float, over: Boolean, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    val on = if (over) c.stamp else c.ink
    val off = c.inkFaded.copy(alpha = 0.3f)
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        val n = 40
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val lit = if (over) n else (fraction.coerceIn(0f, 1f) * n).let { f -> if (f > 0f && f < 1f) 1 else f.roundToInt() }
        for (i in 0 until n) {
            drawRect(if (i < lit) on else off, topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height))
        }
    }
}

/** The rubber stamp: only used for going over a budget. */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    Text(
        text,
        style = Type.small.copy(fontWeight = FontWeight.Bold),
        color = c.stamp,
        modifier = modifier
            .rotate(-6f)
            .border(1.5.dp, c.stamp, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 0.dp),
    )
}

@Composable
fun YellowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    val c = LocalSlip.current
    Row(
        modifier
            .defaultMinSize(minHeight = 50.dp)
            .background(if (enabled) c.highlighter else c.paperShade, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = if (enabled) c.onHighlighter else c.inkFaded, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = Type.strong, color = if (enabled) c.onHighlighter else c.inkFaded, textAlign = TextAlign.Center)
    }
}

/** A quieter action printed on the paper. */
@Composable
fun PaperButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = LocalSlip.current.ink) {
    Box(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .border(1.dp, color, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
fun CounterIcon(@DrawableRes icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = LocalSlip.current
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), description, tint = if (enabled) c.onCounter else c.onCounterMuted.copy(alpha = 0.4f))
    }
}

/** A filter or choice on the counter or on paper. Picked ones get the highlighter. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, onPaper: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    val line = if (onPaper) c.ink else c.onCounter
    val textColor = if (selected) c.onHighlighter else line
    Box(
        modifier
            .defaultMinSize(minHeight = 38.dp)
            .then(
                if (selected) Modifier.background(c.highlighter, RoundedCornerShape(5.dp))
                else Modifier.border(1.dp, line.copy(alpha = 0.55f), RoundedCornerShape(5.dp)),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body, color = textColor, maxLines = 1)
    }
}

/**
 * A field on the paper. [flagged] marks a value the phone wasn't sure of with the highlighter, and says so in words.
 */
@Composable
fun PaperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    flagged: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    style: TextStyle = Type.body,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalSlip.current
    Column(modifier) {
        if (label.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Type.small, color = c.inkFaded)
                if (flagged) {
                    Spacer(Modifier.size(8.dp))
                    Text("check this", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.ink)
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        val fill = if (flagged) c.highlighter else c.paperShade
        val textColor = if (flagged) c.onHighlighter else c.ink
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = style.copy(color = textColor),
            cursorBrush = SolidColor(textColor),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(fill, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = style, color = if (flagged) c.onHighlighter.copy(alpha = 0.7f) else c.inkFaded, maxLines = 1)
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}

/** The bar along the bottom. Scanning is the main thing the app does, so it gets the highlighter. */
@Composable
fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    Row(
        modifier.fillMaxWidth().background(c.counterDeep).navigationBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarItem(R.drawable.ic_month, "Month", tab == Tab.MONTH, { onTab(Tab.MONTH) }, Modifier.weight(1f))
        BarItem(R.drawable.ic_receipts, "Receipts", tab == Tab.RECEIPTS, { onTab(Tab.RECEIPTS) }, Modifier.weight(1f))
        Column(
            Modifier
                .weight(1.1f)
                .padding(horizontal = 4.dp)
                .background(c.highlighter, RoundedCornerShape(5.dp))
                .clickable(role = Role.Button, onClick = onScan)
                .padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_scan), null, tint = c.onHighlighter, modifier = Modifier.size(24.dp))
            Text("Scan", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.onHighlighter)
        }
        BarItem(R.drawable.ic_fuel, "Fuel", tab == Tab.FUEL, { onTab(Tab.FUEL) }, Modifier.weight(1f))
        BarItem(R.drawable.ic_budget, "Budgets", tab == Tab.BUDGETS, { onTab(Tab.BUDGETS) }, Modifier.weight(1f))
    }
}

@Composable
private fun BarItem(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalSlip.current
    val tint = if (selected) c.onCounter else c.onCounterMuted
    Column(
        modifier.clickable(role = Role.Tab, onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = Type.small.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = tint,
            maxLines = 1,
        )
    }
}
