package com.moblized.spendscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.Draft
import com.moblized.spendscan.data.DraftItem
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.parse.ReceiptField
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** Check what the phone read before it goes in the books. Anything it wasn't sure of has the highlighter on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    draft: Draft,
    categories: List<Category>,
    photoFile: (String) -> File,
    onChange: (Draft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    var pickingDate by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var showExtras by rememberSaveable {
        mutableStateOf(
            listOf(draft.subtotalText, draft.taxText, draft.tipText, draft.discountText, draft.payment, draft.note).any { it.isNotBlank() },
        )
    }
    var showText by rememberSaveable { mutableStateOf(false) }

    val title = when {
        draft.receiptId != null -> "Edit receipt"
        draft.photos.isNotEmpty() -> "Check this receipt"
        else -> "Add a receipt"
    }

    Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Back", onBack)
            Text(title, style = Type.lead, color = c.onCounter, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
        ) {
            Slip {
                draft.readNote?.let {
                    Text(it, style = Type.small, color = c.inkFaded)
                    Spacer(Modifier.height(10.dp))
                }
                if (draft.readFailed) {
                    Text(
                        "No text could be read from this photo. Fill in the store and total yourself, or go back and scan it again in better light.",
                        style = Type.body,
                        color = c.onHighlighter,
                        modifier = Modifier.background(c.highlighter, RoundedCornerShape(3.dp)).padding(10.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (draft.photos.isNotEmpty()) {
                    PhotoStrip(draft.photos.map(photoFile)) { viewing = it }
                    Spacer(Modifier.height(14.dp))
                }

                PaperField(
                    "Store",
                    draft.store,
                    { onChange(draft.copy(store = it, unsure = draft.unsure - ReceiptField.STORE)) },
                    flagged = ReceiptField.STORE in draft.unsure,
                    placeholder = "Where was this?",
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PaperField(
                        "Total",
                        draft.totalText,
                        { onChange(draft.copy(totalText = it, unsure = draft.unsure - ReceiptField.TOTAL)) },
                        Modifier.weight(1f),
                        flagged = ReceiptField.TOTAL in draft.unsure,
                        keyboardType = KeyboardType.Decimal,
                        style = Type.lead,
                        placeholder = "0.00",
                    )
                    DateButton(draft.date, ReceiptField.DATE in draft.unsure, { pickingDate = true }, Modifier.weight(1f))
                }

                Spacer(Modifier.height(14.dp))
                Text("Category", style = Type.small, color = c.inkFaded)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        Chip(cat.name, draft.categoryId == cat.id, { onChange(draft.copy(categoryId = cat.id)) }, onPaper = true)
                    }
                }

                SlipRule()
                ItemsEditor(draft, onChange)
                SlipRule()

                if (showExtras) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PaperField("Subtotal", draft.subtotalText, { onChange(draft.copy(subtotalText = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                        PaperField("Tax", draft.taxText, { onChange(draft.copy(taxText = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PaperField("Tip", draft.tipText, { onChange(draft.copy(tipText = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                        PaperField("Discount", draft.discountText, { onChange(draft.copy(discountText = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Decimal)
                    }
                    Spacer(Modifier.height(10.dp))
                    PaperField("Paid with", draft.payment, { onChange(draft.copy(payment = it)) }, placeholder = "Visa 4242")
                    Spacer(Modifier.height(10.dp))
                    PaperField("Note", draft.note, { onChange(draft.copy(note = it)) }, singleLine = false)
                } else {
                    PaperButton("Add tax, tip, payment or a note", { showExtras = true }, Modifier.fillMaxWidth())
                }

                if (!draft.rawText.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (showText) "Hide the text the phone read" else "Show the text the phone read",
                        style = Type.small,
                        color = c.inkFaded,
                        modifier = Modifier.fillMaxWidth().clickable { showText = !showText }.padding(vertical = 8.dp),
                    )
                    if (showText) {
                        Text(draft.rawText, style = Type.small, color = c.ink)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.counterDeep).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!draft.canSave) {
                Text(
                    when {
                        draft.store.isBlank() && draft.totalCents == null -> "Add the store and total to save."
                        draft.store.isBlank() -> "Add the store to save."
                        draft.totalCents == null -> "Add the total to save."
                        else -> "Pick a category to save."
                    },
                    style = Type.small,
                    color = c.onCounterMuted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            YellowButton("Save receipt", onSave, Modifier.fillMaxWidth(), enabled = draft.canSave)
        }
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }

    if (pickingDate) {
        PickDate(draft.date, onPick = {
            onChange(draft.copy(date = it, unsure = draft.unsure - ReceiptField.DATE))
            pickingDate = false
        }, onDismiss = { pickingDate = false })
    }
}

@Composable
private fun DateButton(date: LocalDate, flagged: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSlip.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Date", style = Type.small, color = c.inkFaded)
            if (flagged) {
                Spacer(Modifier.size(8.dp))
                Text("check this", style = Type.small.copy(fontWeight = FontWeight.Bold), color = c.ink)
            }
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (flagged) c.highlighter else c.paperShade, RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(date.full(), style = Type.lead, color = if (flagged) c.onHighlighter else c.ink, maxLines = 1)
        }
    }
}

@Composable
private fun ItemsEditor(draft: Draft, onChange: (Draft) -> Unit) {
    val c = LocalSlip.current
    Text(if (draft.items.isEmpty()) "Items" else "Items (${draft.items.size})", style = Type.small, color = c.inkFaded)
    Spacer(Modifier.height(4.dp))
    draft.items.forEach { item ->
        ItemRow(
            item,
            onChange = { changed -> onChange(draft.copy(items = draft.items.map { if (it.key == item.key) changed else it })) },
            onRemove = { onChange(draft.copy(items = draft.items.filterNot { it.key == item.key })) },
        )
        Spacer(Modifier.height(6.dp))
    }
    if (draft.itemsOffByCents != 0L) {
        val off = Money.format(abs(draft.itemsOffByCents))
        Text(
            if (draft.itemsOffByCents > 0) "The items add up to $off more than the receipt says." else "The items add up to $off less than the receipt says.",
            style = Type.small,
            color = c.onHighlighter,
            modifier = Modifier.background(c.highlighter, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
    PaperButton("Add an item", {
        val key = (draft.items.maxOfOrNull { it.key } ?: -1) + 1
        onChange(draft.copy(items = draft.items + DraftItem(key, "")))
    }, Modifier.fillMaxWidth())
}

@Composable
private fun ItemRow(item: DraftItem, onChange: (DraftItem) -> Unit, onRemove: () -> Unit) {
    val c = LocalSlip.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaperField("", item.name, { onChange(item.copy(name = it)) }, Modifier.weight(1f), placeholder = "Item")
        Spacer(Modifier.width(6.dp))
        PaperField("", item.amountText, { onChange(item.copy(amountText = it)) }, Modifier.width(92.dp), keyboardType = KeyboardType.Decimal, placeholder = "0.00")
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_close), "Remove ${item.name}", tint = c.inkFaded, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val c = LocalSlip.current
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) onPick(LocalDate.ofEpochDay(millis / 86_400_000L)) else onDismiss()
            }) { Text("Set date", color = c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink) } },
    ) {
        DatePicker(state = state)
    }
}
