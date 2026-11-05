package com.moblized.spendscan.server

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP against a stand-in server, including the ways real servers turn requests down. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ServerApiTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val api = ServerApi()
    private val goodAnswer = """{"store":"Kroger","date":"2026-11-02","total":6.93,"subtotal":6.48,"tax":0.45,"tip":null,"discount":null,"payment":"Visa 4242","category":"Groceries","items":[{"name":"Milk","quantity":2,"amount":3.49}]}"""

    private lateinit var http: FakeHttpServer
    private val requests = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var authHeader: String? = null
    /** Decides each answer from the request: (status, what the model wrote). */
    private var respond: (JSONObject) -> Pair<Int, String> = { 200 to goodAnswer }

    private lateinit var photo: File

    @Before
    fun start() {
        http = FakeHttpServer { request ->
            authHeader = request.header("Authorization")
            when (request.path) {
                "/v1/models" -> (if (authHeader == "Bearer wrong") 401 else 200) to """{"data":[{"id":"qwen3-vl:8b"},{"id":"llama3.2:latest"}]}"""
                "/v1/chat/completions" -> {
                    val sent = JSONObject(request.body)
                    requests += sent
                    val (code, content) = respond(sent)
                    code to (
                        if (code == 200) JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
                        else """{"error":"$content"}"""
                        )
                }
                else -> 404 to "{}"
            }
        }
        photo = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "page.jpg")
        photo.outputStream().use { Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @After
    fun stop() = http.close()

    private val config get() = ServerConfig(http.address, model = "qwen3-vl:8b")

    private fun read(text: String? = "KROGER 6.93", pages: List<File> = listOf(photo)) =
        api.read(config, pages, text, listOf("Groceries", "Other"), today)

    private fun hasImage(r: JSONObject): Boolean {
        val content = r.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        return (0 until content.length()).any { content.getJSONObject(it).getString("type") == "image_url" }
    }

    @Test
    fun readsAReceiptFromAPhoto() {
        val outcome = read() as ServerOutcome.Read
        assertTrue(outcome.usedPhoto)
        assertEquals("Kroger", outcome.receipt.store)
        assertEquals(693L, outcome.receipt.totalCents)
        assertEquals(LocalDate.of(2026, 11, 2), outcome.receipt.date)
        assertEquals(2.0, outcome.receipt.items.single().quantity, 0.0)

        val sent = requests.single()
        assertEquals("qwen3-vl:8b", sent.getString("model"))
        assertEquals(false, sent.getBoolean("stream"))
        assertEquals("json_schema", sent.getJSONObject("response_format").getString("type"))
        val content = sent.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getString("text").contains("Groceries, Other"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,/9j/"))
    }

    @Test
    fun aServerThatRejectsTheAnswerFormatGetsAPlainRequest() {
        respond = { r -> if (r.has("response_format")) 400 to "response_format not supported" else 200 to goodAnswer }
        val outcome = read() as ServerOutcome.Read
        assertTrue(outcome.usedPhoto)
        assertEquals(2, requests.size)
        assertTrue(hasImage(requests[1]))
    }

    @Test
    fun aModelThatCantSeePhotosWorksFromThePhonesText() {
        respond = { r -> if (hasImage(r)) 400 to "model does not support images" else 200 to goodAnswer }
        val outcome = read() as ServerOutcome.Read
        assertFalse(outcome.usedPhoto)
        assertEquals(693L, outcome.receipt.totalCents)
        assertTrue(requests.last().getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text").contains("KROGER 6.93"))
    }

    @Test
    fun rubbishFromTheModelFallsThroughTheAttempts() {
        respond = { 200 to "I'm sorry, I can't read that." }
        assertEquals(ServerOutcome.Failed(ServerProblem.NO_USABLE_ANSWER), read())
        assertEquals(4, requests.size)
    }

    @Test
    fun aMissingModelCountsAsNoAnswer() {
        respond = { 404 to "model not found" }
        assertEquals(ServerOutcome.Failed(ServerProblem.NO_USABLE_ANSWER), read())
    }

    @Test
    fun aRefusedKeyStopsRightAway() {
        respond = { 401 to "unauthorized" }
        assertEquals(ServerOutcome.Failed(ServerProblem.REFUSED), read())
        assertEquals(1, requests.size)
    }

    @Test
    fun aDeadServerIsReportedAsUnreachable() {
        val closed = ServerSocket(0).use { it.localPort }
        val dead = ServerConfig("127.0.0.1:$closed", model = "m")
        assertEquals(ServerOutcome.Failed(ServerProblem.UNREACHABLE), api.read(dead, listOf(photo), "x", emptyList(), today))
        assertEquals(ModelList.Failed(ServerProblem.UNREACHABLE), api.models(dead))
    }

    @Test
    fun aPictureThatCantBeOpenedFallsBackToTheText() {
        val notAPicture = File(photo.parentFile, "bad.jpg").apply { writeText("nope") }
        val outcome = read(pages = listOf(notAPicture)) as ServerOutcome.Read
        assertFalse(outcome.usedPhoto)
    }

    @Test
    fun nothingToSendIsNotAnAttempt() {
        val notAPicture = File(photo.parentFile, "bad.jpg").apply { writeText("nope") }
        assertEquals(ServerOutcome.Failed(ServerProblem.NO_USABLE_ANSWER), read(text = null, pages = listOf(notAPicture)))
        assertEquals(0, requests.size)
    }

    @Test
    fun bigPhotosAreShrunk() {
        val big = File(photo.parentFile, "big.jpg")
        big.outputStream().use { Bitmap.createBitmap(1000, 5000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        assertTrue(read(pages = listOf(big)) is ServerOutcome.Read)
        val url = requests.single().getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
        val bytes = android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        assertEquals(2000, maxOf(decoded.width, decoded.height))
        assertEquals(400, minOf(decoded.width, decoded.height))
    }

    @Test
    fun listsModelsAndSendsTheKey() {
        val found = api.models(config.copy(key = " secret ")) as ModelList.Found
        assertEquals(listOf("qwen3-vl:8b", "llama3.2:latest"), found.names)
        assertEquals("Bearer secret", authHeader)
        assertEquals(ModelList.Failed(ServerProblem.REFUSED), api.models(config.copy(key = "wrong")))
        api.models(config)
        assertNull(authHeader)
    }

    @Test
    fun readsTheWaysModelsWriteJson() {
        fun parse(s: String) = ServerJson.parseReceipt(s, today)
        assertEquals(693L, parse("""```json
            {"total": 6.93}
            ```""")?.totalCents)
        assertEquals(693L, parse("""<think>The total {looks} like 6.93</think>{"total":6.93}""")?.totalCents)
        assertEquals(693L, parse("""Here it is: {"total":"$6.93"} Hope that helps!""")?.totalCents)
        assertEquals(1999L, parse("""{"total":19.99}""")?.totalCents)
        assertEquals(1000L, parse("""{"total":10}""")?.totalCents)
        assertNull(parse("""{"total":null,"store":null,"items":[]}"""))
        assertNull(parse("""{"total":-5}"""))
        assertNull(parse("no json here"))
        assertNull(parse("""{"total":6.93"""))

        val r = parse("""{"store":"null","total":5,"tip":"","discount":-1.50,"date":"11/02/26","items":[{"name":"Tea","amount":"3.50"},{"name":"","amount":1},{"name":"No price"}]}""")!!
        assertNull(r.store)
        assertNull(r.tipCents)
        assertEquals(150L, r.discountCents)
        assertEquals(LocalDate.of(2026, 11, 2), r.date)
        assertEquals(listOf("Tea"), r.items.map { it.name })
        assertEquals(1.0, r.items.single().quantity, 0.0)
        // A date from the future isn't kept.
        assertNull(parse("""{"total":5,"date":"2030-01-01"}""")?.date)
    }

    @Test
    fun readsFuelDetails() {
        val r = ServerJson.parseReceipt("""{"total":42.70,"gallons":12.345,"price_per_gallon":"3.459"}""", today)!!
        assertEquals(12345L, r.gallonsMilli)
        assertEquals(3459L, r.priceMilli)
        val none = ServerJson.parseReceipt("""{"total":5,"gallons":null,"price_per_gallon":0}""", today)!!
        assertNull(none.gallonsMilli)
        assertNull(none.priceMilli)

        val merged = ServerMerge.merge(r, null, today)
        assertEquals(com.moblized.spendscan.parse.ParsedFuel(12345, 3459), merged.fuel)
        // Gallons that can't be right for the money are dropped.
        assertNull(ServerMerge.merge(r.copy(gallonsMilli = 200_000), null, today).fuel)
    }

    @Test
    fun readsContentGivenAsParts() {
        val json = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", """{"total":1.25}"""))))),
        ).toString()
        assertEquals(125L, ServerJson.parseResponse(json, today)?.totalCents)
        assertNull(ServerJson.parseResponse("""{"choices":[]}""", today))
        assertNull(ServerJson.parseResponse("not json", today))
    }

    @Test
    fun theAnswerFormatIsWhatStrictServersWant() {
        val schema = JSONObject(ServerJson.request("m", emptyList(), "x", emptyList(), today, true))
            .getJSONObject("response_format").getJSONObject("json_schema")
        assertEquals("receipt", schema.getString("name"))
        val s = schema.getJSONObject("schema")
        val properties = s.getJSONObject("properties")
        val required = s.getJSONArray("required")
        // Strict servers need every property listed as required.
        assertEquals(properties.length(), required.length())
        assertEquals(false, s.getBoolean("additionalProperties"))
        assertEquals(JSONArray(listOf("number", "null")).toString(), properties.getJSONObject("total").getJSONArray("type").toString())
        assertFalse(JSONObject(ServerJson.request("m", emptyList(), "x", emptyList(), today, false)).has("response_format"))
    }
}
