package com.moblized.spendscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
    var editingId by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
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
                    if (editingId == cat.id) {
                        NameEditor(
                            initial = cat.name,
                            confirm = "Save",
                            onConfirm = {
                                onRename(cat, it)
                                editingId = null
                            },
                            onCancel = { editingId = null },
                            onDelete = if (cat.fixed) null else ({
                                onDelete(cat)
                                editingId = null
                            }),
                            hint = if (cat.fixed) "Receipts from deleted categories land here, so this one stays." else "Deleting moves its receipts to Other.",
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        adding = false
                                        editingId = cat.id
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
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
                }
                Spacer(Modifier.height(14.dp))
                if (adding) {
                    NameEditor(
                        initial = "",
                        confirm = "Add",
                        onConfirm = {
                            onAdd(it)
                            adding = false
                        },
                        onCancel = { adding = false },
                    )
                } else {
                    PaperButton("Add a category", {
                        editingId = null
                        adding = true
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Tap a category's name to rename or delete it.", style = Type.small, color = c.inkFaded)
                }
            }
        }
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

/** Names a category right there on the slip. */
@Composable
private fun NameEditor(
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    hint: String? = null,
) {
    val c = LocalSlip.current
    var name by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth()) {
        PaperField("Category name", name, { name = it }, placeholder = "Coffee, Kids, Vet")
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = Type.small, color = c.inkFaded)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperButton(confirm, { onConfirm(name.trim()) }, Modifier.weight(1f), enabled = name.isNotBlank())
            PaperButton("Cancel", onCancel, Modifier.weight(1f))
            if (onDelete != null) PaperButton("Delete", onDelete, Modifier.weight(1f), color = c.stamp)
        }
    }
}
