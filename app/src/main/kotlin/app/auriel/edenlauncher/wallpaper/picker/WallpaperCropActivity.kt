package app.auriel.edenlauncher.wallpaper.picker

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.util.EdenLog
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Frames a picture and sets it as the static wallpaper.
 *
 * Handing a photo straight to `WallpaperManager.setStream` - which is what Eden did in 0.3.0 and
 * what most launchers still do - lets the system pick the crop, and on a tall screen it usually
 * picks badly. This screen puts that decision back where it belongs.
 *
 * Two resolutions are in play on purpose. What you drag around is a downsampled copy, small enough
 * that panning stays smooth on a two-core phone; what gets set is decoded again from the original
 * file, at the region you framed, so zooming in does not cost you the picture's detail.
 */
class WallpaperCropActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var cropView: CropView
    private lateinit var setButton: Button

    private var sourceFile: File? = null
    private var targetWidth = 0
    private var targetHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.wallpaper_crop_title)

        val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.wallpaper_crop_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceFile = file

        val size = screenSize()
        targetWidth = size.x
        targetHeight = size.y

        setContentView(buildContentView())
        loadPreview(file)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- layout ----------------------------------------------------------------------------------

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)

        cropView = CropView(this)

        val hint = TextView(this).apply {
            setText(R.string.wallpaper_crop_hint)
            setTextColor(getColor(R.color.settings_text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        setButton = Button(this).apply {
            setText(R.string.wallpaper_crop_set)
            isEnabled = false
            setOnClickListener { askDestination() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cancel = Button(this).apply {
            setText(R.string.cancel)
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, 0, padding, padding)
            addView(cancel)
            addView(setButton)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.settings_background))
            fitsSystemWindows = true
            addView(
                cropView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            addView(
                hint,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    // ---- preview ---------------------------------------------------------------------------------

    /**
     * Decodes a copy small enough to drag around.
     *
     * A modern phone camera produces images several times the size of the screen. Loading one at
     * full resolution to pan it would spend forty megabytes and drop frames doing it, and would
     * gain nothing: the preview only has to look right, and the crop is applied to the original.
     */
    private fun loadPreview(file: File) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeDownsampled(file) }
            if (bitmap == null) {
                Toast.makeText(
                    this@WallpaperCropActivity,
                    R.string.wallpaper_crop_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
                return@launch
            }
            cropView.setImage(bitmap, targetWidth.toFloat() / targetHeight)
            setButton.isEnabled = true
        }
    }

    private fun decodeDownsampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                PREVIEW_MAX_EDGE,
                PREVIEW_MAX_EDGE,
            )
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    // ---- applying --------------------------------------------------------------------------------

    /**
     * Asks where the wallpaper goes before setting it.
     *
     * The two screens are genuinely separate settings on Android and people use them differently -
     * a photo of someone on the lock screen, something quiet behind the icons. Silently doing both,
     * which is what `setStream` does by default, throws away a choice the platform offers.
     */
    private fun askDestination() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_crop_destination_title)
            .setItems(
                arrayOf(
                    getString(R.string.wallpaper_crop_both),
                    getString(R.string.wallpaper_crop_home),
                    getString(R.string.wallpaper_crop_lock),
                ),
            ) { _, which ->
                apply(
                    when (which) {
                        1 -> WallpaperManager.FLAG_SYSTEM
                        2 -> WallpaperManager.FLAG_LOCK
                        else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    },
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun apply(which: Int) {
        val file = sourceFile ?: return
        val crop = cropView.normalisedCrop() ?: return
        setButton.isEnabled = false

        scope.launch {
            val applied = withContext(Dispatchers.IO) { applyCrop(file, crop, which) }
            if (!applied) {
                setButton.isEnabled = true
                Toast.makeText(
                    this@WallpaperCropActivity,
                    R.string.wallpaper_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            Toast.makeText(this@WallpaperCropActivity, R.string.wallpaper_applied, Toast.LENGTH_SHORT)
                .show()
            goHome()
        }
    }

    /**
     * Decodes the framed region from the original and hands it to the wallpaper manager.
     *
     * Falls back to a whole-image decode when the file's format has no region decoder, which is
     * rare but real - some GIF and heavily progressive JPEG sources refuse.
     */
    private fun applyCrop(file: File, crop: RectF, which: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val region = Rect(
            (crop.left * bounds.outWidth).roundToInt(),
            (crop.top * bounds.outHeight).roundToInt(),
            (crop.right * bounds.outWidth).roundToInt(),
            (crop.bottom * bounds.outHeight).roundToInt(),
        )
        if (region.width() <= 0 || region.height() <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(region.width(), region.height(), targetWidth, targetHeight)
        }

        val cropped = decodeRegion(file, region, options)
            ?: cropFromWhole(file, region, options)
            ?: return false

        // Scaled to the screen exactly, so the system has no scaling decision left to make and
        // what was framed is what appears.
        val scaled = runCatching {
            Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        }.getOrNull() ?: return false

        val ok = runCatching {
            WallpaperManager.getInstance(applicationContext)
                .setBitmap(scaled, null, true, which)
            true
        }.onFailure { EdenLog.w(TAG, "Could not set the static wallpaper", it) }.getOrDefault(false)

        if (ok) saveThumbnail(scaled)

        if (scaled !== cropped) cropped.recycle()
        scaled.recycle()
        return ok
    }

    private fun decodeRegion(file: File, region: Rect, options: BitmapFactory.Options): Bitmap? =
        runCatching {
            // recycle() rather than use(): BitmapRegionDecoder only became AutoCloseable in
            // API 31, and this has to work on 29.
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false) ?: return null
            try {
                decoder.decodeRegion(region, options)
            } finally {
                decoder.recycle()
            }
        }.getOrNull()

    private fun cropFromWhole(file: File, region: Rect, options: BitmapFactory.Options): Bitmap? =
        runCatching {
            val whole = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val sample = options.inSampleSize.coerceAtLeast(1)
            val left = (region.left / sample).coerceIn(0, whole.width - 1)
            val top = (region.top / sample).coerceIn(0, whole.height - 1)
            val width = (region.width() / sample).coerceIn(1, whole.width - left)
            val height = (region.height() / sample).coerceIn(1, whole.height - top)
            Bitmap.createBitmap(whole, left, top, width, height).also {
                if (it !== whole) whole.recycle()
            }
        }.getOrNull()

    /**
     * Keeps a tile-sized copy of what was applied.
     *
     * The picker shows a still of every wallpaper it offers, and the static one should be no
     * exception. Reading the live wallpaper back out of the system needs a storage permission a
     * launcher has no business holding, so Eden remembers what it set instead.
     */
    private fun saveThumbnail(applied: Bitmap) {
        val thumbHeight = (THUMB_WIDTH.toFloat() / targetWidth * targetHeight).roundToInt()
        runCatching {
            val thumb = Bitmap.createScaledBitmap(applied, THUMB_WIDTH, thumbHeight, true)
            val file = File(filesDir, THUMB_FILE_NAME)
            FileOutputStream(file).use { thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            thumb.recycle()
            LauncherAppState.getInstance(this).preferences.stillWallpaperThumbPath = file.absolutePath
        }.onFailure { EdenLog.w(TAG, "Could not save the wallpaper thumbnail", it) }
    }

    /** Same reasoning as the live picker: what you see next should be what you just chose. */
    private fun goHome() {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        runCatching { startActivity(home) }
        finish()
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The real display, bars included - a wallpaper covers all of it. */
    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        val point = Point()
        windowManager.defaultDisplay.getRealSize(point)
        return point
    }

    companion object {
        private const val TAG = "WallpaperCrop"
        private const val EXTRA_SOURCE_PATH = "app.auriel.edenlauncher.CROP_SOURCE"

        /**
         * Longest edge of the on-screen copy. Comfortably more than any phone screen, so the
         * preview is never visibly soft, and far less than a camera original.
         */
        private const val PREVIEW_MAX_EDGE = 2048

        private const val THUMB_WIDTH = 320
        private const val THUMB_QUALITY = 88
        private const val THUMB_FILE_NAME = "wallpaper_still_thumb.jpg"

        /**
         * Largest power-of-two downsample that still leaves at least the requested size. Halving
         * is what the decoder is fast at; anything else is a full decode plus a resize.
         */
        private fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
            var sample = 1
            while (
                width / (sample * 2) >= maxWidth &&
                height / (sample * 2) >= maxHeight
            ) {
                sample *= 2
            }
            return max(1, sample)
        }

        /** Builds the intent that opens this screen for [source]. */
        fun intentFor(context: android.content.Context, source: File) =
            android.content.Intent(context, WallpaperCropActivity::class.java)
                .putExtra(EXTRA_SOURCE_PATH, source.absolutePath)
    }
}
