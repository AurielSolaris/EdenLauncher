package app.auriel.edenlauncher.icons

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.auriel.edenlauncher.util.EdenLog
import java.io.File
import java.io.FileOutputStream

/**
 * Per-app icons the user chose by hand.
 *
 * Stored as PNG files under `filesDir/icons`, one per component, rather than in preferences: an
 * icon is tens of kilobytes and preferences are read whole into memory at startup, so a dozen
 * custom icons in there would be a dozen base64 blobs charged against every cold start.
 *
 * A custom icon beats an icon pack, which beats the app's own. That order is the only one that
 * makes sense: choosing an icon for one app is a more specific instruction than choosing a pack
 * for all of them, and it should not quietly stop working when the pack changes.
 */
object CustomIcons {

    private const val TAG = "CustomIcons"
    private const val DIRECTORY = "icons"
    private const val QUALITY = 100

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY)

    /**
     * File name for a component.
     *
     * Component names contain dots and slashes; the slash is the only one a file name cannot hold,
     * so it becomes a marker that cannot appear in a package or class name.
     */
    private fun fileFor(context: Context, component: ComponentName): File =
        File(directory(context), component.flattenToString().replace('/', '@') + ".png")

    /** True when anything at all has been overridden, so the common path costs one directory stat. */
    fun hasAny(context: Context): Boolean =
        directory(context).takeIf { it.isDirectory }?.list()?.isNotEmpty() == true

    fun iconFor(context: Context, component: ComponentName): Bitmap? {
        val file = fileFor(context, component)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Reads [source], squares it to [sizePx], and stores it for [component].
     *
     * The image is centre-cropped to a square before scaling rather than squashed: a photo handed
     * in at 16:9 should lose its edges, not its proportions. Icons are square because the grid is,
     * and an icon that is subtly the wrong shape is worse than one that is cropped.
     */
    fun set(context: Context, component: ComponentName, source: Uri, sizePx: Int): Boolean {
        if (sizePx <= 0) return false

        return runCatching {
            val decoded = context.contentResolver.openInputStream(source)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return false

            val squared = squareCrop(decoded, sizePx)
            directory(context).mkdirs()
            FileOutputStream(fileFor(context, component)).use {
                squared.compress(Bitmap.CompressFormat.PNG, QUALITY, it)
            }
            if (squared !== decoded) decoded.recycle()
            squared.recycle()
            true
        }.onFailure { EdenLog.w(TAG, "Could not store a custom icon for $component", it) }
            .getOrDefault(false)
    }

    /**
     * Stores an icon taken from an icon pack.
     *
     * The same store as a hand-picked PNG on purpose: once chosen, "this app uses that icon" is one
     * fact, and it should survive the pack being switched or removed rather than silently reverting
     * to something else.
     */
    fun setFromBitmap(context: Context, component: ComponentName, icon: Bitmap): Boolean =
        runCatching {
            directory(context).mkdirs()
            FileOutputStream(fileFor(context, component)).use {
                icon.compress(Bitmap.CompressFormat.PNG, QUALITY, it)
            }
            true
        }.onFailure { EdenLog.w(TAG, "Could not store a pack icon for $component", it) }
            .getOrDefault(false)

    private fun squareCrop(source: Bitmap, sizePx: Int): Bitmap {
        val edge = minOf(source.width, source.height)
        if (edge <= 0) return source

        val cropped = if (source.width == source.height) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                (source.width - edge) / 2,
                (source.height - edge) / 2,
                edge,
                edge,
            )
        }

        val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /** Removes the override, putting the app's own icon (or the pack's) back. */
    fun clear(context: Context, component: ComponentName) {
        runCatching { fileFor(context, component).delete() }
    }

    fun has(context: Context, component: ComponentName): Boolean =
        fileFor(context, component).exists()
}
