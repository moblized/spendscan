package com.moblized.spendscan.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.moblized.spendscan.App
import com.moblized.spendscan.MainActivity
import com.moblized.spendscan.data.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real app, database and all, the way a person would use it. Only the camera and text reader are left out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1800dp-xxhdpi")
class AppFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var app: App
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun start() {
        app = ApplicationProvider.getApplicationContext()
        // No update check during tests.
        app.prefs.updateCheckedAt = System.currentTimeMillis()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Scan a receipt")
    }

    @After
    fun stop() {
        scenario.close()
        app.db.close()
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitGone(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun tap(text: String) {
        waitFor(text)
        compose.onAllNodesWithText(text)[0].performClick()
    }

    private fun tapIcon(description: String) = compose.onNodeWithContentDescription(description).performClick()

    private fun field(index: Int): SemanticsNodeInteraction = compose.onAllNodes(hasSetTextAction())[index]

    private fun addByHand(store: String, total: String, category: String? = null) {
        tap("Receipts")
        tapIcon("Add a receipt by hand")
        waitFor("Add a receipt")
        field(0).performTextInput(store)
        field(1).performTextInput(total)
        if (category != null) tap(category)
        compose.waitForIdle()
        tap("Save receipt")
    }

    private fun money(cents: Long) = Money.format(cents)

    @Test
    fun startsOnAnEmptyMonth() {
        waitFor("No receipts in", substring = true)
        compose.onNodeWithText("Add one by hand").assertExists()
    }

    @Test
    fun aReceiptShowsUpInTheMonthAndCanBeDeleted() {
        addByHand("Kroger", "45.20", "Groceries")
        waitFor("Saved Kroger, ${money(4520)}.")

        // Saving lands on the month, with the receipt counted.
        waitFor("Spent so far")
        waitFor("See all 1 receipt")

        tap("See all 1 receipt")
        tap("Kroger")
        waitFor("Total")
        waitFor("Groceries")

        tapIcon("Delete receipt")
        tap("Delete")
        waitFor("Deleted Kroger, ${money(4520)}.")
        waitFor("Nothing matches")
        tap("Clear filters")
        waitFor("No receipts yet")
    }

    @Test
    fun aSavedReceiptCanBeEdited() {
        addByHand("Kroger", "45.20")
        waitFor("Spent so far")
        tap("See all 1 receipt")
        tap("Kroger")
        waitFor("Total")
        tapIcon("Edit receipt")
        waitFor("Edit receipt")
        field(1).performTextClearance()
        field(1).performTextInput("50")
        tap("Save receipt")
        waitFor(money(5000))
        // Back on the receipt, not thrown to the month.
        waitFor("Total")
    }

    @Test
    fun savingNeedsAStoreAndATotal() {
        tap("Add one by hand")
        waitFor("Add the store and total to save.")
        field(0).performTextInput("Kroger")
        waitFor("Add the total to save.")
        field(1).performTextInput("abc")
        waitFor("Add the total to save.")
        field(1).performTextClearance()
        field(1).performTextInput("12")
        waitGone("Add the total to save.")
    }

    @Test
    fun theSameReceiptTwiceGetsAWarning() {
        addByHand("Kroger", "45.20")
        waitFor("Spent so far")
        addByHand("Kroger", "45.20")
        waitFor("Already saved?")
        tap("Discard this one")
        waitGone("Already saved?")
        waitFor("Receipts")
        assertEquals(1, runBlocking { app.db.dao().receiptsNow().size })
    }

    @Test
    fun goingBackFromAnUnsavedReceiptAsksFirst() {
        tap("Add one by hand")
        waitFor("Add a receipt")
        // Nothing typed yet: back just leaves.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitGone("Add a receipt")

        tap("Add one by hand")
        waitFor("Add a receipt")
        field(0).performTextInput("Kroger")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Discard this receipt?")
        tap("Keep editing")
        waitGone("Discard this receipt?")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Discard")
        waitFor("No receipts in", substring = true)
    }

    @Test
    fun searchFindsItemsOnReceipts() {
        tap("Add one by hand")
        waitFor("Add a receipt")
        field(0).performTextInput("Kroger")
        field(1).performTextInput("3.49")
        tap("Add an item")
        waitFor("Items (1)")
        field(2).performTextInput("2% Milk")
        field(3).performTextInput("3.49")
        tap("Save receipt")
        waitFor("Spent so far")

        tap("Receipts")
        field(0).performTextInput("milk")
        waitFor("\"milk\" on your receipts")
        waitFor("Bought 1 time for ${money(349)}, about ${money(349)} each time")
        tapIcon("Clear search")
        waitGone("\"milk\" on your receipts")
    }

    @Test
    fun budgetsWarnWhenCrossed() {
        tap("Budgets")
        waitFor("Whole month")
        // The first field is the month; the next is the first category, Groceries.
        field(1).performTextInput("10")
        compose.waitForIdle()
        addByHand("Kroger", "45.20", "Groceries")
        waitFor("Saved Kroger, ${money(4520)}. Groceries is over budget by ${money(3520)}.")
        waitFor("OVER")
    }

    @Test
    fun categoriesCanBeAddedAndDeleted() {
        tap("Budgets")
        tap("Add a category")
        waitFor("New category")
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size.let { n -> field(n - 1).performTextInput("Coffee") }
        tap("Add")
        waitFor("Coffee")
        tap("Coffee")
        waitFor("Edit Coffee")
        tap("Delete")
        waitGone("Coffee")
    }

    @Test
    fun theIconFollowsDarkMode() {
        val pm = app.packageManager
        fun state(name: String) = pm.getComponentEnabledSetting(ComponentName(app.packageName, "com.moblized.spendscan.$name"))

        tap("Settings")
        tap("Dark")
        compose.waitUntil(timeoutMillis = 5_000) { state("LauncherDark") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("LauncherLight"))

        tap("Light")
        compose.waitUntil(timeoutMillis = 5_000) { state("LauncherLight") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("LauncherDark"))
    }

    @Test
    fun exportIsOffWithNothingToExport() {
        tap("Settings")
        waitFor("Nothing to export yet")
    }
}
