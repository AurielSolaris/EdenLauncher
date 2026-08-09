package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import app.auriel.edenlauncher.R

/**
 * The row of dots under the workspace.
 *
 * AOSP 7 built this from one `ImageView` marker per page; a handful of views that only ever draw a
 * circle is pure overhead on a low-end device, so this draws them directly. One paint, one pass,
 * no child views, no allocation in [onDraw].
 */
class PageIndicatorDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), PagedView.PageIndicator {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dotRadius = DOT_RADIUS_DP * resources.displayMetrics.density
    private val dotGap = DOT_GAP_DP * resources.displayMetrics.density

    private var markerCount = 0
    private var activeMarker = 0

    /** Index of the page HOME returns to, drawn with a ring. -1 when none is set. */
    private var homeMarker = -1

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val activeColor = context.getColor(R.color.eden_leaf)
    private val inactiveColor = Color.argb(110, 199, 233, 210)

    override fun setMarkerCount(count: Int) {
        if (markerCount == count) return
        markerCount = count
        // A single page needs no indicator at all; hiding it also skips its draw and layout.
        visibility = if (count > 1) VISIBLE else GONE
        requestLayout()
    }

    override fun setActiveMarker(index: Int) {
        if (activeMarker == index) return
        activeMarker = index
        invalidate()
    }

    /** Marks which page is the home page, so the choice is visible rather than remembered. */
    fun setHomeMarker(index: Int) {
        if (homeMarker == index) return
        homeMarker = index
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (markerCount == 0) {
            0
        } else {
            (markerCount * 2 * dotRadius + (markerCount - 1) * dotGap).toInt() + paddingLeft + paddingRight
        }
        // Tall enough for the home-page ring, which is wider than a plain dot.
        val height = (4 * dotRadius).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (markerCount <= 1) return

        val totalWidth = markerCount * 2 * dotRadius + (markerCount - 1) * dotGap
        var x = (width - totalWidth) / 2f + dotRadius
        val y = height / 2f

        for (i in 0 until markerCount) {
            paint.color = if (i == activeMarker) activeColor else inactiveColor
            canvas.drawCircle(x, y, dotRadius, paint)
            if (i == homeMarker) {
                ringPaint.color = activeColor
                ringPaint.strokeWidth = dotRadius * 0.5f
                canvas.drawCircle(x, y, dotRadius * 1.9f, ringPaint)
            }
            x += 2 * dotRadius + dotGap
        }
    }

    private companion object {
        const val DOT_RADIUS_DP = 3f
        const val DOT_GAP_DP = 8f
    }
}
