package com.moblized.spendscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.moblized.spendscan.R
import com.moblized.spendscan.data.FillUp
import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.ReceiptItem
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.io.File

fun quantityPrefix(q: Double): String = when {
    q == 1.0 -> ""
    q % 1.0 == 0.0 -> "${q.toLong()} x "
    else -> "$q x "
}

/** A saved receipt, reprinted cleanly, with the photo behind it. */
@Composable
fun ReceiptDetailScreen(
    receipt: Receipt,
    items: List<ReceiptItem>,
    categoryName: String,
    photoFile: (String) -> File,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    fillUp: FillUp? = null,
) {
    val c = LocalSlip.current
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Back", onBack)
            Spacer(Modifier.weight(1f))
            CounterIcon(R.drawable.ic_edit, "Edit receipt", onEdit)
            CounterIcon(R.drawable.ic_delete, "Delete receipt", { confirmDelete = true })
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
        ) {
            Slip {
                Text(receipt.store, style = Type.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(receipt.date.full(), style = Type.body, color = c.inkFaded, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        categoryName,
                        style = Type.small,
                        color = c.onHighlighter,
                        modifier = Modifier.background(c.highlighter, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
                if (items.isNotEmpty()) {
                    SlipRule()
                    items.forEach { item ->
                        LeaderRow(quantityPrefix(item.quantity) + item.name, Money.format(item.amountCents), Modifier.padding(vertical = 3.dp))
                    }
                }
                val hasBreakdown = receipt.subtotalCents != null || receipt.taxCents != null || receipt.tipCents != null || receipt.discountCents != null
                if (hasBreakdown) {
                    SlipRule()
                    receipt.subtotalCents?.let { LeaderRow("Subtotal", Money.format(it), Modifier.padding(vertical = 2.dp)) }
                    receipt.discountCents?.let { LeaderRow("Discount", "-" + Money.format(it), Modifier.padding(vertical = 2.dp)) }
                    receipt.taxCents?.let { LeaderRow("Tax", Money.format(it), Modifier.padding(vertical = 2.dp)) }
                    receipt.tipCents?.let { LeaderRow("Tip", Money.format(it), Modifier.padding(vertical = 2.dp)) }
                }
                SlipRule()
                LeaderRow("Total", Money.format(receipt.totalCents), style = Type.strong, amountStyle = Type.figureSmall)
                receipt.payment?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Paid with $it", style = Type.small, color = c.inkFaded)
                }
                fillUp?.let { fill ->
                    SlipRule()
                    fill.gallonsMilli?.let { LeaderRow("Gallons", FuelUnits.gallons(it), Modifier.padding(vertical = 2.dp)) }
                    val price = fill.priceMilli ?: fill.gallonsMilli?.let { FuelUnits.paidPerGallon(receipt.totalCents, it) }
                    price?.let { LeaderRow("Per gallon", FuelUnits.pricePerGallon(it), Modifier.padding(vertical = 2.dp)) }
                    fill.odometer?.let { LeaderRow("Odometer", "${FuelUnits.odometer(it)} mi", Modifier.padding(vertical = 2.dp)) }
                    LeaderRow("Tank", if (fill.fullTank) "Filled" else "Partial", Modifier.padding(vertical = 2.dp))
                }
                receipt.note?.let {
                    SlipRule()
                    Text(it, style = Type.body, color = c.ink)
                }
                if (receipt.photoList.isNotEmpty()) {
                    SlipRule()
                    Text("Photo", style = Type.small, color = c.inkFaded)
                    Spacer(Modifier.height(6.dp))
                    PhotoStrip(receipt.photoList.map(photoFile)) { viewing = it }
                }
            }
        }
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this receipt?") },
            text = { Text("${receipt.store}, ${Money.format(receipt.totalCents)} comes out of your totals and its photo is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = c.stamp) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = c.ink) } },
        )
    }
}

@Composable
fun PhotoStrip(files: List<File>, onOpen: (File) -> Unit) {
    val c = LocalSlip.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            AsyncImage(
                model = file,
                contentDescription = "Receipt photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 76.dp, height = 104.dp)
                    .background(c.paperShade, RoundedCornerShape(3.dp))
                    .border(1.dp, c.inkFaded.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
                    .clickable { onOpen(file) },
            )
        }
    }
}

/** Full screen, with pinch to zoom, for checking the small print. */
@Composable
fun PhotoViewer(file: File, onClose: () -> Unit) {
    val c = LocalSlip.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.counterDeep)) {
            AsyncImage(
                model = file,
                contentDescription = "Receipt photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
                CounterIcon(R.drawable.ic_close, "Close photo", onClose)
            }
        }
    }
}
