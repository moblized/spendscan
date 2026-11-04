package com.moblized.spendscan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.ReceiptFilter
import com.moblized.spendscan.data.ThemeChoice
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.SpendTheme
import com.moblized.spendscan.ui.theme.Type

@Composable
fun AppRoot(
    model: AppModel,
    onScan: () -> Unit,
    onPickPhoto: () -> Unit,
    onDarkChange: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    LaunchedEffect(dark) { onDarkChange(dark) }

    SpendTheme(dark) {
        val c = LocalSlip.current
        val stack by model.stack.collectAsStateWithLifecycle()
        val tab by model.tab.collectAsStateWithLifecycle()
        val reading by model.reading.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }

        BackHandler(enabled = stack.size > 1 || tab != Tab.MONTH) { if (!model.back()) onExit() }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                model.message.value = null
            }
        }

        Box(Modifier.fillMaxSize().background(c.counter)) {
            when (val route = stack.last()) {
                Route.Tabs -> Tabs(model, tab, onScan)
                Route.Review -> ReviewRoute(model)
                is Route.Detail -> DetailRoute(model, route.id)
            }

            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            ) { data ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    containerColor = c.ink,
                    contentColor = c.paper,
                ) { Text(data.visuals.message, style = Type.body, color = c.paper) }
            }

            if (reading != ReadingStage.NONE) ReadingOverlay(reading, model::usePhoneReading)
        }

        Dialogs(model, onPickPhoto)
    }
}

@Composable
private fun Tabs(model: AppModel, tab: Tab, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.MONTH -> MonthRoute(model, onScan)
                Tab.RECEIPTS -> ReceiptsRoute(model, onScan)
                Tab.BUDGETS -> BudgetsRoute(model)
                Tab.SETTINGS -> SettingsRoute(model)
            }
        }
        BottomBar(tab, model::selectTab, onScan)
    }
}

@Composable
private fun MonthRoute(model: AppModel, onScan: () -> Unit) {
    val summary by model.summary.collectAsStateWithLifecycle()
    val print by model.printSlip.collectAsStateWithLifecycle()
    val s = summary ?: return
    MonthScreen(
        summary = s,
        animateIn = print,
        onPrinted = { model.printSlip.value = false },
        onPrev = { model.shiftMonth(-1) },
        onNext = { model.shiftMonth(1) },
        onCategory = { model.showReceipts(ReceiptFilter(categoryId = it, month = s.month)) },
        onStore = { model.showReceipts(ReceiptFilter(store = it, month = s.month)) },
        onDay = { model.showReceipts(ReceiptFilter(day = s.month.atDay(it))) },
        onAllReceipts = { model.showReceipts(ReceiptFilter(month = s.month)) },
        onScan = onScan,
        onAddByHand = model::addByHand,
    )
}

@Composable
private fun ReceiptsRoute(model: AppModel, onScan: () -> Unit) {
    val rows by model.rows.collectAsStateWithLifecycle()
    val stats by model.itemStats.collectAsStateWithLifecycle()
    val filter by model.filter.collectAsStateWithLifecycle()
    val categories by model.categories.collectAsStateWithLifecycle()
    val total by model.receiptCount.collectAsStateWithLifecycle()
    ReceiptsScreen(
        rows = rows,
        itemStats = stats,
        filter = filter,
        categories = categories,
        hasAnyReceipts = total > 0,
        onFilter = { model.filter.value = it },
        onOpen = { model.open(Route.Detail(it)) },
        onAddByHand = model::addByHand,
        onScan = onScan,
    )
}

@Composable
private fun BudgetsRoute(model: AppModel) {
    val categories by model.categories.collectAsStateWithLifecycle()
    val spent by model.spentThisMonth.collectAsStateWithLifecycle()
    val overall by model.overallBudget.collectAsStateWithLifecycle()
    BudgetsScreen(
        categories = categories,
        spent = spent,
        month = java.time.YearMonth.now(),
        overallBudgetCents = overall,
        onOverall = model::setOverallBudget,
        onBudget = { c, cents -> model.setBudget(c, cents) },
        onRename = { c, name -> model.renameCategory(c, name) },
        onDelete = { model.deleteCategory(it) },
        onAdd = { model.addCategory(it) },
    )
}

@Composable
private fun SettingsRoute(model: AppModel) {
    val theme by model.theme.collectAsStateWithLifecycle()
    val total by model.receiptCount.collectAsStateWithLifecycle()
    val status by model.updateStatus.collectAsStateWithLifecycle()
    val server by model.server.collectAsStateWithLifecycle()
    val serverModels by model.serverModels.collectAsStateWithLifecycle()
    val serverStatus by model.serverStatus.collectAsStateWithLifecycle()
    SettingsScreen(
        theme = theme,
        onTheme = model::setTheme,
        receiptCount = total,
        onExport = model::exportCsv,
        version = model.updater.installedVersionName,
        updateStatus = status,
        onCheckUpdates = model::checkForUpdates,
        server = server,
        onServer = model::setServer,
        serverModels = serverModels,
        serverStatus = serverStatus,
        onFindModels = model::findModels,
    )
}

