package com.moblized.spendscan.server

import com.moblized.spendscan.data.FuelUnits
import com.moblized.spendscan.data.Money
import com.moblized.spendscan.parse.ReceiptParser
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** The OpenAI-style messages sent to the server and read back from it. */
object ServerJson {
    private fun nullable(type: String) = JSONObject().put("type", JSONArray().put(type).put("null"))

    private fun receiptSchema(): JSONObject {
        val item = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", JSONObject().put("type", "string"))
                    .put("quantity", JSONObject().put("type", "number"))
                    .put("amount", JSONObject().put("type", "number")),
            )
            .put("required", JSONArray(listOf("name", "quantity", "amount")))
            .put("additionalProperties", false)
        val texts = listOf("store", "date", "payment", "category")
        val numbers = listOf("total", "subtotal", "tax", "tip", "discount", "gallons", "price_per_gallon")
        val properties = JSONObject()
        texts.forEach { properties.put(it, nullable("string")) }
        numbers.forEach { properties.put(it, nullable("number")) }
        properties.put("items", JSONObject().put("type", "array").put("items", item))
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(texts + numbers + "items"))
            .put("additionalProperties", false)
    }

    fun request(
        model: String,
        pagesBase64: List<String>,
        phoneText: String?,
        categories: List<String>,
        today: LocalDate,
        useSchema: Boolean,
        pump: Boolean = false,
    ): String {
        val text = if (pump) ServerPrompt.pump(phoneText, categories, today)
        else ServerPrompt.user(pagesBase64.isNotEmpty(), phoneText, categories, today)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        pagesBase64.forEach { page ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$page")),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", 4000)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", ServerPrompt.SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", content)),
            )
        if (useSchema) {
            body.put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("json_schema", JSONObject().put("name", "receipt").put("schema", receiptSchema())),
            )
        }
        return body.toString()
    }

    /** The model names from GET /models. Null when the answer isn't a model list at all, like a web page. */
    fun parseModels(json: String): List<String>? {
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return null
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } }
    }

    /** The receipt inside a chat completion, or null if the answer has none. */
    fun parseResponse(json: String, today: LocalDate): ServerReceipt? {
        val message = runCatching {
            JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }.getOrNull() ?: return null
        val content = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString("")
            else -> return null
        }
        return parseReceipt(content, today)
    }

    /** Reads the JSON a model wrote, which may come wrapped in thinking or a code fence. */
    fun parseReceipt(content: String, today: LocalDate): ServerReceipt? {
        val cleaned = content.replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull() ?: return null

        val items = ArrayList<ServerItem>()
        o.optJSONArray("items")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val amount = cents(item, "amount") ?: continue
                val name = text(item, "name") ?: continue
                val quantity = item.optDouble("quantity", 1.0).takeIf { it > 0 && !it.isNaN() } ?: 1.0
                items += ServerItem(name, quantity, abs(amount))
            }
        }
        val receipt = ServerReceipt(
            store = text(o, "store"),
            // The phone's date reader copes with every format, and ignores dates that can't be right.
            date = text(o, "date")?.let { ReceiptParser.parse(listOf(it), today).date },
            totalCents = cents(o, "total")?.takeIf { it > 0 },
            subtotalCents = cents(o, "subtotal")?.takeIf { it > 0 },
            taxCents = cents(o, "tax")?.takeIf { it >= 0 },
            tipCents = cents(o, "tip")?.takeIf { it > 0 },
            discountCents = cents(o, "discount")?.let { abs(it) }?.takeIf { it > 0 },
            payment = text(o, "payment"),
            category = text(o, "category"),
            items = items,
            gallonsMilli = thousandths(o, "gallons"),
            priceMilli = thousandths(o, "price_per_gallon"),
        )
        return receipt.takeIf { it.hasAnything }
    }

    private fun text(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun thousandths(o: JSONObject, key: String): Long? {
        if (o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() && it > 0 }?.let { (it * 1000).roundToLong() }
            is String -> FuelUnits.parseMilli(v)?.takeIf { it > 0 }
            else -> null
        }
    }

    private fun cents(o: JSONObject, key: String): Long? {
        if (o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }?.let { (it * 100).roundToLong() }
            is String -> {
                val t = v.trim()
                val negative = t.startsWith("-")
                Money.parse(t.removePrefix("-"))?.let { if (negative) -it else it }
            }
            else -> null
        }
    }
}
