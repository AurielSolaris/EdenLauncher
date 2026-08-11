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
import app.auriel.edenlauncher.icons.CustomIcons
import app.auriel.edenlauncher.icons.IconPack
import app.auriel.edenlauncher.settings.LauncherPrefs

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
     * The icon pack in force, or null for the apps' own icons.
     *
     * Held here rather than looked up per icon because resolving a pack means opening another
     * app's resources, and doing that a few hundred times during a load is exactly the kind of
     * cost a launcher cannot afford on a slow phone.
     */
    @Volatile
    private var iconPack: IconPack? = null

    /** Cached so the common case - nobody has set a custom icon - costs one directory stat a load. */
    @Volatile
    private var hasCustomIcons = false

    /**
     * Re-reads which pack and which overrides are in force, clearing anything cached under the old
     * answer. Called when the pack setting changes and at the start of every load.
     */
    fun refreshIconSources() {
        val wanted = LauncherPrefs(context).iconPackPackage
        val current = iconPack
        if (wanted != current?.packageName) {
            iconPack = wanted?.let { IconPack(context, it) }?.takeIf { it.isUsable }
            cache.evictAll()
            EdenLog.i(TAG, "icon pack is now ${iconPack?.packageName ?: "none"} (asked for $wanted)")
        }
        hasCustomIcons = CustomIcons.hasAny(context)
    }

    /**
     * Returns the icon for [activity], rasterising it on first use.
     *
     * Call from a background dispatcher: [LauncherActivityInfo.getIcon] hits the package manager
     * and decodes a drawable, and an icon pack adds another app's resources on top of that.
     */
    fun getIcon(activity: LauncherActivityInfo, user: UserHandle): Bitmap {
        val key = Key(activity.componentName, user)
        cache.get(key)?.let { return it }

        val bitmap = resolve(activity)
        cache.put(key, bitmap)
        return bitmap
    }

    /**
     * Most specific instruction wins: an icon the user picked for this one app, then the pack they
     * chose for all of them, then what the app ships.
     */
    private fun resolve(activity: LauncherActivityInfo): Bitmap {
        val component = activity.componentName

        if (hasCustomIcons) {
            CustomIcons.iconFor(context, component)?.let { custom ->
                return if (custom.width == iconSizePx && custom.height == iconSizePx) {
                    custom
                } else {
                    Bitmap.createScaledBitmap(custom, iconSizePx, iconSizePx, true)
                        .also { if (it !== custom) custom.recycle() }
                }
            }
        }

        val pack = iconPack
        if (pack != null) {
            pack.drawableFor(component)?.let { return rasterise(it) }
            // An app the pack has no entry for still gets its background, mask, and overlay, which
            // is what stops a themed home screen looking half finished.
            if (pack.canCompose()) {
                pack.compose(activity.getIcon(iconDensity), iconSizePx)?.let { return it }
            }
        }

        return rasterise(activity.getIcon(iconDensity))
    }

    fun clear() = cache.evictAll()

    /**
     * Drops every cached icon belonging to [packageName].
     *
     * An app that updates usually keeps its component names, so without this its old icon would
     * survive the update for as long as the launcher process does. Called from the package-change
     * watcher, not from any hot path, which is why snapshotting the keys is affordable.
     */
    fun removePackage(packageName: String) {
        for (key in cache.snapshot().keys) {
            if (key.component.packageName == packageName) cache.remove(key)
        }
    }

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
        const val TAG = "IconCache"
        const val CACHE_BYTES = 6 * 1024 * 1024
    }
}
