package com.moblized.spendscan.paperless

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate

/** Where Paperless is with a file it was sent. */
sealed interface TaskState {
    /** Still in the queue or being read. */
    data object Waiting : TaskState

    /** Paperless has never heard of it. Right after sending, that only means it hasn't caught up yet. */
    data object Missing : TaskState

    /** [documentId] is null when Paperless made the document but didn't say which one. */
    data class Made(val documentId: Long?) : TaskState

    /** Paperless already had exactly this file, so it kept the one it had. */
    data class AlreadyThere(val documentId: Long?) : TaskState

    data class Failed(val message: String) : TaskState
}

/** A tag, correspondent, document type or custom field. */
data class Label(val id: Long, val name: String, val dataType: String?)

data class LabelPage(val labels: List<Label>, val hasMore: Boolean)

/** The parts of a document this app changes, as they are now. */
data class DocumentNow(val tags: List<Long>, val customFields: List<Pair<Long, Any?>>)

/** Reads and writes Paperless-ngx's JSON. Copes with the older and newer shapes of its answers. */
object PaperlessJson {
    private fun parse(body: String): Any? = try {
        JSONTokener(body.trim()).nextValue()
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.longOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    /** The results of a list, whether it comes whole or a page at a time. */
    private fun results(body: String): Pair<JSONArray, Boolean>? = when (val v = parse(body)) {
        is JSONArray -> v to false
        is JSONObject -> v.optJSONArray("results")?.let { it to (v.stringOrNull("next") != null) }
        else -> null
    }

    /** The number of documents, from a list of them. Null when the answer isn't Paperless's. */
    fun documentCount(body: String): Int? {
        val o = parse(body) as? JSONObject ?: return null
        if (!o.has("count") || !o.has("results")) return null
        return o.optInt("count", -1).takeIf { it >= 0 }
    }

    /** The id of the job that's taking in an upload: a bare string, or an object holding it. */
    fun taskId(body: String): String? {
        val id = when (val v = parse(body)) {
            is String -> v
            is JSONObject -> v.stringOrNull("task_id") ?: v.stringOrNull("id")
            else -> body
        }
        // A web page or anything else that isn't an id gets no further.
        return id?.trim()?.takeIf { TASK_ID.matches(it) }
    }

    private val TASK_ID = Regex("[0-9A-Za-z_-]{6,80}")

    private val DOCUMENT_ID = Regex("""document id (\d+)""", RegexOption.IGNORE_CASE)
    private val DUPLICATE_OF = Regex("""\(#(\d+)\)""")

    fun task(body: String): TaskState {
        val (list, _) = results(body) ?: return TaskState.Missing
        val t = list.optJSONObject(0) ?: return TaskState.Missing
        val result = t.stringOrNull("result").orEmpty()
        val document = t.longOrNull("related_document") ?: t.longOrNull("document_id") ?: t.longOrNull("document")
            ?: DOCUMENT_ID.find(result)?.groupValues?.get(1)?.toLongOrNull()
        return when (t.stringOrNull("status")?.uppercase()) {
            "SUCCESS" -> TaskState.Made(document)
            "FAILURE" -> if (result.contains("duplicate", ignoreCase = true)) {
                TaskState.AlreadyThere(document ?: DUPLICATE_OF.findAll(result).lastOrNull()?.groupValues?.get(1)?.toLongOrNull())
            } else {
                TaskState.Failed(result.ifBlank { "Paperless couldn't read the file." }.take(300))
            }
            "REVOKED" -> TaskState.Failed("The upload was cancelled in Paperless.")
            else -> TaskState.Waiting
        }
    }

    fun labels(body: String): LabelPage? {
        val (list, more) = results(body) ?: return null
        val labels = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.longOrNull("id") ?: return@mapNotNull null
            Label(id, o.optString("name"), o.stringOrNull("data_type"))
        }
        return LabelPage(labels, more)
    }

    fun createdId(body: String): Long? = (parse(body) as? JSONObject)?.longOrNull("id")

    fun newLabel(name: String, dataType: String? = null): String = JSONObject().apply {
        put("name", name)
        if (dataType == null) {
            // Only this app puts these on documents, so Paperless shouldn't start adding them to anything else.
            put("matching_algorithm", 0)
        } else {
            put("data_type", dataType)
        }
    }.toString()

    fun document(body: String): DocumentNow? {
        val o = parse(body) as? JSONObject ?: return null
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optLong(it, -1).takeIf { id -> id >= 0 } } }.orEmpty()
        val fields = o.optJSONArray("custom_fields")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val f = a.optJSONObject(i) ?: return@mapNotNull null
                val id = f.longOrNull("field") ?: return@mapNotNull null
                id to (if (f.isNull("value")) null else f.opt("value"))
            }
        }.orEmpty()
        return DocumentNow(tags, fields)
    }

    /** Sets everything this app manages on a document, keeping the tags and fields someone added in Paperless. */
    fun update(
        title: String,
        created: LocalDate,
        correspondent: Long?,
        documentType: Long?,
        tags: List<Long>,
        customFields: List<Pair<Long, Any?>>,
    ): String = JSONObject().apply {
        put("title", title)
        put("created", created.toString())
        correspondent?.let { put("correspondent", it) }
        documentType?.let { put("document_type", it) }
        put("tags", JSONArray(tags.distinct()))
        put(
            "custom_fields",
            JSONArray(customFields.map { (field, value) -> JSONObject().put("field", field).put("value", value ?: JSONObject.NULL) }),
        )
    }.toString()

    /** Paperless's own words about why it turned something down, if it gave any. */
    fun complaint(body: String): String? {
        val words = mutableListOf<String>()
        fun collect(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) words += v.trim()
                is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i))
                is JSONObject -> v.keys().forEach { collect(v.opt(it)) }
            }
        }
        val parsed = parse(body)
        if (parsed !is JSONObject && parsed !is JSONArray) return null
        collect(parsed)
        return words.distinct().joinToString(" ").take(300).ifBlank { null }
    }
}
