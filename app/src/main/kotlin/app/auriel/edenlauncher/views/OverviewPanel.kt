package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.auriel.edenlauncher.R

/**
 * The bar along the bottom of overview: add page, wallpaper, widgets, settings.
 *
 * Ported from AOSP 7's `overview_panel.xml`, which was a row of icon-over-label buttons shown
 * while the workspace was zoomed out. Same idea, same place; the icons are drawn here rather than
 * shipped as four PNG densities, and "Add page" is added because Eden lets pages exist without
 * anything on them.
 */
class OverviewPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onAddPageClick: (() -> Unit)? = null
    var onWallpaperClick: (() -> Unit)? = null
    var onWidgetsClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null

    var isOpen: Boolean = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        visibility = GONE
        setBackgroundColor(context.getColor(R.color.overview_panel_background))

        val padding = resources.getDimensionPixelSize(R.dimen.overview_panel_padding)
        setPadding(padding, padding, padding, padding)

        addView(button(Glyph.ADD, R.string.overview_add_page) { onAddPageClick?.invoke() })
        addView(button(Glyph.WALLPAPER, R.string.overview_wallpaper) { onWallpaperClick?.invoke() })
        addView(button(Glyph.WIDGETS, R.string.overview_widgets) { onWidgetsClick?.invoke() })
        addView(button(Glyph.SETTINGS, R.string.overview_settings) { onSettingsClick?.invoke() })
    }

    private fun button(glyph: Glyph, labelRes: Int, onClick: () -> Unit): View {
        val label = TextView(context).apply {
            setText(labelRes)
            setTextColor(context.getColor(R.color.settings_text))
            textSize = 12f
            gravity = Gravity.CENTER
            isAllCaps = true
        }

        val icon = GlyphView(context, glyph)

        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val iconSize = resources.getDimensionPixelSize(R.dimen.overview_icon_size)
            addView(icon, LayoutParams(iconSize, iconSize))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        alpha = 0f
        translationY = height.toFloat()
        animate().alpha(1f).translationY(0f).setDuration(DURATION_MS).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        animate()
            .alpha(0f)
            .translationY(height.toFloat())
            .setDuration(DURATION_MS)
            .withEndAction { visibility = GONE }
            .start()
    }

    private enum class Glyph { ADD, WALLPAPER, WIDGETS, SETTINGS }

    /**
     * Draws one panel icon.
     *
     * Vector drawables would need four more resource files for four shapes that are each a handful
     * of lines; drawing them keeps the resource tree honest and the APK smaller.
     */
    private class GlyphView(context: Context, private val glyph: Glyph) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = context.getColor(R.color.eden_leaf)
        }
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val unit = minOf(w, h)
            paint.strokeWidth = unit * 0.08f
            val inset = unit * 0.22f
            rect.set(inset, inset, w - inset, h - inset)

            when (glyph) {
                Glyph.ADD -> {
                    canvas.drawLine(w / 2, rect.top, w / 2, rect.bottom, paint)
                    canvas.drawLine(rect.left, h / 2, rect.right, h / 2, paint)
                }

                Glyph.WALLPAPER -> {
                    canvas.drawRoundRect(rect, unit * 0.08f, unit * 0.08f, paint)
                    path.reset()
                    path.moveTo(rect.left, rect.bottom - rect.height() * 0.25f)
                    path.lineTo(rect.left + rect.width() * 0.35f, rect.centerY())
                    path.lineTo(rect.right, rect.bottom)
                    canvas.drawPath(path, paint)
                }

                Glyph.WIDGETS -> {
                    val half = rect.width() / 2 - unit * 0.05f
                    canvas.drawRect(rect.left, rect.top, rect.left + half, rect.top + half, paint)
                    canvas.drawRect(rect.right - half, rect.top, rect.right, rect.top + half, paint)
                    canvas.drawRect(rect.left, rect.bottom - half, rect.left + half, rect.bottom, paint)
                    canvas.drawRect(
                        rect.right - half,
                        rect.bottom - half,
                        rect.right,
                        rect.bottom,
                        paint,
                    )
                }

                Glyph.SETTINGS -> {
                    canvas.drawCircle(w / 2, h / 2, rect.width() * 0.22f, paint)
                    canvas.drawCircle(w / 2, h / 2, rect.width() * 0.5f, paint)
                }
            }
        }
    }

    private companion object {
        const val DURATION_MS = 180L
    }
}
