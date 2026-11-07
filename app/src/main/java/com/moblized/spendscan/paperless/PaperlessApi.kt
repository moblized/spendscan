package com.moblized.spendscan.paperless

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** How a connection check went. */
sealed interface PaperlessCheck {
    data class Connected(val documents: Int) : PaperlessCheck
    data object Refused : PaperlessCheck
    data object NotPaperless : PaperlessCheck
    data object Unreachable : PaperlessCheck
}

/** A receipt on its way to becoming a Paperless document. */
class NewDocument(
    val fileName: String,
    val pdf: ByteArray,
    val title: String,
    val created: LocalDate,
    val correspondent: Long?,
    val documentType: Long?,
    val tags: List<Long>,
)

/** Talks to Paperless-ngx's REST API with an API token. Every call blocks, so call it off the main thread. */
class PaperlessApi(private val client: OkHttpClient = defaultClient) {
    /** Paperless, or something in front of it, answered with an error. [text] is what it said. */
    class Failure(val code: Int, val text: String) : IOException("HTTP $code")

    /** The address isn't one a request can go to. */
    class BadAddress : IOException("Not an address")

    private fun url(config: PaperlessConfig, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val base = "${config.apiBase}$path".toHttpUrlOrNull() ?: throw BadAddress()
        return base.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    private fun call(config: PaperlessConfig, url: HttpUrl, build: Request.Builder.() -> Unit = {}): String {
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Token ${config.token.trim()}")
                .header("Accept", "application/json")
                .apply(build)
                .build()
        } catch (_: IllegalArgumentException) {
            // A token with characters no header can carry, which can't be a real one.
            throw Failure(401, "")
        }
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Failure(response.code, body)
            return body
        }
    }

    private fun get(config: PaperlessConfig, path: String, query: Map<String, String> = emptyMap()) = call(config, url(config, path, query))

    fun check(config: PaperlessConfig): PaperlessCheck = try {
        PaperlessJson.documentCount(get(config, "/documents/", mapOf("page_size" to "1", "fields" to "id")))
            ?.let { PaperlessCheck.Connected(it) } ?: PaperlessCheck.NotPaperless
    } catch (e: Failure) {
        if (e.code == 401 || e.code == 403) PaperlessCheck.Refused else PaperlessCheck.NotPaperless
    } catch (_: BadAddress) {
        PaperlessCheck.Unreachable
    } catch (_: IOException) {
        PaperlessCheck.Unreachable
    }

    /**
     * Finds a tag, correspondent, document type or custom field by name, ignoring case, and makes it if it isn't there.
     * Null if Paperless won't make it, in which case the document goes without it.
     */
    fun label(config: PaperlessConfig, kind: String, name: String, dataType: String? = null): Long? {
        find(config, kind, name, dataType)?.let { return it }
        val made = try {
            PaperlessJson.createdId(
                call(config, url(config, "/$kind/")) { post(PaperlessJson.newLabel(name, dataType).toRequestBody(JSON)) },
            )
        } catch (e: Failure) {
            if (e.code == 401 || e.code >= 500) throw e
            // Most likely it was there all along, under a name the search missed.
            null
        }
        return made ?: find(config, kind, name, dataType)
    }

    private fun find(config: PaperlessConfig, kind: String, name: String, dataType: String?): Long? {
        for (page in 1..MAX_PAGES) {
            val body = get(config, "/$kind/", mapOf("name__iexact" to name, "page_size" to "100", "page" to "$page"))
            val found = PaperlessJson.labels(body) ?: throw Failure(200, body.take(200))
            found.labels.firstOrNull { it.name.trim().equals(name, ignoreCase = true) && (dataType == null || it.dataType == dataType) }
                ?.let { return it.id }
            if (!found.hasMore) return null
        }
        return null
    }

    /** Sends the PDF. Paperless takes it in the background, so what comes back is the id of that job. */
    fun upload(config: PaperlessConfig, d: NewDocument): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", d.title)
            .addFormDataPart("created", d.created.toString())
            .apply {
                d.correspondent?.let { addFormDataPart("correspondent", "$it") }
                d.documentType?.let { addFormDataPart("document_type", "$it") }
                d.tags.distinct().forEach { addFormDataPart("tags", "$it") }
            }
            .addFormDataPart("document", d.fileName, d.pdf.toRequestBody(PDF))
            .build()
        val answer = call(config, url(config, "/documents/post_document/")) { post(form) }
        return PaperlessJson.taskId(answer) ?: throw Failure(200, answer.take(200))
    }

    fun task(config: PaperlessConfig, id: String): TaskState = PaperlessJson.task(get(config, "/tasks/", mapOf("task_id" to id)))

    fun document(config: PaperlessConfig, id: Long): DocumentNow {
        val body = get(config, "/documents/$id/")
        return PaperlessJson.document(body) ?: throw Failure(200, body.take(200))
    }

    fun updateDocument(config: PaperlessConfig, id: Long, json: String) {
        call(config, url(config, "/documents/$id/")) { patch(json.toRequestBody(JSON)) }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val PDF = "application/pdf".toMediaType()
        private const val MAX_PAGES = 20

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // A big multi-page receipt over a slow phone connection.
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
