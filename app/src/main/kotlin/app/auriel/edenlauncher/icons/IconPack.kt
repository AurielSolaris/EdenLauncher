package app.auriel.edenlauncher.icons

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import app.auriel.edenlauncher.util.EdenLog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * One installed icon pack, read on demand.
 *
 * Icon packs are a de-facto standard rather than a real one: there is no Android API for them, only
 * a convention that grew out of ADW and Go Launcher and that every launcher since has copied. A
 * pack is an ordinary APK whose assets contain `appfilter.xml`, mapping a component name to a
 * drawable inside the pack:
 *
 * ```
 * <item component="ComponentInfo{com.foo/com.foo.Main}" drawable="foo" />
 * ```
 *
 * Packs also ship a background, a mask, an overlay, and a scale factor, which together are how an
 * app the pack has never heard of still comes out looking like it belongs. That composition is the
 * difference between an icon pack that themes twenty apps and one that themes the whole drawer, so
 * it is implemented here rather than skipped - see [compose].
 *
 * The map is parsed once, lazily, and held for as long as the pack is selected. A large pack is a
 * few thousand short strings, which is worth it to keep icon lookup off the parser.
 */
class IconPack(context: Context, val packageName: String) {

    private val appContext = context.applicationContext

    private val resources: Resources? = runCatching {
        appContext.packageManager.getResourcesForApplication(packageName)
    }.onFailure { EdenLog.w(TAG, "Icon pack $packageName is not readable", it) }.getOrNull()

    /** Flattened component name to drawable name. */
    private val drawableForComponent = HashMap<String, String>(512)

    private var iconBack: String? = null
    private var iconMask: String? = null
    private var iconUpon: String? = null
    private var scaleFactor = 1f

    private var parsed = false

    /** True when the pack could be opened at all; a pack that was uninstalled will be false. */
    val isUsable: Boolean get() = resources != null

    @Synchronized
    private fun ensureParsed() {
        if (parsed) return
        parsed = true
        val res = resources ?: return
        runCatching { openAppFilter(res)?.use(::parseAppFilter) }
            .onFailure { EdenLog.w(TAG, "Could not read appfilter for $packageName", it) }
        EdenLog.i(
            TAG,
            "$packageName: ${drawableForComponent.size} entries, " +
                "back=$iconBack mask=$iconMask upon=$iconUpon scale=$scaleFactor",
        )
    }

    /**
     * Finds `appfilter.xml` wherever this pack decided to keep it.
     *
     * Assets is where the convention says it goes and where most packs put it, but plenty ship it
     * as `res/xml/appfilter` instead, and a few as `res/raw`. Trying all three is the difference
     * between supporting icon packs and supporting the icon packs that happened to be tested.
     */
    private fun openAppFilter(res: Resources): InputStream? {
        runCatching { return res.assets.open(APP_FILTER_ASSET) }

        for (type in arrayOf("xml", "raw")) {
            val id = res.getIdentifier(APP_FILTER_NAME, type, packageName)
            if (id != 0) {
                runCatching { return res.openRawResource(id) }
            }
        }
        return null
    }