@Composable
private fun DetailRoute(model: AppModel, id: Long) {
    val receiptFlow = remember(id) { model.receipt(id) }
    val itemsFlow = remember(id) { model.items(id) }
    val receipt by receiptFlow.collectAsState(initial = null)
    val items by itemsFlow.collectAsState(initial = emptyList())
    val categories by model.categories.collectAsStateWithLifecycle()
    val r = receipt ?: return
    ReceiptDetailScreen(
        receipt = r,
        items = items,
        categoryName = categories.firstOrNull { it.id == r.categoryId }?.name.orEmpty(),
        photoFile = model::photoFile,
        onBack = { model.back() },
        onEdit = { model.edit(id) },
        onDelete = { model.deleteReceipt(id) },
    )
}

@Composable
private fun ReviewRoute(model: AppModel) {
    val draft by model.draft.collectAsStateWithLifecycle()
    val categories by model.categories.collectAsStateWithLifecycle()
    val d = draft ?: return
    ReviewScreen(
        draft = d,
        categories = categories,
        photoFile = model::photoFile,
        onChange = model::updateDraft,
        onSave = { model.saveDraft() },
        onBack = { model.back() },
    )
}

@Composable
private fun ReadingOverlay(stage: ReadingStage, onUsePhone: () -> Unit) {
    val c = LocalSlip.current
    val asking = stage == ReadingStage.SERVER
    Box(Modifier.fillMaxSize().background(c.counter), contentAlignment = Alignment.Center) {
        Slip(Modifier.padding(horizontal = 40.dp)) {
            Text(
                if (asking) "Asking your server" else "Reading your receipt",
                style = Type.lead,
                color = c.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (asking) "This can take a minute if the model has to load first."
                else "The first scan can take up to a minute while the phone gets its text reader.",
                style = Type.small,
                color = c.inkFaded,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.ink, trackColor = c.paperShade)
            if (asking) {
                Spacer(Modifier.height(12.dp))
                PaperButton("Use the phone's reading", onUsePhone, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Dialogs(model: AppModel, onPickPhoto: () -> Unit) {
    val c = LocalSlip.current
    val discard by model.confirmDiscard.collectAsStateWithLifecycle()
    val duplicate by model.duplicateWarning.collectAsStateWithLifecycle()
    val noScanner by model.scannerUnavailable.collectAsStateWithLifecycle()
    val update by model.update.collectAsStateWithLifecycle()
    val progress by model.updateProgress.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()

    if (discard) {
        val isNew = draft?.receiptId == null
        AlertDialog(
            onDismissRequest = { model.confirmDiscard.value = false },
            title = { Text(if (isNew) "Discard this receipt?" else "Discard your changes?") },
            text = { Text(if (isNew) "It won't be saved, and its photo is deleted." else "The receipt stays as it was.") },
            confirmButton = { TextButton(onClick = model::discardDraft) { Text("Discard", color = c.stamp) } },
            dismissButton = { TextButton(onClick = { model.confirmDiscard.value = false }) { Text("Keep editing", color = c.ink) } },
        )
    }
    if (duplicate) {
        val d = draft
        AlertDialog(
            onDismissRequest = { model.duplicateWarning.value = false },
            title = { Text("Already saved?") },
            text = {
                Text("You have a receipt from ${d?.store} on ${d?.date?.full()} for ${d?.totalCents?.let { Money.format(it) }}.")
            },
            confirmButton = { TextButton(onClick = { model.saveDraft(allowDuplicate = true) }) { Text("Save anyway", color = c.ink) } },
            dismissButton = { TextButton(onClick = model::discardDraft) { Text("Discard this one", color = c.stamp) } },
        )
    }
    if (noScanner) {
        AlertDialog(
            onDismissRequest = { model.scannerUnavailable.value = false },
            title = { Text("The scanner didn't start") },
            text = { Text("It needs Google Play services. You can still read a receipt from a photo you already took.") },
            confirmButton = {
                TextButton(onClick = {
                    model.scannerUnavailable.value = false
                    onPickPhoto()
                }) { Text("Choose a photo", color = c.ink) }
            },
            dismissButton = { TextButton(onClick = { model.scannerUnavailable.value = false }) { Text("Cancel", color = c.ink) } },
        )
    }
    update?.let { release ->
        AlertDialog(
            onDismissRequest = { if (progress == null) model.snoozeUpdate() },
            title = { Text("SpendScan ${release.versionName} is ready") },
            text = {
                Column {
                    if (release.notes.isNotBlank()) {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            Text(release.notes, style = Type.small)
                        }
                    }
                    progress?.let {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = c.ink, trackColor = c.paperShade)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = model::installUpdate, enabled = progress == null) {
                    Text(if (progress == null) "Update now" else "Downloading...", color = c.ink)
                }
            },
            dismissButton = {
                if (progress == null) TextButton(onClick = model::snoozeUpdate) { Text("Later", color = c.ink) }
            },
        )
    }
}
