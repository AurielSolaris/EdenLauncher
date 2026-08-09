package app.auriel.edenlauncher.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.LruCache

/**
 * Bounded in-memory cache of rasterised app icons.
 *
 * Ported in spirit from `IconCache` (AOSP 7), minus its disk cache: on a 4 GB device the disk
 * cache mostly buys a slower cold start and a database to keep coherent. Icons are rasterised
 * once at the grid's icon size and held in an [LruCache] sized in bytes, so the cache cannot grow
 * with the app count.
 */
class IconCache(private val context: Context, private val iconSizePx: Int) {

    private data class Key(val component: ComponentName, val user: UserHandle)

    /**
     * Budget: enough for roughly two screens' worth of icons beyond a typical app list, capped so
     * the launcher never becomes the reason something else gets killed.
     */
    private val cache = object : LruCache<Key, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    /** Density bucket to request drawables at, matching the size we rasterise to. */
    private val iconDensity = context.resources.displayMetrics.densityDpi

    /**
     * Returns the icon for [activity], rasterising it on first use.
     *
     * Call from a background dispatcher: [LauncherActivityInfo.getIcon] hits the package manager
     * and decodes a drawable.
     */
    fun getIcon(activity: LauncherActivityInfo, user: UserHandle): Bitmap {
        val key = Key(activity.componentName, user)
        cache.get(key)?.let { return it }

        val bitmap = rasterise(activity.getIcon(iconDensity))
        cache.put(key, bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    /** Draws [drawable] into a square bitmap of the grid's icon size. */
    private fun rasterise(drawable: Drawable): Bitmap {
        // A bitmap-backed drawable already at the right size needs no redraw.
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap
            if (source != null && source.width == iconSizePx && source.height == iconSizePx) {
                return source
            }
        }

        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, iconSizePx, iconSizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private companion object {
        const val CACHE_BYTES = 6 * 1024 * 1024
    }
}
