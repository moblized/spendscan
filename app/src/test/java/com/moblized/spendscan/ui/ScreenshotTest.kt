package com.moblized.spendscan.ui

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.moblized.spendscan.R
import com.moblized.spendscan.calc.CalcKey
import com.moblized.spendscan.calc.CalcState
import com.moblized.spendscan.calc.Calculator
import com.moblized.spendscan.data.Category
import com.moblized.spendscan.data.Draft
import com.moblized.spendscan.data.FillUp
import com.moblized.spendscan.data.FillUpRow
import com.moblized.spendscan.data.FuelSummary
import com.moblized.spendscan.data.ItemHit
import com.moblized.spendscan.data.PaperlessRun
import com.moblized.spendscan.data.UploadCounts
import com.moblized.spendscan.paperless.PaperlessConfig
import com.moblized.spendscan.paperless.PaperlessSync
import com.moblized.spendscan.paperless.SyncProblem
import com.moblized.spendscan.data.ItemStats
import com.moblized.spendscan.data.MonthSummary
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.ReceiptFilter
import com.moblized.spendscan.data.ReceiptItem
import com.moblized.spendscan.data.ReceiptRow
import com.moblized.spendscan.data.ThemeChoice
import com.moblized.spendscan.parse.PumpReader
import com.moblized.spendscan.parse.ReceiptField
import com.moblized.spendscan.parse.ReceiptParser
import com.moblized.spendscan.server.ServerConfig
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.SpendTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Renders every screen with sample data, so the look can be checked from CI without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 18)
    private val nov = YearMonth.of(2026, 11)
    private val cats = listOf(
        Category(1, "Groceries", budgetCents = 60000, position = 0),
        Category(2, "Dining out", budgetCents = 15000, position = 1),
        Category(3, "Gas & auto", budgetCents = 25000, position = 2),
        Category(4, "Home", position = 3),
        Category(5, "Health", position = 4),
        Category(6, "Shopping", position = 5),
        Category(7, "Fun", position = 6),
        Category(8, "Work", position = 7),
        Category(9, "Other", position = 8, fixed = true),
    )

    private fun r(id: Long, store: String, day: Int, cents: Long, cat: Long) =
        Receipt(id = id, store = store, dateEpochDay = nov.atDay(day).toEpochDay(), totalCents = cents, categoryId = cat)

    private val receipts = listOf(
        r(1, "Kroger", 17, 8642, 1), r(2, "Shell", 16, 7215, 3), r(3, "Taqueria Oaxaca", 15, 3860, 2),
        r(4, "Costco", 14, 21874, 1), r(5, "Home Depot", 12, 13422, 4), r(6, "Starbucks", 12, 745, 2),
        r(7, "Olive Garden", 9, 6810, 2), r(8, "Kroger", 8, 5433, 1), r(9, "CVS", 7, 2199, 5),
        r(10, "Shell", 6, 6920, 3), r(11, "Chipotle", 5, 2140, 2), r(12, "Aldi", 3, 6118, 1),
        r(13, "Target", 2, 9405, 6), r(14, "Regal", 1, 3200, 7),
    )
    private val summary = MonthSummary.of(
        nov,
        receipts,
        listOf(Receipt(store = "Kroger", dateEpochDay = nov.minusMonths(1).atDay(4).toEpochDay(), totalCents = 112000, categoryId = 1)),
        cats,
        150000,
        today,
    )
    private val overBudget = MonthSummary.of(nov, receipts + r(20, "Best Buy", 11, 92000, 6), emptyList(), cats, 150000, today)
    private val rows = receipts.map { ReceiptRow(it, (it.id % 9).toInt()) }

    private val parsedDraft = Draft.fromParsed(
        ReceiptParser.parse(
            listOf(
                "Blue Heron Market", "417 East Blvd", "Charlotte, NC 28203", "11/17/2026 5:41 PM",
                "2% MILK GAL 3.49", "HONEYCRISP APPLES 4.12", "SOURDOUGH LOAF 5.50", "EGGS 18CT 4.99",
                "OLIVE OIL 12.99", "Subtotal 31.09", "Tax 1.24", "Total 32.33", "VISA **** 4242 32.33",
            ),
            today,
        ),
        emptyList(),
        today,
        1,
    )

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            SpendTheme(dark) {
                Box(Modifier.fillMaxSize().background(LocalSlip.current.counter)) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
        println("SHOT $name")
    }

    @Composable
    private fun Month(s: MonthSummary) = Column(Modifier.fillMaxSize()) {
        MonthScreen(
            s, animateIn = false, onPrinted = {}, onPrev = {}, onNext = {}, onCategory = {}, onStore = {}, onDay = {},
            onAllReceipts = {}, onScan = {}, onAddByHand = {}, modifier = Modifier.weight(1f),
        )
        BottomBar(Tab.MONTH, {}, {})
    }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun month() = shoot("01_month") { Month(summary) }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun monthDark() = shoot("02_month_dark", dark = true) { Month(summary) }

    @Test
    fun monthFirstScreen() = shoot("03_month_first_screen") { Month(summary) }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun monthOverBudget() = shoot("04_month_over_budget") { Month(overBudget) }

    @Test
    fun monthEmpty() = shoot("05_month_empty") {
        Month(MonthSummary.of(nov, emptyList(), emptyList(), cats.map { it.copy(budgetCents = null) }, null, today))
    }

    @Test
    fun receiptsList() = shoot("06_receipts") {
        Column(Modifier.fillMaxSize()) {
            ReceiptsScreen(rows, null, ReceiptFilter(), cats, true, {}, {}, {}, {}, Modifier.weight(1f))
            BottomBar(Tab.RECEIPTS, {}, {})
        }
    }

    @Test
    fun receiptsSearch() = shoot("07_receipts_search", dark = true) {
        val hits = listOf(
            ItemHit(1, "2% MILK GAL", 1.0, 419, "Kroger", nov.atDay(17).toEpochDay()),
            ItemHit(8, "2% MILK GAL", 1.0, 399, "Kroger", nov.atDay(8).toEpochDay()),
            ItemHit(12, "MILK WHOLE", 1.0, 349, "Aldi", nov.atDay(3).toEpochDay()),
        )
        Column(Modifier.fillMaxSize()) {
            ReceiptsScreen(
                rows.filter { it.receipt.id in setOf(1L, 8L, 12L) },
                ItemStats.of("milk", hits),
                ReceiptFilter(query = "milk", month = nov),
                cats,
                true,
                {},
                {},
                {},
                {},
                Modifier.weight(1f),
            )
            BottomBar(Tab.RECEIPTS, {}, {})
        }
    }

    @Test
    fun receiptsNothingYet() = shoot("08_receipts_empty") {
        ReceiptsScreen(emptyList(), null, ReceiptFilter(), cats, false, {}, {}, {}, {})
    }

    @Test
    fun receiptsSearching() = shoot("17_receipts_typing") {
        ReceiptsScreen(emptyList(), null, ReceiptFilter(query = "zucchini"), cats, true, {}, {}, {}, {})
    }

    private val detailReceipt = Receipt(
        id = 1, store = "Olive Garden", dateEpochDay = nov.atDay(9).toEpochDay(), totalCents = 6810, subtotalCents = 5400,
        taxCents = 410, tipCents = 1000, categoryId = 2, payment = "Mastercard 5454",
    )
    private val detailItems = listOf(
        ReceiptItem(receiptId = 1, name = "Tour of Italy", amountCents = 2199),
        ReceiptItem(receiptId = 1, name = "Chicken Parm", amountCents = 1899),
        ReceiptItem(receiptId = 1, name = "Iced Tea", quantity = 2.0, amountCents = 698),
        ReceiptItem(receiptId = 1, name = "Tiramisu with a very long name that runs off the edge", amountCents = 604),
    )

    @Test
    fun detail() = shoot("09_detail") {
        ReceiptDetailScreen(detailReceipt, detailItems, "Dining out", { File(it) }, {}, {}, {})
    }

    @Test
    fun detailDark() = shoot("10_detail_dark", dark = true) {
        ReceiptDetailScreen(detailReceipt, detailItems, "Dining out", { File(it) }, {}, {}, {}, paperless = PaperlessNote("In Paperless.", kept = true))
    }

    @Test
    @Config(qualifiers = "w412dp-h1300dp-xxhdpi")
    fun review() = shoot("11_review") {
        ReviewScreen(parsedDraft.copy(photos = listOf("page1.jpg", "page2.jpg")), cats, { File(it) }, {}, {}, {})
    }

    @Test
    fun reviewDark() = shoot("12_review_dark", dark = true) {
        ReviewScreen(parsedDraft.copy(unsure = ReceiptField.entries.toSet(), totalText = "32.33", items = parsedDraft.items.drop(1), store = "Blue Heron Market"), cats, { File(it) }, {}, {}, {})
    }

    @Test
    fun reviewCouldNotRead() = shoot("13_review_unread") {
        ReviewScreen(Draft.fromParsed(null, emptyList(), today, 9), cats, { File(it) }, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "w412dp-h1200dp-xxhdpi")
    fun budgets() = shoot("14_budgets") {
        Column(Modifier.fillMaxSize()) {
            BudgetsScreen(
                cats, mapOf(1L to 42067L, 2L to 13555L, 3L to 14135L, 4L to 13422L), nov, 150000,
                {}, { _, _ -> }, { _, _ -> }, {}, {}, Modifier.weight(1f),
            )
            BottomBar(Tab.BUDGETS, {}, {})
        }
    }

    @Test
    fun settings() = shoot("15_settings") {
        Column(Modifier.fillMaxSize()) {
            SettingsScreen(
                ThemeChoice.SYSTEM, {}, 14, {}, "1.0.1", "You have the latest version.", {},
                ServerConfig(), {}, emptyList(), null, {}, Modifier.weight(1f),
            )
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1300dp-xxhdpi")
    fun settingsWithServer() = shoot("18_settings_server", dark = true) {
        SettingsScreen(
            ThemeChoice.SYSTEM, {}, 14, {}, "1.0.1", null, {},
            ServerConfig("http://192.168.1.20:11434", model = "qwen3-vl:8b"), {},
            listOf("gemma3:12b", "qwen3-vl:8b", "llama3.2:latest"), "Connected. Pick a model that can read images.", {},
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun settingsWithPaperless() = shoot("28_settings_paperless") {
        SettingsScreen(
            ThemeChoice.SYSTEM, {}, 51, {}, "1.0.1", null, {},
            ServerConfig(), {}, emptyList(), null, {},
            paperless = PaperlessView(
                PaperlessConfig("https://paperless.example.com", "0123456789abcdef", on = true),
                UploadCounts(waiting = 3, sent = 46, refused = 1, noPhotos = 2, lastProblem = PaperlessSync.MISSING_PHOTOS),
                PaperlessRun(SyncProblem.UNREACHABLE, 1),
            ),
        )
    }

    @Test
    fun settingsPaperlessNotConnected() = shoot("29_settings_paperless_off", dark = true) {
        SettingsScreen(
            ThemeChoice.SYSTEM, {}, 51, {}, "1.0.1", null, {},
            ServerConfig(), {}, emptyList(), null, {},
            paperless = PaperlessView(PaperlessConfig("http://paperless.example.com", "x"), status = "Paperless turned down that token. Copy it again from My Profile in Paperless."),
        )
    }

    @Test
    fun reviewReadByServer() = shoot("19_review_server") {
        ReviewScreen(parsedDraft.copy(readNote = "Read by your server."), cats, { File(it) }, {}, {}, {})
    }

    private fun fill(id: Long, store: String, day: Int, cents: Long, gallons: Long?, price: Long?, odometer: Int?, full: Boolean = true) =
        FillUpRow(FillUp(id, id, gallons, price, odometer, full), store, nov.atDay(day).toEpochDay(), cents)

    private val fuelRows = listOf(
        fill(1, "Shell", 2, 8420, 22_400, 3_759, 52_010),
        fill(2, "Love's", 7, 3010, 8_000, 3_759, 52_140, full = false),
        fill(3, "Costco Gas", 11, 7296, 21_100, 3_459, 52_500),
        fill(4, "Shell", 16, 8211, 22_200, 3_699, 52_830),
        fill(5, "Pilot", 21 - 20, 9150, 24_000, 3_812, 51_700),
        FillUpRow(FillUp(6, 6, 25_000, null, 51_400), "Exxon", nov.minusMonths(1).atDay(18).toEpochDay(), 9300),
        FillUpRow(FillUp(7, 7, 21_000, null, null), "Shell", nov.minusMonths(2).atDay(9).toEpochDay(), 7600),
        FillUpRow(FillUp(8, 8, 23_000, null, null), "Shell", nov.minusMonths(3).atDay(9).toEpochDay(), 8200),
        FillUpRow(FillUp(9, 9, 20_000, null, null), "Shell", nov.minusMonths(5).atDay(9).toEpochDay(), 7000),
    )

    @Test
    @Config(qualifiers = "w412dp-h1300dp-xxhdpi")
    fun fuel() = shoot("20_fuel") {
        Column(Modifier.fillMaxSize()) {
            FuelScreen(FuelSummary.of(fuelRows, nov, today), {}, {}, {}, {}, Modifier.weight(1f))
            BottomBar(Tab.FUEL, {}, {})
        }
    }

    @Test
    fun fuelDark() = shoot("21_fuel_dark", dark = true) {
        Column(Modifier.fillMaxSize()) {
            FuelScreen(FuelSummary.of(fuelRows, nov, today), {}, {}, {}, {}, Modifier.weight(1f))
            BottomBar(Tab.FUEL, {}, {})
        }
    }

    @Test
    fun fuelEmpty() = shoot("22_fuel_empty") {
        Column(Modifier.fillMaxSize()) {
            FuelScreen(FuelSummary.of(emptyList(), nov, today), {}, {}, {}, {}, Modifier.weight(1f))
            BottomBar(Tab.FUEL, {}, {})
        }
    }

    private val calc = listOf("12.50", "+", "3.49", "=", "45.20", "×", "18", "%", "=", "84.20", "+", "30.10", "+", "12")
        .fold(CalcState()) { s, token ->
            when {
                token == "=" -> Calculator.press(s, CalcKey.Equals)
                token == "%" -> Calculator.press(s, CalcKey.Percent)
                token.length == 1 && token[0] in "+×" -> Calculator.press(s, CalcKey.Op(token[0]))
                else -> token.fold(s) { st, ch -> Calculator.press(st, if (ch == '.') CalcKey.Dot else CalcKey.Digit(ch)) }
            }
        }

    @Test
    fun calculator() = shoot("23_calculator") { CalculatorScreen(calc, false, {}, {}, {}, {}) }

    @Test
    fun calculatorForTotal() = shoot("24_calculator_total", dark = true) { CalculatorScreen(calc, true, {}, {}, {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h1000dp-xxhdpi")
    fun reviewFillUp() = shoot("25_review_fillup") {
        ReviewScreen(
            parsedDraft.copy(
                store = "Shell", isFillUp = true, gallonsText = "22.400", priceText = "3.759", totalText = "84.20",
                categoryId = 3, items = emptyList(), lastOdometer = 52_010,
            ),
            cats, { File(it) }, {}, {}, {},
        )
    }

    @Test
    @Config(qualifiers = "w412dp-h1000dp-xxhdpi")
    fun reviewPumpPhoto() = shoot("27_review_pump") {
        ReviewScreen(
            Draft.fromParsed(PumpReader.parse(listOf("42.70", "12.345", "3.459")), emptyList(), today, 3, "Read from the pump photo. Check the numbers against it.", 52_010)
                .copy(isFillUp = true, photos = listOf("pump.jpg")),
            cats, { File(it) }, {}, {}, {},
        )
    }

    @Test
    fun detailFillUp() = shoot("26_detail_fillup") {
        ReceiptDetailScreen(
            detailReceipt.copy(store = "Shell", categoryId = 3, subtotalCents = null, taxCents = null, tipCents = null, totalCents = 8420),
            emptyList(), "Gas & auto", { File(it) }, {}, {}, {}, fillUp = FillUp(1, 1, 22_400, 3_759, 52_010),
        )
    }

    @Test
    fun appIcons() = shoot("16_icons") {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(
                Color(0xFF2A46C8) to R.drawable.ic_launcher_foreground,
                Color(0xFF0C1030) to R.drawable.ic_launcher_foreground_dark,
            ).forEach { (bg, fg) ->
                Box(Modifier.size(160.dp).clip(RoundedCornerShape(36.dp)).background(bg)) {
                    Image(painterResource(fg), null, Modifier.fillMaxSize())
                }
            }
        }
    }
}
