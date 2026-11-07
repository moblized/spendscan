package com.moblized.spendscan.paperless

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moblized.spendscan.data.AppDatabase
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.ReceiptItem
import com.moblized.spendscan.data.SpendDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaperlessSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SpendDao
    private lateinit var paperless: FakePaperless
    private lateinit var dir: File
    private var config = PaperlessConfig()
    private lateinit var sync: PaperlessSync
    private var clock = 1_000_000L
    private val savedLocale = Locale.getDefault()
    private val day = LocalDate.of(2026, 11, 2)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.StartingCategories)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        dir = File(context.cacheDir, "photos-${System.nanoTime()}").apply { mkdirs() }
        paperless = FakePaperless()
        config = PaperlessConfig(paperless.address, "secret", on = true)
        sync = PaperlessSync(dao, { File(dir, it) }, { config }, now = { clock }, currencyCode = { "USD" })
    }

    @After
    fun tearDown() {
        paperless.close()
        db.close()
        Locale.setDefault(savedLocale)
    }

    private fun photo(name: String, width: Int = 300, height: Int = 900): String {
        File(dir, name).writeBytes(ReceiptPdfTest.jpeg(width, height))
        return name
    }

    private suspend fun category(name: String) = dao.categoriesNow().first { it.name == name }.id

    private suspend fun save(store: String = "Kroger", total: Long = 693, photos: List<String> = listOf(photo("a.jpg")), category: String = "Groceries") =
        dao.saveReceipt(
            Receipt(store = store, dateEpochDay = day.toEpochDay(), totalCents = total, categoryId = category(category), photos = photos.joinToString("\n")),
            listOf(ReceiptItem(receiptId = 0, name = "Milk", amountCents = 349)),
        )

    @Test
    fun sendsAReceiptAsAPdfWithItsDetails() = runBlocking {
        val id = save(photos = listOf(photo("a.jpg"), photo("b.jpg", 600, 300)))
        val result = sync.run()

        assertEquals(SyncResult.Finished(sent = 1, processing = 0), result)
        val sent = paperless.uploads.single()
        assertEquals("Kroger $6.93", sent.field("title"))
        assertEquals("2026-11-02", sent.field("created"))
        assertEquals("${paperless.labelId("correspondents", "Kroger")}", sent.field("correspondent"))
        assertEquals("${paperless.labelId("document_types", "Receipt")}", sent.field("document_type"))
        assertEquals(listOf("${paperless.labelId("tags", "Groceries")}"), sent.fields("tags"))
        assertEquals("2026-11-02 Kroger.pdf", sent.file.fileName)
        val pdf = String(sent.file.bytes, Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-"))
        assertTrue(pdf.contains("/Count 2"))

        // What this app makes in Paperless doesn't go matching other documents.
        assertEquals(0, paperless.labels.getValue("tags").single().getInt("matching_algorithm"))
        assertEquals("monetary", paperless.labels.getValue("custom_fields").single().getString("data_type"))

        // Once Paperless says which document it made, the total goes on it.
        val (docId, patch) = paperless.patches.single()
        assertEquals(42L, docId)
        val field = patch.getJSONArray("custom_fields").getJSONObject(0)
        assertEquals(paperless.labelId("custom_fields", "Total"), field.getLong("field"))
        assertEquals("USD6.93", field.getString("value"))

        val upload = dao.uploadNow(id)!!
        assertEquals(42L, upload.documentId)
        assertEquals(0, upload.changes)
        assertNull(upload.problem)
        assertEquals(1, dao.uploadCounts().first().sent)
        assertEquals(0, dao.uploadCounts().first().waiting)
    }

    @Test
    fun nothingGoesTwice() = runBlocking {
        save()
        sync.run()
        val before = paperless.requests.size
        assertEquals(SyncResult.Finished(0, 0), sync.run())
        assertEquals(before, paperless.requests.size)
    }

    @Test
    fun waitsWhilePaperlessIsStillReading() = runBlocking {
        val id = save()
        paperless.taskAnswers[1] = JSONObject().put("status", "STARTED")
        assertEquals(SyncResult.Finished(sent = 1, processing = 1), sync.run())
        assertNull(dao.uploadNow(id)!!.documentId)
        assertTrue(paperless.patches.isEmpty())

        paperless.docs[7] = FakePaperless.Doc(7, mutableListOf())
        paperless.taskAnswers[1] = JSONObject().put("status", "SUCCESS").put("result", "Success. New document id 7 created")
        assertEquals(SyncResult.Finished(sent = 0, processing = 0), sync.run())
        assertEquals(7L, dao.uploadNow(id)!!.documentId)
        assertEquals(7L, paperless.patches.single().first)
    }

    @Test
    fun editsGoToPaperlessAndLeaveItsOwnTagsAndFieldsAlone() = runBlocking {
        val id = save()
        sync.run()
        val doc = paperless.docs.getValue(42)
        // Things added by hand in Paperless.
        doc.tags.add(99)
        doc.fields.put(JSONObject().put("field", 500).put("value", "Warranty"))

        val receipt = dao.receiptNow(id)!!
        dao.saveReceipt(receipt.copy(totalCents = 1250, categoryId = category("Dining out"), store = "Kroger Deli"), dao.itemsNow(id))
        assertEquals(1, dao.uploadNow(id)!!.changes)
        assertEquals(SyncResult.Finished(0, 0), sync.run())

        assertEquals(1, paperless.uploads.size)
        val patch = paperless.patches.last().second
        assertEquals("Kroger Deli $12.50", patch.getString("title"))
        assertEquals(paperless.labelId("correspondents", "Kroger Deli"), patch.getLong("correspondent"))
        val tags = patch.getJSONArray("tags").let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }
        assertEquals(setOf(99L, paperless.labelId("tags", "Dining out")), tags)
        val fields = patch.getJSONArray("custom_fields").let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { f -> f.getLong("field") to f.get("value") } } }
        assertEquals("Warranty", fields[500L])
        assertEquals("USD12.50", fields[paperless.labelId("custom_fields", "Total")])
        assertEquals(0, dao.uploadNow(id)!!.changes)
        assertEquals(paperless.labelId("tags", "Dining out"), dao.uploadNow(id)!!.tagId)
    }

    @Test
    fun deletingAReceiptLeavesItsCopyInPaperless() = runBlocking {
        val id = save()
        sync.run()
        val before = paperless.requests.size
        dao.deleteReceipt(id)
        assertNull(dao.uploadNow(id))
        sync.run()
        assertEquals(before, paperless.requests.size)
        assertNotNull(paperless.docs[42])
    }

    @Test
    fun aFilePaperlessAlreadyHasCountsAsSent() = runBlocking {
        val id = save()
        paperless.docs[17] = FakePaperless.Doc(17, mutableListOf())
        paperless.taskAnswers[1] = JSONObject().put("status", "FAILURE")
            .put("result", "2026-11-02 Kroger.pdf: Not consuming 2026-11-02 Kroger.pdf: It is a duplicate of Kroger #5 \$6.93 (#17).")
        sync.run()
        assertEquals(17L, dao.uploadNow(id)!!.documentId)
        assertEquals(17L, paperless.patches.single().first)
    }

    @Test
    fun whatPaperlessTurnsDownWaitsUntilYouSayTryAgain() = runBlocking {
        val id = save()
        paperless.taskAnswers[1] = JSONObject().put("status", "FAILURE").put("result", "Error while consuming document: unreadable")
        sync.run()
        assertEquals("Paperless said: Error while consuming document: unreadable", dao.uploadNow(id)!!.problem)
        assertEquals(1, dao.uploadCounts().first().refused)

        sync.run()
        assertEquals(1, paperless.uploads.size)

        paperless.taskAnswers.remove(2)
        dao.clearUploadProblems()
        sync.run()
        assertEquals(2, paperless.uploads.size)
        assertNotNull(dao.uploadNow(id)!!.documentId)
    }

    @Test
    fun aFileTooBigForTheServerDoesNotHoldUpTheRest() = runBlocking {
        val first = save()
        val second = save(store = "Shell", total = 4200, photos = listOf(photo("c.jpg")), category = "Gas & auto")
        paperless.refuseNextUpload = 413 to "<html>413 Request Entity Too Large</html>"
        assertEquals(SyncResult.Finished(sent = 1, processing = 0), sync.run())
        assertEquals(PaperlessSync.TOO_BIG, dao.uploadNow(first)!!.problem)
        assertNotNull(dao.uploadNow(second)!!.documentId)
    }

    @Test
    fun receiptsWithoutPhotosStayOnThePhone() = runBlocking {
        save(photos = emptyList())
        val missing = save(store = "Target", photos = listOf("gone.jpg"))
        sync.run()
        assertTrue(paperless.uploads.isEmpty())
        val counts = dao.uploadCounts().first()
        assertEquals(1, counts.noPhotos)
        assertEquals(1, counts.refused)
        assertEquals(PaperlessSync.MISSING_PHOTOS, dao.uploadNow(missing)!!.problem)
    }

    @Test
    fun aWrongTokenStopsWithoutUsingUpAnything() = runBlocking {
        val id = save()
        config = config.copy(token = "wrong")
        assertEquals(SyncResult.Stopped(SyncProblem.REFUSED, 0), sync.run())
        assertNull(dao.uploadNow(id))
        assertEquals(1, dao.uploadCounts().first().waiting)
    }

    @Test
    fun anUnreachableServerIsTriedAgainLater() = runBlocking {
        val id = save()
        config = PaperlessConfig("http://127.0.0.1:9", "secret", on = true)
        assertEquals(SyncResult.Stopped(SyncProblem.UNREACHABLE, 0), sync.run())
        assertNull(dao.uploadNow(id))
    }

    @Test
    fun turnedOffItSendsNothing() = runBlocking {
        save()
        config = config.copy(on = false)
        assertEquals(SyncResult.Off, sync.run())
        assertTrue(paperless.requests.isEmpty())
    }

    @Test
    fun checksTheConnection() {
        val api = PaperlessApi()
        assertEquals(PaperlessCheck.Connected(0), api.check(config))
        assertEquals(PaperlessCheck.Refused, api.check(config.copy(token = "nope")))
        assertEquals(PaperlessCheck.Unreachable, api.check(PaperlessConfig("http://127.0.0.1:9", "secret")))
        assertEquals(PaperlessCheck.NotPaperless, api.check(PaperlessConfig(paperless.address + "/somewhere-else", "secret")))
    }
}
