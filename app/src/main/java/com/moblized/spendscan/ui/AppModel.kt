package com.moblized.spendscan.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moblized.spendscan.App
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.Draft
import com.moblized.spendscan.data.FALLBACK_CATEGORY
import com.moblized.spendscan.data.ItemHit
import com.moblized.spendscan.data.ItemStats
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.data.MonthSummary
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.ReceiptFilter
import com.moblized.spendscan.data.ReceiptItem
import com.moblized.spendscan.data.ReceiptRow
import com.moblized.spendscan.data.ThemeChoice
import com.moblized.spendscan.data.budgetNote
import com.moblized.spendscan.export.Csv
import com.moblized.spendscan.parse.Chains
import com.moblized.spendscan.parse.ReceiptParser
import com.moblized.spendscan.scan.ReceiptOcr
import com.moblized.spendscan.update.AppRelease
import com.moblized.spendscan.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

enum class Tab { MONTH, RECEIPTS, BUDGETS, SETTINGS }

sealed interface Route {
    data object Tabs : Route
    data object Review : Route
    data class Detail(val id: Long) : Route
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val dao = app.dao
    private val prefs = app.prefs
    val updater = app.updater

    // Navigation
    val stack = MutableStateFlow<List<Route>>(listOf(Route.Tabs))
    val tab = MutableStateFlow(Tab.MONTH)

    fun selectTab(t: Tab) {
        tab.value = t
    }

    fun open(route: Route) {
        stack.value = stack.value + route
    }

    private fun pop() {
        if (stack.value.size > 1) stack.value = stack.value.dropLast(1)
    }

    /** The back button or arrow. Returns false when there's nothing left to go back to. */
    fun back(): Boolean {
        if (stack.value.last() == Route.Review) {
            if (draft.value != draftStart) confirmDiscard.value = true else discardDraft()
            return true
        }
        if (stack.value.size > 1) {
            pop()
            return true
        }
        if (tab.value != Tab.MONTH) {
            tab.value = Tab.MONTH
            return true
        }
        return false
    }

    // Theme
    val theme: StateFlow<ThemeChoice> = prefs.theme
    fun setTheme(choice: ThemeChoice) = prefs.setTheme(choice)

    // Month
    val month = MutableStateFlow(YearMonth.now())
    /** Set when the month slip should feed out again, as when a receipt was just added. */
    val printSlip = MutableStateFlow(true)

    val categories: StateFlow<List<Category>> = dao.categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val overallBudget: StateFlow<Long?> = prefs.overallBudget

    private fun range(m: YearMonth) = m.atDay(1).toEpochDay() to m.atEndOfMonth().toEpochDay()

