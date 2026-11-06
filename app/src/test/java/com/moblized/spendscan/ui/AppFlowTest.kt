package com.moblized.spendscan.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.moblized.spendscan.App
import com.moblized.spendscan.MainActivity
import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.server.FakeHttpServer
import com.moblized.spendscan.server.ServerConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket
import java.time.LocalDate

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
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            explain(text)
            throw e
        }
    }

    /** When something never appears, shows what was on screen instead. */
    private fun explain(text: String) {
        println("NEVER APPEARED: $text")
        runCatching { compose.onAllNodes(isRoot()).printToString(maxDepth = 12) }.onSuccess(::println).onFailure { println("no tree: $it") }
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
    fun reopeningTheAppKeepsWorking() {
        addByHand("Kroger", "45.20")
        waitFor("Spent so far")
        // Back out of the app and open it again while the process is still alive.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Spent so far")
        waitFor("See all 1 receipt")
        // Turning the phone keeps everything too.
        scenario.recreate()
        waitFor("See all 1 receipt")
    }

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

        field(0).performTextInput("zucchini")
        waitFor("Nothing matches")
        tap("Clear filters")
        waitFor("Kroger")
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
        waitFor("Category name")
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size.let { n -> field(n - 1).performTextInput("Coffee") }
        tap("Add")
        waitFor("Coffee")
        waitGone("Category name")

        tap("Coffee")
        waitFor("Category name")
        val nameField = compose.onNode(hasSetTextAction() and hasText("Coffee"))
        nameField.performTextClearance()
        // Emptied, it no longer matches by name, so find it by its hint.
        compose.onNode(hasSetTextAction() and hasText("Coffee, Kids, Vet")).performTextInput("Tea")
        tap("Save")
        waitFor("Tea")

        tap("Tea")
        waitFor("Category name")
        tap("Delete")
        waitGone("Tea")
    }

    @Test
    fun theCatchAllCategoryCantBeDeleted() {
        tap("Budgets")
        tap("Other")
        waitFor("Receipts from deleted categories land here, so this one stays.")
        compose.onAllNodesWithText("Delete").assertCountEquals(0)
        tap("Cancel")
        waitGone("Category name")
    }

    @Test
    fun theDateCanBePicked() {
        tap("Add one by hand")
        tap(LocalDate.now().full())
        tap("Set date")
        waitGone("Set date")
        waitFor("Add a receipt")
    }

    @Test
    fun aPhotoThatCantBeReadStillOpensForEditing() {
        scenario.close()
        val photo = File(app.cacheDir, "shared.jpg").apply { writeText("not really a picture") }
        val share = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(photo))
        scenario = ActivityScenario.launch(share)
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText("No text could be read", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor("Check this receipt")
        waitFor("Add the store and total to save.")
        // The photo was kept with the draft, and goes away if the draft does.
        assertEquals(1, File(app.filesDir, "receipts").list()?.size)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Scan a receipt")
        compose.waitUntil(timeoutMillis = 5_000) { File(app.filesDir, "receipts").list().isNullOrEmpty() }
    }

    @Test
    fun theIconFollowsDarkMode() {
        val pm = app.packageManager
        fun state(name: String) = pm.getComponentEnabledSetting(ComponentName(app.packageName, "com.moblized.spendscan.$name"))

        tapIcon("Settings")
        tap("Dark")
        compose.waitUntil(timeoutMillis = 5_000) { state("LauncherDark") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("LauncherLight"))

        tap("Light")
        compose.waitUntil(timeoutMillis = 5_000) { state("LauncherLight") == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("LauncherDark"))
    }

    /** A stand-in for a language model server: answers like Ollama or LM Studio would. */
    private fun fakeServer(answer: String) = FakeHttpServer { request ->
        when (request.path) {
            "/v1/models" -> 200 to """{"data":[{"id":"nomic-embed-text"},{"id":"llama3.2:latest"},{"id":"qwen3-vl:8b"}]}"""
            "/v1/chat/completions" -> 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer)))).toString()
            else -> 404 to "{}"
        }
    }

    /** Shares a small real photo with the app, as if it had just been scanned. */
    private fun sharePhoto() {
        scenario.close()
        val photo = File(app.cacheDir, "receipt.jpg")
        photo.outputStream().use { Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val share = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.fromFile(photo))
        scenario = ActivityScenario.launch(share)
    }

    private fun waitLong(text: String) = compose.waitUntil(timeoutMillis = 90_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aScanIsReadByYourServer() {
        val http = fakeServer(
            """{"store":"Kroger","date":"${LocalDate.now()}","total":6.93,"subtotal":6.48,"tax":0.45,"tip":null,"discount":null,
               "payment":"Visa 4242","category":"Groceries","items":[{"name":"2% Milk","quantity":1,"amount":3.49},{"name":"Bread","quantity":1,"amount":2.99}]}""",
        )
        try {
            app.prefs.setServer(ServerConfig(http.address, model = "qwen3-vl:8b"))
            sharePhoto()
            waitLong("Read by your server.")
            waitFor("Check this receipt")
            waitFor("Items (2)")
            tap("Save receipt")
            waitFor("Saved Kroger, ${money(693)}.")
            assertEquals("Groceries", runBlocking { app.dao.categoriesNow() }.first().name)
            val saved = runBlocking { app.dao.receiptsNow() }.single()
            assertEquals(runBlocking { app.dao.categoriesNow() }.first().id, saved.categoryId)
            assertEquals(45L, saved.taxCents)
            assertEquals("Visa 4242", saved.payment)
        } finally {
            http.close()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aServerThatDoesntAnswerFallsBackToThePhone() {
        // Nothing listens on this port.
        val closed = ServerSocket(0).use { it.localPort }
        app.prefs.setServer(ServerConfig("http://127.0.0.1:$closed", model = "qwen3-vl:8b"))
        sharePhoto()
        waitLong("Your server didn't answer, so this phone read it.")
        waitFor("Check this receipt")
    }

    @Test
    fun yourServerIsSetUpInSettings() {
        val http = fakeServer("{}")
        try {
            tapIcon("Settings")
            waitFor("Language model server")
            tap("Find models")
            waitFor("Add the server's address first.")

            compose.onNode(hasSetTextAction() and hasText("http://192.168.1.20:11434")).performTextInput(http.address)
            tap("Find models")
            waitFor("Connected. Pick a model that can read images.")
            // Embedding models are left out, and one that can see photos is picked to start.
            compose.onAllNodesWithText("nomic-embed-text").assertCountEquals(0)
            assertEquals("qwen3-vl:8b", app.prefs.server.value.model)
            tap("llama3.2:latest")
            compose.waitUntil(timeoutMillis = 5_000) { app.prefs.server.value.model == "llama3.2:latest" }
            assertEquals(http.address, app.prefs.server.value.url)
            waitFor("Receipts and photos are kept on this phone. Photos of new scans also go to your server. The app only goes online for that and to check for updates.")
        } finally {
            http.close()
        }
    }

    @Test
    fun openWebUiGetsItsPathAddedInSettings() {
        val webui = FakeHttpServer { request ->
            when (request.path) {
                "/api/models" -> 200 to """{"data":[{"id":"qwen3-vl:8b"}]}"""
                else -> 200 to "<!doctype html><html><body>Open WebUI</body></html>"
            }
        }
        try {
            tapIcon("Settings")
            compose.onNode(hasSetTextAction() and hasText("http://192.168.1.20:11434")).performTextInput(webui.address)
            tap("Find models")
            waitFor("Connected. This server keeps its API at /api, so that's been added to the address. Pick a model that can read images.")
            assertEquals("${webui.address}/api", app.prefs.server.value.url)
            assertEquals("qwen3-vl:8b", app.prefs.server.value.model)
            // The field shows the corrected address, and typing still works after.
            compose.onNode(hasSetTextAction() and hasText("${webui.address}/api")).assertExists()
        } finally {
            webui.close()
        }
    }

    @Test
    fun aBadServerAddressSaysWhatToCheck() {
        val closed = ServerSocket(0).use { it.localPort }
        tapIcon("Settings")
        compose.onNode(hasSetTextAction() and hasText("http://192.168.1.20:11434")).performTextInput("http://127.0.0.1:$closed")
        tap("Find models")
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Couldn't reach that address", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun key(label: String) = compose.onNodeWithTag("key $label").performClick()

    private fun keys(labels: String) = labels.filter { it != ' ' }.forEach { key(it.toString()) }

    @Test
    fun theCalculatorAddsUpAndKeepsATape() {
        tapIcon("Calculator")
        waitFor("Calculator")
        keys("12.5 + 3 =")
        waitFor("15.50")
        waitFor("12.5 + 3")

        // It's still there after leaving.
        tapIcon("Back")
        waitFor("Scan a receipt")
        tapIcon("Calculator")
        waitFor("12.5 + 3")

        keys("8 ÷ 0 =")
        waitFor("Can't divide by zero")
        key("C")
        waitGone("Can't divide by zero")

        tap("Clear tape")
        waitGone("12.5 + 3")
    }

    @Test
    fun theCalculatorFillsInATotal() {
        tap("Add one by hand")
        waitFor("Add a receipt")
        field(0).performTextInput("Kroger")
        tapIcon("Work out the total")
        waitFor("Work out the total")
        keys("20 + 5")
        tap("Use ${money(2500)}")
        waitFor("Add a receipt")
        compose.onNode(hasSetTextAction() and hasText("25.00")).assertExists()
        // What was typed in the receipt is still there.
        compose.onNode(hasSetTextAction() and hasText("Kroger")).assertExists()
        tap("Save receipt")
        waitFor("Saved Kroger, ${money(2500)}.")
    }

    @Test
    fun aFillUpIsTrackedUnderFuel() {
        tap("Fuel")
        waitFor("No fill-ups yet")
        tap("Add by hand")
        waitFor("Add a fill-up")
        field(0).performTextInput("Shell")
        // Gallons and price work out the total when it's left empty.
        field(2).performTextInput("10")
        field(3).performTextInput("3.50")
        field(4).performTextInput("50,000")
        tap("Save receipt")
        waitFor("Saved Shell, ${money(3500)}.")
        waitFor("Fuel so far")
        waitFor("10 gal at ${FuelUnits.pricePerGallon(3500)}, 50,000 mi")
        waitFor("Add your odometer at two full tanks in a row to see your miles per gallon.")

        // It counts as spending once, under Gas & auto.
        val saved = runBlocking { app.dao.receiptsNow() }.single()
        val gas = runBlocking { app.dao.categoriesNow() }.first { it.name == "Gas & auto" }
        assertEquals(gas.id, saved.categoryId)
        assertEquals(3500L, saved.totalCents)

        // The second full tank makes mileage possible: 300 miles on 10 gallons.
        tap("Add by hand")
        waitFor("Add a fill-up")
        waitFor("Last time 50,000")
        field(0).performTextInput("Exxon")
        field(1).performTextInput("30")
        field(2).performTextInput("10")
        field(4).performTextInput("50300")
        tap("Save receipt")
        waitFor("Saved Exxon, ${money(3000)}.")
        waitFor("30.0")
        waitFor("10 gal at ${FuelUnits.pricePerGallon(3000)}, 30.0 mpg, 50,300 mi")

        // A fill-up opens as its receipt, and deleting that takes it off Fuel too.
        tap("Exxon")
        waitFor("Per gallon")
        waitFor("Odometer")
        tapIcon("Delete receipt")
        tap("Delete")
        waitFor("Deleted Exxon, ${money(3000)}.")
        waitGone("Exxon")
        assertEquals(1, runBlocking { app.dao.fillUpsNow() }.size)
    }

    /** The real flow past the camera app: a pump photo arriving back in the app. */
    private fun pumpPhotoTaken() {
        val photo = File(app.cacheDir, "pump.jpg")
        photo.outputStream().use { Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        scenario.onActivity { ViewModelProvider(it)[AppModel::class.java].onPumpPhoto(Uri.fromFile(photo)) }
    }

    @Test
    fun thePumpButtonOpensTheCameraApp() {
        tap("Fuel")
        tap("Photo of the pump")
        compose.waitForIdle()
        scenario.onActivity { activity ->
            val started = Shadows.shadowOf(activity).nextStartedActivityForResult
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
            val output = started.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            assertEquals("${app.packageName}.files", output?.authority)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPumpPhotoIsReadByYourServerIntoAFillUp() {
        val http = fakeServer(
            """{"store":null,"date":null,"total":42.70,"subtotal":null,"tax":null,"tip":null,"discount":null,"payment":null,
               "category":"Gas & auto","gallons":12.345,"price_per_gallon":3.459,"items":[]}""",
        )
        try {
            app.prefs.setServer(ServerConfig(http.address, model = "qwen3-vl:8b"))
            pumpPhotoTaken()
            waitLong("Read by your server.")
            waitFor("Check this fill-up")
            compose.onNode(hasSetTextAction() and hasText("42.70")).assertExists()
            compose.onNode(hasSetTextAction() and hasText("12.345")).assertExists()
            compose.onNode(hasSetTextAction() and hasText("3.459")).assertExists()
            // Pumps don't name the station, so it asks.
            waitFor("Which station?")
            waitFor("check this")
            tap("Save receipt")
            waitFor("Add the store to save.")
            field(0).performTextInput("Shell")
            tap("Save receipt")
            waitFor("Saved Shell, ${money(4270)}.")
            // Lands on Fuel, dated today, under Gas & auto, with the photo kept.
            waitFor("Fuel so far")
            val saved = runBlocking { app.dao.fillUpsNow() }.single()
            assertEquals(12_345L, saved.fill.gallonsMilli)
            assertEquals(LocalDate.now(), saved.date)
            val receipt = runBlocking { app.dao.receiptsNow() }.single()
            assertEquals(1, receipt.photoList.size)
            assertEquals(runBlocking { app.dao.categoriesNow() }.first { it.name == "Gas & auto" }.id, receipt.categoryId)
        } finally {
            http.close()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aPumpPhotoNothingCanReadStillBecomesAFillUp() {
        // No server, and no text reader in this test: the numbers are typed in from the photo.
        pumpPhotoTaken()
        waitLong("Check this fill-up")
        waitFor("No numbers could be read from this photo", substring = true)
        waitFor("Fill-up")
        field(0).performTextInput("Shell")
        field(2).performTextInput("10")
        field(3).performTextInput("4")
        tap("Save receipt")
        waitFor("Saved Shell, ${money(4000)}.")
    }

    @Test
    fun aGasReceiptCanBecomeAFillUpAndBack() {
        tap("Add one by hand")
        waitFor("Add a receipt")
        field(0).performTextInput("Shell")
        field(1).performTextInput("40")
        // Only offered for the gas category, so other receipts stay simple.
        compose.onAllNodesWithText("This was a fill-up").assertCountEquals(0)
        tap("Gas & auto")
        tap("This was a fill-up")
        waitFor("Fill-up")
        tap("Not a fill-up")
        waitFor("This was a fill-up")
        tap("This was a fill-up")
        field(2).performTextInput("10")
        tap("Partial fill")
        tap("Save receipt")
        waitFor("Saved Shell, ${money(4000)}.")
        val saved = runBlocking { app.dao.fillUpsNow() }.single()
        assertEquals(10_000L, saved.fill.gallonsMilli)
        assertEquals(false, saved.fill.fullTank)

        // Edit it back to an ordinary receipt.
        tap("Shell")
        waitFor("Per gallon")
        tapIcon("Edit receipt")
        tap("Not a fill-up")
        tap("Save receipt")
        waitFor("Saved Shell, ${money(4000)}.")
        compose.waitUntil(timeoutMillis = 5_000) {
            // Lets the save finish: tests only run the app's queued work when asked to.
            compose.waitForIdle()
            runBlocking { app.dao.fillUpsNow() }.isEmpty()
        }
        assertEquals(1, runBlocking { app.dao.receiptsNow() }.size)
    }

    @Test
    fun exportIsOffWithNothingToExport() {
        tapIcon("Settings")
        waitFor("Nothing to export yet")
    }
}
