package com.moblized.spendscan.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.moblized.spendscan.parse.DigitRow
import com.moblized.spendscan.parse.Gray
import com.moblized.spendscan.parse.SegmentReader
import java.io.File
import kotlin.math.max

/** Reads the bar digits of a pump's display out of a photo, on the phone. */
object PumpDigits {
    // Plenty for digits, and small enough to be quick.
    private const val LONGEST_SIDE = 1800

    fun read(photo: File): List<DigitRow> = gray(photo)?.let(SegmentReader::read).orEmpty()

    private fun gray(photo: File): Gray? {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, size)
        if (size.outWidth <= 0 || size.outHeight <= 0) return null
        var sample = 1
        while (max(size.outWidth, size.outHeight) / (sample * 2) >= LONGEST_SIDE) sample *= 2
        var bitmap = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // The camera saves photos lying on their side, with a note of which way is up.
        val turn = runCatching { ExifInterface(photo).rotationDegrees }.getOrDefault(0)
        if (turn != 0) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // The strongest of red, green and blue, so that digits lit in any one colour count as bright.
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
        }
        return Gray(w, h, pixels)
    }
}