    val summary: StateFlow<MonthSummary?> = month.flatMapLatest { m ->
        val (from, to) = range(m)
        val (prevFrom, prevTo) = range(m.minusMonths(1))
        combine(
            dao.receiptsBetween(from, to),
            dao.receiptsBetween(prevFrom, prevTo),
            categories,
            prefs.overallBudget,
        ) { receipts, previous, cats, budget ->
            MonthSummary.of(m, receipts, previous, cats, budget, LocalDate.now())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** This calendar month's spending per category, for the Budgets screen. */
    val spentThisMonth: StateFlow<Map<Long, Long>> = run {
        val (from, to) = range(YearMonth.now())
        dao.receiptsBetween(from, to).map { rs -> rs.groupBy { it.categoryId }.mapValues { (_, v) -> v.sumOf { it.totalCents } } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun shiftMonth(by: Long) {
        val next = month.value.plusMonths(by)
        if (!next.isAfter(YearMonth.now())) month.value = next
    }

    // Receipts list
    val filter = MutableStateFlow(ReceiptFilter())

    private val itemHits: Flow<List<ItemHit>> = filter.map { it.query.trim() }.distinctUntilChanged().flatMapLatest { q ->
        if (q.length < 2) flowOf(emptyList()) else dao.searchItems(q)
    }

    private val allRows: StateFlow<List<ReceiptRow>> = dao.receiptRows().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val receiptCount: StateFlow<Int> = allRows.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val rows: StateFlow<List<ReceiptRow>> = combine(allRows, filter, itemHits) { all, f, hits ->
        val ids = hits.map { it.receiptId }.toSet()
        if (f.isEmpty) all else all.filter { f.matches(it, ids) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val itemStats: StateFlow<ItemStats?> = combine(filter, itemHits) { f, hits ->
        ItemStats.of(f.query, hits)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun showReceipts(f: ReceiptFilter) {
        filter.value = f
        tab.value = Tab.RECEIPTS
    }

    fun receipt(id: Long): Flow<Receipt?> = dao.receipt(id)
    fun items(id: Long): Flow<List<ReceiptItem>> = dao.items(id)
    fun photoFile(name: String): File = app.photos.file(name)

    // Scanning
    val reading = MutableStateFlow(false)
    val scannerUnavailable = MutableStateFlow(false)

    /** Pages came back from the scanner, or a photo was picked or shared. */
    fun onPages(pages: List<Uri>) {
        if (pages.isEmpty()) return
        reading.value = true
        viewModelScope.launch {
            val photos = app.photos.import(pages)
            val parsed = runCatching {
                val lines = ReceiptOcr.read(app, photos.map(app.photos::file))
                withContext(Dispatchers.Default) { ReceiptParser.parse(ReceiptParser.rows(lines)) }
            }.getOrNull()
            val categoryId = pickCategory(parsed?.store, parsed?.suggestedCategory)
            startDraft(Draft.fromParsed(parsed, photos, LocalDate.now(), categoryId))
            reading.value = false
        }
    }

    /** The category this store went under last time, or failing that, the one it usually belongs in. */
    private suspend fun pickCategory(store: String?, suggested: String?): Long? {
        val cats = dao.categoriesNow()
        val fallback = cats.firstOrNull { it.fixed }?.id ?: cats.firstOrNull { it.name == FALLBACK_CATEGORY }?.id
        if (!store.isNullOrBlank()) {
            dao.lastCategoryFor(store)?.let { last -> if (cats.any { it.id == last }) return last }
        }
        val name = suggested ?: store?.let(Chains::categoryOfStore)
        return cats.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: fallback
    }

    // Reviewing and saving
    val draft = MutableStateFlow<Draft?>(null)
    private var draftStart: Draft? = null
    val confirmDiscard = MutableStateFlow(false)
    val duplicateWarning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private fun startDraft(d: Draft) {
        draft.value = d
        draftStart = d
        open(Route.Review)
    }

    fun addByHand() {
        viewModelScope.launch {
            val cats = dao.categoriesNow()
            startDraft(Draft.blank(LocalDate.now(), cats.firstOrNull { it.fixed }?.id ?: cats.firstOrNull()?.id))
        }
    }

    fun edit(id: Long) {
        viewModelScope.launch {
            val r = dao.receiptNow(id) ?: return@launch
            startDraft(Draft.fromSaved(r, dao.itemsNow(id)))
        }
    }

    fun updateDraft(d: Draft) {
        draft.value = d
    }

    /** Leaves the review, throwing away photos that were never saved to a receipt. */
    fun discardDraft() {
        val d = draft.value
        confirmDiscard.value = false
        duplicateWarning.value = false
        draft.value = null
        draftStart = null
        if (stack.value.last() == Route.Review) pop()
        if (d != null && d.receiptId == null && d.photos.isNotEmpty()) {
            viewModelScope.launch { app.photos.delete(d.photos) }
        }
    }

    fun saveDraft(allowDuplicate: Boolean = false) {
        val d = draft.value ?: return
        if (!d.canSave) return
        viewModelScope.launch {
            val receipt = d.toReceipt(System.currentTimeMillis())
            if (!allowDuplicate && dao.sameReceiptCount(receipt.store, receipt.dateEpochDay, receipt.totalCents, receipt.id) > 0) {
                duplicateWarning.value = true
                return@launch
            }
            duplicateWarning.value = false
            val receiptMonth = YearMonth.from(receipt.date)
            val (from, to) = range(receiptMonth)
            val before = dao.categorySpent(receipt.categoryId, from, to)
            dao.saveReceipt(receipt, d.toItems())
            val after = dao.categorySpent(receipt.categoryId, from, to)
            val category = dao.categoriesNow().firstOrNull { it.id == receipt.categoryId }
            val note = category?.let { budgetNote(it.name, it.budgetCents, before, after) }

            draft.value = null
            draftStart = null
            pop()
            if (d.receiptId == null) {
                // Show where it landed.
                month.value = receiptMonth
                tab.value = Tab.MONTH
                printSlip.value = true
            }
            val saved = "Saved ${receipt.store}, ${Money.format(receipt.totalCents)}."
            message.value = if (note != null) "$saved $note" else saved
        }
    }

    fun deleteReceipt(id: Long) {
        viewModelScope.launch {
            val r = dao.receiptNow(id)
            dao.deleteReceipt(id)
            pop()
            if (r != null) {
                app.photos.delete(r.photoList)
                message.value = "Deleted ${r.store}, ${Money.format(r.totalCents)}."
            }
        }
    }

    // Budgets and categories
    fun setOverallBudget(cents: Long?) = prefs.setOverallBudget(cents)
    fun setBudget(c: Category, cents: Long?) = viewModelScope.launch { dao.updateCategory(c.copy(budgetCents = cents)) }
    fun renameCategory(c: Category, name: String) = viewModelScope.launch { dao.updateCategory(c.copy(name = name)) }

    fun addCategory(name: String) = viewModelScope.launch {
        val cats = dao.categoriesNow()
        dao.insertCategory(Category(name = name, position = (cats.filterNot { it.fixed }.maxOfOrNull { it.position } ?: -1) + 1))
    }

    fun deleteCategory(c: Category) = viewModelScope.launch {
        if (c.fixed) return@launch
        val fallback = dao.categoriesNow().firstOrNull { it.fixed } ?: return@launch
        dao.removeCategory(c, fallback.id)
    }

    // Export
    fun exportCsv() {
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                val receipts = dao.receiptsNow()
                val items = dao.allItemsNow()
                val cats = dao.categoriesNow()
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                val stamp = LocalDate.now()
                val a = File(dir, "spendscan-receipts-$stamp.csv").apply { writeText(Csv.receipts(receipts, cats)) }
                val b = File(dir, "spendscan-items-$stamp.csv").apply { writeText(Csv.items(receipts, items, cats)) }
                listOf(a, b).map { FileProvider.getUriForFile(app, "${app.packageName}.files", it) }
            }
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/csv"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = ClipData.newRawUri("SpendScan export", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(
                Intent.createChooser(send, "Export receipts")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    // Updates
    val update = MutableStateFlow<AppRelease?>(null)
    val updateProgress = MutableStateFlow<Float?>(null)
    val updateStatus = MutableStateFlow<String?>(null)
    private var downloaded: File? = null

    init {
        checkForUpdateIfDue()
    }

    private fun checkForUpdateIfDue() {
        val now = System.currentTimeMillis()
        if (now - prefs.updateCheckedAt < Updates.CHECK_EVERY_MS) return
        viewModelScope.launch {
            val latest = runCatching { updater.fetchLatest() }.getOrNull() ?: return@launch
            prefs.updateCheckedAt = now
            if (latest.versionCode <= updater.installedVersionCode || latest.apkUrl == null) return@launch
            val snoozed = prefs.snoozedVersion == latest.versionCode && now < prefs.snoozedUntil
            if (!snoozed) update.value = latest
        }
    }

    fun checkForUpdates() {
        updateStatus.value = "Checking..."
        viewModelScope.launch {
            val result = runCatching { updater.fetchLatest() }
            val latest = result.getOrNull()
            updateStatus.value = when {
                result.isFailure -> "Couldn't reach GitHub. Check your connection and try again."
                latest == null || latest.apkUrl == null || latest.versionCode <= updater.installedVersionCode -> "You have the latest version."
                else -> {
                    update.value = latest
                    null
                }
            }
        }
    }

    fun snoozeUpdate() {
        val r = update.value ?: return
        prefs.snoozedVersion = r.versionCode
        prefs.snoozedUntil = System.currentTimeMillis() + Updates.SNOOZE_MS
        update.value = null
    }

    fun installUpdate() {
        val r = update.value ?: return
        if (!updater.canInstall()) {
            updater.openInstallPermission()
            return
        }
        downloaded?.let {
            updater.install(it)
            return
        }
        updateProgress.value = 0f
        viewModelScope.launch {
            val result = runCatching { updater.download(r) { updateProgress.value = it } }
            updateProgress.value = null
            result.onSuccess {
                downloaded = it
                updater.install(it)
            }.onFailure {
                message.value = "The download didn't finish. Check your connection and try again."
            }
        }
    }
}
