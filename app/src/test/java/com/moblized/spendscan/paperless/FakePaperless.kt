package com.moblized.spendscan.paperless

import com.moblized.spendscan.server.FakeHttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.net.URLDecoder

/** Enough of Paperless-ngx's API, behind a real HTTP server, to send receipts to. */
class FakePaperless(private val token: String = "secret") : Closeable {
    class Part(val name: String, val fileName: String?, val bytes: ByteArray) {
        val text: String get() = bytes.decodeToString()
    }

    class Upload(val parts: List<Part>) {
        fun field(name: String): String? = parts.firstOrNull { it.name == name && it.fileName == null }?.text
        fun fields(name: String): List<String> = parts.filter { it.name == name && it.fileName == null }.map { it.text }
        val file: Part get() = parts.single { it.fileName != null }
    }

    class Doc(val id: Long, var tags: MutableList<Long>, var fields: JSONArray = JSONArray())

    val requests = mutableListOf<FakeHttpServer.Request>()
    val uploads = mutableListOf<Upload>()
    val patches = mutableListOf<Pair<Long, JSONObject>>()
    val labels = mutableMapOf<String, MutableList<JSONObject>>()
    val docs = mutableMapOf<Long, Doc>()

    /** What the tasks endpoint says about each upload, in order. Missing ones succeed straight away. */
    val taskAnswers = mutableMapOf<Int, JSONObject>()

    /** Answers the next upload with this code and body instead of taking it. */
    var refuseNextUpload: Pair<Int, String>? = null

    var nextDocumentId = 42L
    private var nextLabelId = 1L

    private val server = FakeHttpServer(::handle)
    val address: String get() = server.address

    fun labelId(kind: String, name: String): Long? = labels[kind]?.firstOrNull { it.getString("name") == name }?.getLong("id")

    @Synchronized
    private fun handle(r: FakeHttpServer.Request): Pair<Int, String> {
        requests += r
        if (r.header("authorization") != "Token $token") return 401 to """{"detail":"Invalid token."}"""
        val uri = URI(r.path)
        val path = uri.path
        val query = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val label = Regex("^/api/(tags|correspondents|document_types|custom_fields)/$").find(path)?.groupValues?.get(1)
        val document = Regex("^/api/documents/(\\d+)/$").find(path)?.groupValues?.get(1)?.toLong()
        return when {
            r.method == "GET" && path == "/api/documents/" -> 200 to """{"count":${docs.size},"next":null,"previous":null,"results":[]}"""
            label != null && r.method == "GET" -> {
                val wanted = query["name__iexact"]
                val found = labels[label].orEmpty().filter { wanted == null || it.getString("name").equals(wanted, ignoreCase = true) }
                200 to JSONObject().put("count", found.size).put("next", JSONObject.NULL).put("results", JSONArray(found)).toString()
            }
            label != null && r.method == "POST" -> {
                val made = JSONObject(r.body).put("id", nextLabelId++)
                labels.getOrPut(label) { mutableListOf() } += made
                201 to made.toString()
            }
            r.method == "POST" && path == "/api/documents/post_document/" -> {
                refuseNextUpload?.let {
                    refuseNextUpload = null
                    return it
                }
                uploads += Upload(multipart(r))
                val tags = uploads.last().fields("tags").map { it.toLong() }.toMutableList()
                val n = uploads.size
                if (n !in taskAnswers) {
                    val id = nextDocumentId++
                    docs[id] = Doc(id, tags)
                    taskAnswers[n] = JSONObject().put("status", "SUCCESS").put("related_document", "$id")
                }
                200 to "\"task-$n\""
            }
            r.method == "GET" && path == "/api/tasks/" -> {
                val n = query["task_id"]?.removePrefix("task-")?.toIntOrNull()
                val answer = n?.let { taskAnswers[it] }
                200 to (if (answer == null) "[]" else JSONArray().put(JSONObject(answer.toString()).put("task_id", "task-$n")).toString())
            }
            document != null && r.method == "GET" -> {
                val d = docs[document] ?: return 404 to """{"detail":"No Document matches the given query."}"""
                200 to JSONObject().put("id", d.id).put("title", "x").put("tags", JSONArray(d.tags)).put("custom_fields", d.fields).toString()
            }
            document != null && r.method == "PATCH" -> {
                val d = docs[document] ?: return 404 to """{"detail":"Not found."}"""
                val patch = JSONObject(r.body)
                patches += document to patch
                patch.optJSONArray("tags")?.let { a -> d.tags = (0 until a.length()).map { a.getLong(it) }.toMutableList() }
                patch.optJSONArray("custom_fields")?.let { d.fields = it }
                200 to JSONObject().put("id", d.id).toString()
            }
            else -> 404 to """{"detail":"Not found."}"""
        }
    }

    /** Splits a multipart form into its parts, byte for byte. */
    private fun multipart(r: FakeHttpServer.Request): List<Part> {
        val boundary = r.header("content-type")!!.substringAfter("boundary=").trim('"')
        val all = String(r.raw, Charsets.ISO_8859_1)
        return all.split("--$boundary").drop(1).filterNot { it.startsWith("--") }.map { chunk ->
            val head = chunk.substringBefore("\r\n\r\n")
            val body = chunk.substringAfter("\r\n\r\n").removeSuffix("\r\n")
            val name = Regex("""name="([^"]*)"""").find(head)!!.groupValues[1]
            val fileName = Regex("""filename="([^"]*)"""").find(head)?.groupValues?.get(1)
            Part(name, fileName, body.toByteArray(Charsets.ISO_8859_1))
        }
    }

    override fun close() = server.close()
}
