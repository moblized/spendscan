package com.moblized.spendscan.scan

import android.content.Context
import android.net.Uri
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.moblized.spendscan.parse.OcrLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

/**
 * Reads the text on scanned receipt pages, on the phone, keeping where each line sits. The reader comes from
 * Google Play services, which downloads it the first time it's needed.
 */
object ReceiptOcr {
    suspend fun read(context: Context, pages: List<File>): List<OcrLine> = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val out = ArrayList<OcrLine>()
            var offset = 0
            for (file in pages) {
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                val text = recognize(recognizer, image)
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        // Corners run clockwise from the top left, and follow the text even when it's tilted.
                        val corners = line.cornerPoints
                        var slope = 0.0
                        var textHeight = 0
                        if (corners != null && corners.size == 4) {
                            val across = (corners[1].x - corners[0].x).toDouble()
                            if (across > 0) slope = (corners[1].y - corners[0].y) / across
                            textHeight = hypot((corners[3].x - corners[0].x).toDouble(), (corners[3].y - corners[0].y).toDouble()).toInt()
                        }
                        out += OcrLine(line.text, box.left, box.top + offset, box.right, box.bottom + offset, slope, textHeight)
                    }
                }
                // Stack the pages of a long receipt one under the other.
                offset += image.height + 200
            }
            out
        } finally {
            recognizer.close()
        }
    }

    /** Waits (up to about a minute) for the reader to finish downloading on first use. */
    private suspend fun recognize(recognizer: TextRecognizer, image: InputImage): Text {
        var attempt = 0
        while (true) {
            try {
                return recognizer.process(image).await()
            } catch (e: MlKitException) {
                if (e.errorCode != MlKitException.UNAVAILABLE || attempt >= 30) throw e
                attempt++
                delay(2000)
            }
        }
    }
}
