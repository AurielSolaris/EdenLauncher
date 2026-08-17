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
 *
 * Icons resolve in two passes, which is the whole reason there are two caches.
 *
 * [getIcon] is the fast pass and is what the loader calls: it asks the package manager for the
 * app's own icon and rasterises it, and nothing else. [themedIcon] is the slow pass, run after the
 * home screen is already on screen: it opens the icon pack's resources, looks the app up in a map
 * parsed out of the pack's `appfilter.xml`, and composes a background, mask and overlay for every
 * app the pack has no entry for. That second pass is tens of milliseconds *per app* on a slow
 * phone, and doing it inline is what used to make a launcher with a pack selected sit on a blank
 * screen for several seconds. Split like this the grid appears at the speed of the unthemed
 * launcher and the pack lands on top of it, icon by icon.
 */
class IconCache(private val context: Context, private val iconSizePx: Int) {

    private data class Key(val component: ComponentName, val user: UserHandle)

    /**
     * The apps' own icons, as the package manager hands them over.
     *
     * Budget: enough for roughly two screens' worth of icons beyond a typical app list, capped so
     * the launcher never becomes the reason something else gets killed. When a pack is in force an
     * entry here is only wanted for as long as it takes to compose the themed icon on top of it,
     * and [themedIcon] drops it afterwards rather than paying for both copies.
     */
    private val baseCache = object : LruCache<Key, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    /** Icons the pack or the user replaced. Stays empty when neither is in play. */
    private val themedCache = object : LruCache<Key, Bitmap>(CACHE_BYTES) {
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
     * True when anything at all would change an app's own icon.
     *
     * The launcher checks this before starting the second pass, so a user with no pack and no
     * custom icons - the overwhelmingly common case - pays nothing for the machinery at all.
     */
    val hasThemedSource: Boolean
        get() = iconPack != null || hasCustomIcons

    /**
     * Re-reads which pack and which overrides are in force, clearing anything cached under the old
     * answer. Called when the pack setting changes and at the start of every load.
     *
     * Only the themed icons are dropped: an app's own icon does not change because the user picked
     * a different pack, and re-decoding a few hundred of them to learn that again is pure waste.
     */
    fun refreshIconSources() {
        val prefs = LauncherPrefs(context)

        // Simple mode is v0.2.0, which had neither icon packs nor per-app icons. The stored pack
        // and the PNG files stay exactly where they are and come back the moment it is turned off;
        // they are simply not consulted while it is on.
        val simple = prefs.simpleMode
        val wanted = if (simple) null else prefs.iconPackPackage
        val current = iconPack
        if (wanted != current?.packageName) {
            iconPack = wanted?.let { IconPack(context, it) }?.takeIf { it.isUsable }
            themedCache.evictAll()
            EdenLog.i(TAG, "icon pack is now ${iconPack?.packageName ?: "none"} (asked for $wanted)")
        }

        val custom = !simple && CustomIcons.hasAny(context)
        if (custom != hasCustomIcons) themedCache.evictAll()
        hasCustomIcons = custom
    }

    /**
     * Returns the icon to show for [activity] right now, rasterising it on first use.
     *
     * This is the fast pass: it returns the themed icon when one has already been worked out, and
     * otherwise the app's own. It never opens a pack, so it is safe to call for every app on the
     * phone on the critical path of a load.
     *
     * Call from a background dispatcher: [LauncherActivityInfo.getIcon] still hits the package
     * manager and decodes a drawable.
     */
    fun getIcon(activity: LauncherActivityInfo, user: UserHandle): Bitmap {
        val key = Key(activity.componentName, user)
        themedCache.get(key)?.let { return it }
        return baseIcon(key, activity)
    }

    private fun baseIcon(key: Key, activity: LauncherActivityInfo): Bitmap {
        baseCache.get(key)?.let { return it }
        val bitmap = rasterise(activity.getIcon(iconDensity))
        baseCache.put(key, bitmap)
        return bitmap
    }

    /**
     * The slow pass: what the user's pack and overrides make of [activity].
     *
     * Most specific instruction wins - an icon the user picked for this one app, then the pack they
     * chose for all of them. Returns null when neither has anything to say, which is the caller's
     * cue to leave the icon already on screen alone rather than redraw it with the same bitmap.
     *
     * Call from a background dispatcher, and expect it to be slow: the first call for a pack parses
     * its `appfilter.xml`, and every call after that may compose four layers.
     */
    fun themedIcon(activity: LauncherActivityInfo, user: UserHandle): Bitmap? {
        if (!hasThemedSource) return null

        val key = Key(activity.componentName, user)
        themedCache.get(key)?.let { return it }

        val themed = resolveThemed(key, activity) ?: return null
        themedCache.put(key, themed)
        // The app's own icon was only wanted as the layer underneath. Holding both would double
        // what a themed home screen costs in memory for no one's benefit.
        baseCache.remove(key)
        return themed
    }

    private fun resolveThemed(key: Key, activity: LauncherActivityInfo): Bitmap? {
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

        val pack = iconPack ?: return null
        pack.drawableFor(component)?.let { return rasterise(it) }

        // An app the pack has no entry for still gets its background, mask, and overlay, which is
        // what stops a themed home screen looking half finished. The app's own icon is taken from
        // the base cache rather than asked of the package manager a second time - it was decoded
        // moments ago by the first pass, at exactly the size the composition wants.
        if (pack.canCompose()) {
            val base = baseIcon(key, activity)
            return pack.compose(BitmapDrawable(context.resources, base), iconSizePx)
        }
        return null
    }

    fun clear() {
        baseCache.evictAll()
        themedCache.evictAll()
    }

    /**
     * Drops every cached icon belonging to [packageName].
     *
     * An app that updates usually keeps its component names, so without this its old icon would
     * survive the update for as long as the launcher process does. Called from the package-change
     * watcher, not from any hot path, which is why snapshotting the keys is affordable.
     */
    fun removePackage(packageName: String) {
        for (key in baseCache.snapshot().keys) {
            if (key.component.packageName == packageName) baseCache.remove(key)
        }
        for (key in themedCache.snapshot().keys) {
            if (key.component.packageName == packageName) themedCache.remove(key)
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
