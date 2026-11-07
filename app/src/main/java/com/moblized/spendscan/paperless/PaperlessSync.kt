package com.moblized.spendscan.paperless

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.moblized.spendscan.data.Receipt
import com.moblized.spendscan.data.SpendDao
import com.moblized.spendscan.data.Upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Currency
import java.util.Locale
import kotlin.math.max

/** Why a run stopped before it was through. Each one gets tried again later, except a refused token. */
enum class SyncProblem { UNREACHABLE, REFUSED, NOT_PAPERLESS, SERVER_ERROR }

sealed interface SyncResult {
    data object Off : SyncResult

    /** [processing] receipts are with Paperless but it hasn't said which documents they became yet. */
    data class Finished(val sent: Int, val processing: Int) : SyncResult

    data class Stopped(val problem: SyncProblem, val sent: Int) : SyncResult
}

/**
 * Sends receipts with photos to Paperless-ngx, a PDF each, and keeps their details there up to date when they're
 * edited. Nothing is ever deleted from Paperless.
 */
class PaperlessSync(
    private val dao: SpendDao,
    private val photoFile: (String) -> File,
    private val config: () -> PaperlessConfig,
    private val api: PaperlessApi = PaperlessApi(),
    private val now: () -> Long = System::currentTimeMillis,
    private val currencyCode: () -> String? = { runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() },
) {
    private val mutex = Mutex()

    suspend fun run(): SyncResult = mutex.withLock { withContext(Dispatchers.IO) { runNow() } }

    private suspend fun runNow(): SyncResult {
        val cfg = config()
        if (!cfg.isOn) return SyncResult.Off
        val labels = Labels(cfg)
        var sent = 0
        return try {
            // Goes round until nothing is waiting, so a receipt saved partway through still goes this time.
            for (round in 1..MAX_ROUNDS) {
                val waiting = dao.receiptsToSend()
                if (waiting.isEmpty()) break
                waiting.forEach { if (send(cfg, labels, it)) sent++ }
            }
            var processing = 0
            dao.uploadsToFinish().forEach { if (!finish(cfg, labels, it)) processing++ }
            SyncResult.Finished(sent, processing)
        } catch (e: PaperlessApi.Failure) {
            SyncResult.Stopped(
                when {
                    e.code == 401 || e.code == 403 -> SyncProblem.REFUSED
                    e.code >= 500 -> SyncProblem.SERVER_ERROR
                    else -> SyncProblem.NOT_PAPERLESS
                },
                sent,
            )
        } catch (_: IOException) {
            SyncResult.Stopped(SyncProblem.UNREACHABLE, sent)
        }
    }

    private suspend fun categoryName(id: Long): String? = dao.categoriesNow().firstOrNull { it.id == id }?.name

    /** Sends one receipt. Always leaves a record of what happened, so the same receipt never goes twice. */
    private suspend fun send(cfg: PaperlessConfig, labels: Labels, r: Receipt): Boolean {
        val files = r.photoList.map(photoFile).filter { it.isFile }
        if (files.isEmpty()) {
            dao.putUpload(Upload(r.id, problem = MISSING_PHOTOS, sentAt = now()))
            return false
        }
        val pages = files.mapNotNull { runCatching { PhotoPages.page(it) }.getOrNull() }
        if (pages.isEmpty()) {
            dao.putUpload(Upload(r.id, problem = UNREADABLE_PHOTOS, sentAt = now()))
            return false
        }
        val details = PaperlessDetails.of(r, categoryName(r.categoryId), currencyCode())
        val tag = details.tag?.let { labels.tag(it) }
        val document = NewDocument(
            fileName = details.fileName,
            pdf = ReceiptPdf.write(pages),
            title = details.title,
            created = details.created,
            correspondent = labels.correspondent(details.correspondent),
            documentType = labels.receiptType(),
            tags = listOfNotNull(tag),
        )
        val task = try {
            api.upload(cfg, document)
        } catch (e: PaperlessApi.Failure) {
            // Something about this receipt in particular. The rest can still go.
            val problem = when (e.code) {
                413 -> TOO_BIG
                400 -> PaperlessJson.complaint(e.text)?.let { "Paperless said: $it" } ?: "Paperless turned it down."
                else -> throw e
            }
            dao.putUpload(Upload(r.id, problem = problem, sentAt = now()))
            return false
        }
        dao.putUpload(Upload(r.id, taskId = task, tagId = tag, sentAt = now()))
        return true
    }

    /** Finds out which document a receipt became, then fills in its details. False while Paperless is still on it. */
    private suspend fun finish(cfg: PaperlessConfig, labels: Labels, u: Upload): Boolean {
        val documentId: Long = u.documentId ?: run {
            val taskId = u.taskId ?: run {
                dao.setUploadProblem(u.receiptId, LOST)
                return true
            }
            val made = when (val t = api.task(cfg, taskId)) {
                TaskState.Waiting -> return false
                TaskState.Missing -> {
                    if (now() - u.sentAt < LOST_AFTER_MS) return false
                    dao.setUploadProblem(u.receiptId, LOST)
                    return true
                }
                is TaskState.Failed -> {
                    dao.setUploadProblem(u.receiptId, "Paperless said: ${t.message}")
                    return true
                }
                is TaskState.Made -> t.documentId ?: 0L
                is TaskState.AlreadyThere -> t.documentId ?: 0L
            }
            dao.setUploadDocument(u.receiptId, made)
            made
        }
        if (documentId <= 0L) return true

        val r = dao.receiptNow(u.receiptId) ?: return true
        val details = PaperlessDetails.of(r, categoryName(r.categoryId), currencyCode())
        val tag = details.tag?.let { labels.tag(it) }
        val current = try {
            api.document(cfg, documentId)
        } catch (e: PaperlessApi.Failure) {
            // Deleted in Paperless, or taken out of this account's reach. That's for whoever did it to decide.
            if (e.code == 404 || e.code == 403) {
                dao.uploadUpdated(u.receiptId, u.tagId, u.changes)
                return true
            }
            throw e
        }
        val total = labels.totalField()
        val fields = current.customFields.filter { it.first != total } + listOfNotNull(total?.let { it to details.total })
        val tags = current.tags.filter { it != u.tagId } + listOfNotNull(tag)
        api.updateDocument(
            cfg,
            documentId,
            PaperlessJson.update(details.title, details.created, labels.correspondent(details.correspondent), labels.receiptType(), tags, fields),
        )
        dao.uploadUpdated(u.receiptId, tag, u.changes)
        return true
    }

    /** Looks each name up once a run. */
    private inner class Labels(private val cfg: PaperlessConfig) {
        private val found = HashMap<String, Long?>()

        private fun lookUp(kind: String, name: String, dataType: String? = null): Long? {
            val key = "$kind\n${name.lowercase()}"
            if (key !in found) found[key] = api.label(cfg, kind, name, dataType)
            return found[key]
        }

        fun correspondent(name: String) = lookUp("correspondents", name)
        fun tag(name: String) = lookUp("tags", name)
        fun receiptType() = lookUp("document_types", PaperlessDetails.DOCUMENT_TYPE)
        fun totalField() = lookUp("custom_fields", PaperlessDetails.TOTAL_FIELD, "monetary")
    }

    companion object {
        private const val MAX_ROUNDS = 50
        private const val LOST_AFTER_MS = 24 * 60 * 60 * 1000L

        const val MISSING_PHOTOS = "Its photos aren't on this phone any more."
        const val UNREADABLE_PHOTOS = "Its photos couldn't be opened."
        const val TOO_BIG = "It was too big for your server. Raise the upload size limit on whatever sits in front of Paperless."
        const val LOST = "Paperless lost track of it."
    }
}

/** Turns receipt photos into PDF pages, the right way up. */
object PhotoPages {
    private const val MAX_SIDE = 4096

    fun page(file: File): ReceiptPdf.Page? {
        val bytes = file.readBytes()
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        Jpeg.info(bytes)?.let { if (rotation == 0) return ReceiptPdf.Page(bytes, it) }

        // Turned on its side, or not a JPEG a PDF can hold (a HEIC from the gallery, say): draw it again as one.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val upright = if (rotation == 0) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        val jpeg = ByteArrayOutputStream().use {
            upright.compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        }
        return Jpeg.info(jpeg)?.let { ReceiptPdf.Page(jpeg, it) }
    }
}
