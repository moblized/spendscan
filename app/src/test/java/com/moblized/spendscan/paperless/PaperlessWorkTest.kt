package com.moblized.spendscan.paperless

import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.moblized.spendscan.App
import com.moblized.spendscan.data.Receipt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The real background job, through WorkManager, from a saved receipt to a document in Paperless. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperlessWorkTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val paperless = FakePaperless()

    @After
    fun tearDown() {
        paperless.close()
        app.db.close()
    }

    private fun waitFor(id: java.util.UUID, wm: WorkManager): WorkInfo {
        val until = System.currentTimeMillis() + 20_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val info = wm.getWorkInfoById(id).get()
            if (info != null && info.state.isFinished) return info
            check(System.currentTimeMillis() < until) { "Still ${info?.state}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun sendsInTheBackgroundOnceThereIsAConnection() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        app.prefs.setPaperless(PaperlessConfig(paperless.address, "secret", on = true))
        app.photos.file("page.jpg").writeBytes(ReceiptPdfTest.jpeg(300, 900))
        val category = app.dao.categoriesNow().first().id
        val id = app.dao.saveReceipt(
            Receipt(store = "Kroger", dateEpochDay = LocalDate.of(2026, 11, 2).toEpochDay(), totalCents = 693, categoryId = category, photos = "page.jpg"),
            emptyList(),
        )

        PaperlessWork.start(app)
        val wm = WorkManager.getInstance(app)
        val queued = wm.getWorkInfosForUniqueWork("paperless").get().single()
        // Nothing goes until there's a connection.
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(0, paperless.uploads.size)

        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(queued.id)
        assertEquals(WorkInfo.State.SUCCEEDED, waitFor(queued.id, wm).state)

        assertEquals(1, paperless.uploads.size)
        assertNotNull(app.dao.uploadNow(id)?.documentId)
        assertNull(app.prefs.paperlessRun.value!!.problem)
    }
}