    private fun parseAppFilter(stream: InputStream) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) {
                            // Entries are stored as ComponentInfo{pkg/cls}; keep only the inside,
                            // which is exactly what ComponentName.flattenToString produces.
                            drawableForComponent[unwrapComponent(component)] = drawable
                        }
                    }

                    // Only the first of each is used. Packs that ship several backgrounds pick one
                    // at random per icon, which makes the same app look different between the
                    // drawer and the home screen - not a trade worth making.
                    "iconback" -> if (iconBack == null) iconBack = firstImageAttribute(parser)
                    "iconmask" -> if (iconMask == null) iconMask = firstImageAttribute(parser)
                    "iconupon" -> if (iconUpon == null) iconUpon = firstImageAttribute(parser)
                    "scale" -> parser.getAttributeValue(null, "factor")
                        ?.toFloatOrNull()
                        ?.takeIf { it > 0f }
                        ?.let { scaleFactor = it }
                }
            }
            event = parser.next()
        }
    }

    private fun firstImageAttribute(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).startsWith("img")) return parser.getAttributeValue(i)
        }
        return null
    }

    /** The pack's own icon for [component], or null when the pack does not theme that app. */
    fun drawableFor(component: ComponentName): Drawable? {
        ensureParsed()
        val res = resources ?: return null
        val name = drawableForComponent[component.flattenToString()] ?: return null
        return loadDrawable(res, name)
    }

    private fun loadDrawable(res: Resources, name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", packageName)
        if (id == 0) return null
        return runCatching { res.getDrawableForDensity(id, densityDpi, null) }.getOrNull()
    }

    private val densityDpi = appContext.resources.displayMetrics.densityDpi

    /**
     * Every icon in the pack, in the order the author arranged them.
     *
     * Packs list these in `drawable.xml` so a launcher can offer "pick any icon from this pack" -
     * the answer to an app the author never made an icon for. Falling back to the `icon_pack`
     * string array and then to the appfilter's own drawables means a pack that omits the file is
     * still browsable, just unsorted.
     *
     * Parsed on demand and not retained: this is a few thousand short strings needed only while
     * the browser is open, and holding them for the life of the process to save one parse would be
     * the wrong trade on a 4 GB phone.
     */
    fun browsableDrawables(): List<String> {
        ensureParsed()
        val res = resources ?: return emptyList()

        val names = ArrayList<String>(1024)
        runCatching { openDrawableList(res)?.use { parseDrawableList(it, names) } }
            .onFailure { EdenLog.w(TAG, "Could not read the drawable list for $packageName", it) }

        if (names.isEmpty()) names.addAll(arrayResource(res))
        if (names.isEmpty()) names.addAll(drawableForComponent.values.distinct())

        // A pack can list the same icon under several categories.
        return names.distinct()
    }

    private fun openDrawableList(res: Resources): InputStream? {
        runCatching { return res.assets.open(DRAWABLE_LIST_ASSET) }

        for (type in arrayOf("xml", "raw")) {
            val id = res.getIdentifier(DRAWABLE_LIST_NAME, type, packageName)
            if (id != 0) runCatching { return res.openRawResource(id) }
        }
        return null
    }

    private fun parseDrawableList(stream: InputStream, into: MutableList<String>) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                parser.getAttributeValue(null, "drawable")?.let(into::add)
            }
            event = parser.next()
        }
    }

    /** Some packs skip `drawable.xml` and ship a plain string array instead. */
    private fun arrayResource(res: Resources): List<String> {
        val id = res.getIdentifier(DRAWABLE_ARRAY, "array", packageName)
        if (id == 0) return emptyList()
        return runCatching { res.getStringArray(id).toList() }.getOrDefault(emptyList())
    }

    /** One icon from the pack by name, rasterised to [size], for the browser and for overrides. */
    fun bitmapFor(name: String, size: Int): Bitmap? {
        val res = resources ?: return null
        if (size <= 0) return null
        val drawable = loadDrawable(res, name) ?: return null
        return runCatching { rasterise(drawable, size) }.getOrNull()
    }

    /** True when this pack can dress up apps it has no entry for. */
    fun canCompose(): Boolean {
        ensureParsed()
        return iconBack != null || iconMask != null || iconUpon != null
    }

    /**
     * Dresses an unthemed icon to match the pack.
     *
     * The recipe is the one every icon pack is authored against: draw the pack's background, shrink
     * the app's own icon by the pack's scale factor and draw it on top, punch it out with the mask,
     * then lay the overlay over the result. An app the pack author never saw comes out looking like
     * one they did.
     *
     * Returns null when the pack ships none of those, in which case the caller keeps the original.
     */
    fun compose(base: Drawable, size: Int): Bitmap? {
        ensureParsed()
        val res = resources ?: return null
        if (size <= 0) return null

        val back = iconBack?.let { loadDrawable(res, it) }
        val mask = iconMask?.let { loadDrawable(res, it) }
        val upon = iconUpon?.let { loadDrawable(res, it) }
        if (back == null && mask == null && upon == null) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = Rect(0, 0, size, size)

        back?.let {
            it.bounds = bounds
            it.draw(canvas)
        }

        // The app's icon, inset so the pack's background frames it rather than being hidden by it.
        val inset = ((size * (1f - scaleFactor)) / 2f).toInt().coerceAtLeast(0)
        base.setBounds(inset, inset, size - inset, size - inset)
        base.draw(canvas)

        if (mask != null) {
            val maskBitmap = rasterise(mask, size)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            canvas.drawBitmap(maskBitmap, 0f, 0f, paint)
            maskBitmap.recycle()
        }

        upon?.let {
            it.bounds = bounds
            it.draw(canvas)
        }
        return output
    }

    private fun rasterise(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable) {
            val source = drawable.bitmap
            if (source != null && source.width == size && source.height == size) return source
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object {
        const val TAG = "IconPack"
        const val APP_FILTER_NAME = "appfilter"
        const val APP_FILTER_ASSET = "appfilter.xml"
        const val DRAWABLE_LIST_NAME = "drawable"
        const val DRAWABLE_LIST_ASSET = "drawable.xml"
        const val DRAWABLE_ARRAY = "icon_pack"

        /**
         * `ComponentInfo{pkg/cls}` to `pkg/cls`. Left alone if it is already flattened, because a
         * handful of packs write it that way.
         */
        fun unwrapComponent(raw: String): String {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            return if (start >= 0 && end > start) raw.substring(start + 1, end) else raw
        }
    }
}
