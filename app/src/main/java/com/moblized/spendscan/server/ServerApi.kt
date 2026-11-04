package com.moblized.spendscan.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

enum class ServerProblem { UNREACHABLE, REFUSED, NO_USABLE_ANSWER }

sealed interface ServerOutcome {
    /** [usedPhoto] is false when the model couldn't take images and worked from the phone's text instead. */
    data class Read(val receipt: ServerReceipt, val usedPhoto: Boolean) : ServerOutcome
    data class Failed(val problem: ServerProblem) : ServerOutcome
}

sealed interface ModelList {
    data class Found(val names: List<String>) : ModelList
    data class Failed(val problem: ServerProblem) : ModelList
}

/** Talks to your language model server. Every call blocks, so call it off the main thread. */
class ServerApi {
    private class HttpFailure(val code: Int) : IOException("HTTP $code")

    private fun open(config: ServerConfig, path: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(config.apiBase + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (config.key.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.key.trim()}")
        }

    private fun answer(c: HttpURLConnection): String {
        try {
            if (c.responseCode !in 200..299) throw HttpFailure(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun models(config: ServerConfig): ModelList = try {
        ModelList.Found(ServerJson.parseModels(answer(open(config, "/models", MODELS_TIMEOUT_MS))))
    } catch (e: HttpFailure) {
        ModelList.Failed(if (e.code == 401 || e.code == 403) ServerProblem.REFUSED else ServerProblem.NO_USABLE_ANSWER)
    } catch (_: IOException) {
        ModelList.Failed(ServerProblem.UNREACHABLE)
    }

    private fun post(config: ServerConfig, body: String): String {
        val bytes = body.toByteArray()
        val c = open(config, "/chat/completions", READ_TIMEOUT_MS).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
        }
        c.outputStream.use { it.write(bytes) }
        return answer(c)
    }

    /**
     * Asks the model to read a receipt. Servers and models differ in what they accept, so this starts with the best
     * request and falls back: first without the strict answer format, then without the photo if the model can't see.
     */
    fun read(config: ServerConfig, pages: List<File>, phoneText: String?, categories: List<String>, today: LocalDate): ServerOutcome {
        val images = pages.take(MAX_PAGES).mapNotNull(::encode)
        val text = phoneText?.takeIf { it.isNotBlank() }
        val attempts = buildList {
            if (images.isNotEmpty()) {
                add(true to true)
                add(true to false)
            }
            if (text != null) {
                add(false to true)
                add(false to false)
            }
        }
        for ((usePhoto, useSchema) in attempts) {
            try {
                val body = ServerJson.request(config.model, if (usePhoto) images else emptyList(), text, categories, today, useSchema)
                val receipt = ServerJson.parseResponse(post(config, body), today)
                if (receipt != null) return ServerOutcome.Read(receipt, usePhoto)
            } catch (e: HttpFailure) {
                if (e.code == 401 || e.code == 403) return ServerOutcome.Failed(ServerProblem.REFUSED)
            } catch (_: IOException) {
                return ServerOutcome.Failed(ServerProblem.UNREACHABLE)
            }
        }
        return ServerOutcome.Failed(ServerProblem.NO_USABLE_ANSWER)
    }

    /** Shrinks a page to a size models handle well, the right way up, as base64. Null if it isn't a readable picture. */
    private fun encode(file: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val matrix = Matrix()
        val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation = ExifInterface(file).rotationDegrees
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        val upright = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val bytes = ByteArrayOutputStream().use {
            upright.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    private companion object {
        const val MAX_PAGES = 5
        const val MAX_SIDE = 2000
        const val CONNECT_TIMEOUT_MS = 6_000
        const val MODELS_TIMEOUT_MS = 15_000

        // A model that isn't in memory yet can take a while to load before it starts answering.
        const val READ_TIMEOUT_MS = 180_000
    }
}
