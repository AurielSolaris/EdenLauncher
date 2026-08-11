package app.auriel.edenlauncher.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

private const val TAG = "Bitmaps"

/**
 * PNG-encodes an icon for storage. Icons are small (<= ~200px), so the buffer is sized to the
 * exact byte count instead of letting `ByteArrayOutputStream` double its way there.
 */
fun Bitmap.flatten(): ByteArray? {
    val out = ByteArrayOutputStream(width * height * 4)
    return try {
        if (compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    } catch (e: OutOfMemoryError) {
        EdenLog.w(TAG, "Could not flatten ${width}x$height icon", e)
        null
    }
}

/** Decodes an icon written by [flatten]. Returns null for corrupt or truncated blobs. */
fun ByteArray.toIconBitmap(): Bitmap? = try {
    BitmapFactory.decodeByteArray(this, 0, size)
} catch (e: Exception) {
    EdenLog.w(TAG, "Could not decode stored icon", e)
    null
}
