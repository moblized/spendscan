package com.moblized.spendscan.scan

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Keeps scanned pages in the app's private storage. The scanner's own copies are temporary. */
class PhotoStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "receipts").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    suspend fun import(pages: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        pages.mapNotNull { uri ->
            runCatching {
                val name = "${UUID.randomUUID()}.jpg"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file(name).outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                name
            }.getOrNull()
        }
    }

    suspend fun delete(names: List<String>) = withContext(Dispatchers.IO) {
        names.forEach { runCatching { file(it).delete() } }
    }
}
