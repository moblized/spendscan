package com.moblized.spendscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type
import java.time.YearMonth

@Composable
fun BudgetsScreen(
    categories: List<Category>,
    spent: Map<Long, Long>,
    month: YearMonth,
    overallBudgetCents: Long?,
    onOverall: (Long?) -> Unit,
    onBudget: (Category, Long?) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Category) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Text("Budgets", style = Type.lead, color = c.onCounter, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 12.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Slip {
                val sum = categories.mapNotNull { it.budgetCents }.takeIf { it.isNotEmpty() }?.sum()
                BudgetInput(
                    label = "Whole month",
                    cents = overallBudgetCents,
                    placeholder = sum?.let { Money.plain(it) } ?: "No limit",
                    onCents = onOverall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (sum != null) "Leave this empty to use the categories below, which add up to ${Money.short(sum)}."
                    else "Set a limit for the month, for each category, or both.",
                    style = Type.small,
                    color = c.inkFaded,
                )
                SlipRule()
                categories.forEachIndexed { i, cat ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = cat }.padding(vertical = 4.dp)) {
                            Text(cat.name, style = Type.strong, color = c.ink, maxLines = 1)
                            Text(
                                "${Money.format(spent[cat.id] ?: 0)} in ${month.name()}",
                                style = Type.small,
                                color = c.inkFaded,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        BudgetField(cat.budgetCents, "No limit", { onBudget(cat, it) }, Modifier.width(112.dp), key = cat.id)
                    }
                }
                Spacer(Modifier.height(14.dp))
                PaperButton("Add a category", { adding = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Tap a category's name to rename or delete it.", style = Type.small, color = c.inkFaded)
            }
        }
    }

    editing?.let { cat ->
        NameDialog(
            title = "Edit ${cat.name}",
            initial = cat.name,
            confirm = "Save",
            onConfirm = {
                onRename(cat, it)
                editing = null
            },
            onDismiss = { editing = null },
            deleteLabel = if (cat.fixed) null else "Delete",
            onDelete = {
                onDelete(cat)
                editing = null
            },
            hint = if (cat.fixed) "Receipts from deleted categories land here, so this one stays." else "Deleting moves its receipts to Other.",
        )
    }
    if (adding) {
        NameDialog(
            title = "New category",
            initial = "",
            confirm = "Add",
            onConfirm = {
                onAdd(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun BudgetInput(label: String, cents: Long?, placeholder: String, onCents: (Long?) -> Unit) {
    val c = LocalSlip.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.strong, color = c.ink, modifier = Modifier.weight(1f))
        BudgetField(cents, placeholder, onCents, Modifier.width(112.dp), key = "overall")
    }
}

/** Keeps what's being typed as typed; saves whenever it's a real amount, and clears the budget when emptied. */
@Composable
private fun BudgetField(cents: Long?, placeholder: String, onCents: (Long?) -> Unit, modifier: Modifier = Modifier, key: Any) {
    var text by remember(key) { mutableStateOf(cents?.let { Money.plain(it) }.orEmpty()) }
    PaperField(
        label = "",
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) onCents(null) else Money.parse(it)?.let(onCents)
        },
        modifier = modifier,
        keyboardType = KeyboardType.Decimal,
        placeholder = placeholder,
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    deleteLabel: String? = null,
    onDelete: () -> Unit = {},
    hint: String? = null,
) {
    val c = LocalSlip.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PaperField("Name", name, { name = it })
                if (hint != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(hint, style = Type.small, color = c.inkFaded)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm, color = c.ink) }
        },
        dismissButton = {
            Row {
                if (deleteLabel != null) TextButton(onClick = onDelete) { Text(deleteLabel, color = c.stamp) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = c.ink) }
            }
        },
    )
}
