package app.auriel.edenlauncher.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.views.CellLayout
import app.auriel.edenlauncher.views.DragLayer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The handles that appear around a widget after a long press, for growing and shrinking it.
 *
 * Ported in spirit from `AppWidgetResizeFrame` (AOSP 7). It lives in the drag layer rather than in
 * the page, so it can be drawn outside the widget's own bounds without being clipped by the cell.
 *
 * Resizing is snapped to whole cells, because a widget that can sit between cells is a widget that
 * can overlap an icon. Every candidate size is checked against the page's occupancy before it is
 * applied, so a drag that would swallow a neighbouring icon simply stops rather than displacing it
 * - the alternative, shuffling other items out of the way mid-resize, is how a user loses an
 * arrangement they spent time on.
 */
class WidgetResizeFrame(context: Context) : View(context) {

    /** Told the final size when the gesture ends, so the caller can persist it. */
    fun interface OnResized {
        fun onResized(cellX: Int, cellY: Int, spanX: Int, spanY: Int)
    }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.widget_resize_border)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.widget_resize_handle)
    }

    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.eden_surface)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val density = resources.displayMetrics.density
    private val outset = (HANDLE_RADIUS_DP + HANDLE_TOUCH_PAD_DP) * density
    private val handleRadius = HANDLE_RADIUS_DP * density
    private val cornerRadius = CORNER_RADIUS_DP * density

    // Scratch, reused every frame and every touch. Nothing here allocates once attached.
    private val frameRect = RectF()
    private val widgetRect = Rect()

    private var widget: View? = null
    private var page: CellLayout? = null
    private var onResized: OnResized? = null

    private var minSpanX = 1
    private var minSpanY = 1
    private var maxSpanX = 1
    private var maxSpanY = 1

    /** Cell geometry as it stood when the gesture started, so a drag is measured from the start. */
    private var startCellX = 0
    private var startCellY = 0
    private var startSpanX = 1
    private var startSpanY = 1

    private var activeEdge = EDGE_NONE
    private var downX = 0f
    private var downY = 0f

    /**
     * Puts the frame around [widget] and shows it.
     *
     * The widget's own cells are released from the page's occupancy for the duration: every
     * candidate size overlaps where the widget already is, and testing against an occupancy that
     * still contains it would reject every one of them.
     */
    fun attach(
        dragLayer: DragLayer,
        widget: View,
        page: CellLayout,
        minSpan: IntArray,
        maxSpan: IntArray,
        onResized: OnResized,
    ) {
        detach()

        this.widget = widget
        this.page = page
        this.onResized = onResized
        minSpanX = minSpan[0].coerceAtLeast(1)
        minSpanY = minSpan[1].coerceAtLeast(1)
        maxSpanX = maxSpan[0].coerceAtLeast(minSpanX)
        maxSpanY = maxSpan[1].coerceAtLeast(minSpanY)

        val lp = widget.layoutParams as CellLayout.LayoutParams
        page.occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, false)

        dragLayer.addView(this, FrameLayout.LayoutParams(0, 0))
        reposition(dragLayer)
    }

    /** Hides the frame and puts the widget's cells back into the page's occupancy. */
    fun detach() {
        val widget = this.widget
        val page = this.page
        if (widget != null && page != null) {
            val lp = widget.layoutParams as CellLayout.LayoutParams
            page.occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, true)
        }
        this.widget = null
        this.page = null
        onResized = null
        activeEdge = EDGE_NONE
        (parent as? ViewGroup)?.removeView(this)
    }

    val isAttached: Boolean get() = widget != null

    private fun reposition(dragLayer: DragLayer) {
        val widget = this.widget ?: return
        dragLayer.getDescendantRectRelativeToSelf(widget, widgetRect)

        val pad = outset.roundToInt()
        val lp = layoutParams as FrameLayout.LayoutParams
        lp.width = widgetRect.width() + pad * 2
        lp.height = widgetRect.height() + pad * 2
        lp.leftMargin = widgetRect.left - pad
        lp.topMargin = widgetRect.top - pad
        layoutParams = lp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = outset
        frameRect.set(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, border)

        drawHandle(canvas, frameRect.left, frameRect.centerY())
        drawHandle(canvas, frameRect.right, frameRect.centerY())
        drawHandle(canvas, frameRect.centerX(), frameRect.top)
        drawHandle(canvas, frameRect.centerX(), frameRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handleFill)
        canvas.drawCircle(x, y, handleRadius, handleStroke)
    }

    // Handles are dragged, never tapped; there is no click here for a service to perform.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val widget = this.widget ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeEdge = edgeAt(event.x, event.y)
                if (activeEdge == EDGE_NONE) return false
                downX = event.x
                downY = event.y
                val lp = widget.layoutParams as CellLayout.LayoutParams
                startCellX = lp.cellX
                startCellY = lp.cellY
                startSpanX = lp.cellHSpan
                startSpanY = lp.cellVSpan
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeEdge == EDGE_NONE) return false
                applyDrag(event.x - downX, event.y - downY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeEdge == EDGE_NONE) return false
                activeEdge = EDGE_NONE
                val lp = widget.layoutParams as CellLayout.LayoutParams
                onResized?.onResized(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan)
                return true
            }
        }
        return false
    }

    private fun edgeAt(x: Float, y: Float): Int {
        val pad = outset
        val left = pad
        val right = width - pad
        val top = pad
        val bottom = height - pad
        val reach = handleRadius + HANDLE_TOUCH_PAD_DP * density

        if (abs(x - left) <= reach && abs(y - (top + bottom) / 2f) <= reach) return EDGE_LEFT
        if (abs(x - right) <= reach && abs(y - (top + bottom) / 2f) <= reach) return EDGE_RIGHT
        if (abs(y - top) <= reach && abs(x - (left + right) / 2f) <= reach) return EDGE_TOP
        if (abs(y - bottom) <= reach && abs(x - (left + right) / 2f) <= reach) return EDGE_BOTTOM
        return EDGE_NONE
    }

    /**
     * Turns a finger offset into whole cells and applies it if the result is legal.
     *
     * Rounding rather than truncating means a handle dragged most of the way into the next cell
     * commits to it, which is what the gesture looks like it is doing.
     */
    private fun applyDrag(dx: Float, dy: Float) {
        val widget = this.widget ?: return
        val page = this.page ?: return
        if (page.cellWidth <= 0 || page.cellHeight <= 0) return

        var cellX = startCellX
        var cellY = startCellY
        var spanX = startSpanX
        var spanY = startSpanY

        when (activeEdge) {
            EDGE_LEFT -> {
                val steps = (dx / page.cellWidth).roundToInt()
                cellX = (startCellX + steps).coerceIn(0, startCellX + startSpanX - minSpanX)
                spanX = startCellX + startSpanX - cellX
            }

            EDGE_RIGHT -> {
                val steps = (dx / page.cellWidth).roundToInt()
                spanX = (startSpanX + steps).coerceIn(minSpanX, maxSpanX)
                if (cellX + spanX > page.countX) spanX = page.countX - cellX
            }

            EDGE_TOP -> {
                val steps = (dy / page.cellHeight).roundToInt()
                cellY = (startCellY + steps).coerceIn(0, startCellY + startSpanY - minSpanY)
                spanY = startCellY + startSpanY - cellY
            }

            EDGE_BOTTOM -> {
                val steps = (dy / page.cellHeight).roundToInt()
                spanY = (startSpanY + steps).coerceIn(minSpanY, maxSpanY)
                if (cellY + spanY > page.countY) spanY = page.countY - cellY
            }
        }

        spanX = spanX.coerceIn(minSpanX, maxSpanX)
        spanY = spanY.coerceIn(minSpanY, maxSpanY)
        if (!page.occupancy.isRegionVacant(cellX, cellY, spanX, spanY)) return

        val lp = widget.layoutParams as CellLayout.LayoutParams
        if (lp.cellX == cellX && lp.cellY == cellY && lp.cellHSpan == spanX && lp.cellVSpan == spanY) {
            return
        }

        lp.cellX = cellX
        lp.cellY = cellY
        lp.cellHSpan = spanX
        lp.cellVSpan = spanY
        widget.requestLayout()

        // The frame has to follow the widget, and the widget has not been laid out yet.
        widget.post { (parent as? DragLayer)?.let(::reposition) }
    }

    private companion object {
        const val EDGE_NONE = 0
        const val EDGE_LEFT = 1
        const val EDGE_RIGHT = 2
        const val EDGE_TOP = 3
        const val EDGE_BOTTOM = 4

        const val HANDLE_RADIUS_DP = 9f
        const val HANDLE_TOUCH_PAD_DP = 10f
        const val CORNER_RADIUS_DP = 12f
    }
}
