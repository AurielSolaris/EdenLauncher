package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import app.auriel.edenlauncher.R
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * Called when a dot is tapped. Null leaves the indicator as pure decoration, which is what it
     * was: a row of dots that shows which page you are on but cannot take you to another is a
     * control that only works in one direction.
     */
    var onMarkerSelected: ((Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** The dot the press started on, or -1. Cleared when the finger wanders off. */
    private var pressedMarker = -1

    /** Handed from the touch handler to [performClick], or -1. */
    private var clickTarget = -1

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
            // A gap's worth of slop on each end. The dots stay centred and drawn identically; the
            // extra is only so the first and last are as easy to hit as the ones in the middle.
            (markerCount * 2 * dotRadius + (markerCount + 1) * dotGap).toInt() +
                paddingLeft + paddingRight
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

    /**
     * A tap on a dot goes to that page.
     *
     * Handled here rather than with a child view per dot, for the same reason the dots are drawn
     * rather than inflated: a row of tiny clickable views is a lot of hierarchy for a control that
     * is one arithmetic step. The dot is 6dp across, so the hit slot is the whole stride - dot plus
     * gap - and the view carries a gap of slop at each end so the outer two are no harder to hit.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (markerCount <= 1 || onMarkerSelected == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedMarker = markerAt(event.x)
                return pressedMarker >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                // A press that turns into a swipe belongs to whatever is scrolling, not to us.
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    pressedMarker = -1
                }
            }

            MotionEvent.ACTION_UP -> {
                val target = pressedMarker
                pressedMarker = -1
                if (target < 0) return false
                if (target != activeMarker) {
                    // Routed through performClick rather than acted on here, so an accessibility
                    // service invoking a click reaches the same code a finger does.
                    clickTarget = target
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> pressedMarker = -1
        }
        return pressedMarker >= 0
    }

    override fun performClick(): Boolean {
        super.performClick()
        val target = clickTarget
        clickTarget = -1
        if (target < 0) return false
        playSoundEffect(SoundEffectConstants.CLICK)
        onMarkerSelected?.invoke(target)
        return true
    }

    /** Which dot [x] falls on, or -1 when the press landed outside every slot. */
    private fun markerAt(x: Float): Int {
        val stride = 2 * dotRadius + dotGap
        val totalWidth = markerCount * 2 * dotRadius + (markerCount - 1) * dotGap
        val first = (width - totalWidth) / 2f + dotRadius

        val index = ((x - first) / stride).roundToInt()
        if (index < 0 || index >= markerCount) return -1
        return if (abs(x - (first + index * stride)) <= stride) index else -1
    }

    private companion object {
        const val DOT_RADIUS_DP = 3f
        const val DOT_GAP_DP = 8f
    }
}
