package app.auriel.edenlauncher.wallpaper.picker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import app.auriel.edenlauncher.R
import kotlin.math.max

/**
 * One wallpaper in the picker: a still image with its name across the bottom.
 *
 * Drawn directly rather than composed from an `ImageView` and a `TextView` inside a `CardView`.
 * The picker shows a dozen of these at once on a phone with two cores, and three views each plus a
 * layout pass to arrange them buys nothing that a rounded clip and a text baseline do not.
 */
class WallpaperTileView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wallpaper_tile_empty)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.wallpaper_tile_stroke)
        strokeWidth = context.resources.displayMetrics.density
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.wallpaper_tile_scrim)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text)
        textSize = 13f * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Scratch geometry. onDraw runs on every scroll frame of a grid of these, so none of it
    // allocates.
    private val bounds = RectF()
    private val sourceRect = Rect()
    private val destinationRect = Rect()
    private val clipPath = Path()
    private val cornerRadius = 12f * context.resources.displayMetrics.density

    var title: String = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    /** The still frame. Null while it is still being rendered, which draws as an empty tile. */
    var preview: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Used for third-party wallpapers, whose thumbnail arrives as a drawable from the system. */
    var previewDrawable: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())

        val saved = canvas.save()
        clipPath.rewind()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        canvas.drawRect(bounds, fillPaint)
        drawPreview(canvas)

        // The label sits on a scrim rather than directly on the image: a wallpaper can be any
        // colour, and white text on a pale frame is unreadable exactly when it matters.
        val labelHeight = textPaint.textSize * 2.2f
        canvas.drawRect(0f, height - labelHeight, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawText(
            ellipsised(title, width - cornerRadius * 2f),
            width / 2f,
            height - labelHeight / 2f + textPaint.textSize / 3f,
            textPaint,
        )

        canvas.restoreToCount(saved)

        val inset = strokePaint.strokeWidth / 2f
        bounds.inset(inset, inset)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, strokePaint)
    }

    /** Centre-crops the still, so a preview is never letterboxed or squashed. */
    private fun drawPreview(canvas: Canvas) {
        val bitmap = preview
        if (bitmap != null) {
            val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            destinationRect.set(
                ((width - scaledWidth) / 2f).toInt(),
                ((height - scaledHeight) / 2f).toInt(),
                ((width + scaledWidth) / 2f).toInt(),
                ((height + scaledHeight) / 2f).toInt(),
            )
            sourceRect.set(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, imagePaint)
            return
        }

        previewDrawable?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
        }
    }

    private fun ellipsised(text: String, maxWidth: Float): String =
        android.text.TextUtils.ellipsize(
            text,
            android.text.TextPaint(textPaint),
            maxWidth,
            android.text.TextUtils.TruncateAt.END,
        ).toString()

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // A pressed tile lifts slightly. Cheaper than a ripple and legible on a dark surface.
        alpha = if (isPressed) 0.7f else 1f
    }
}
