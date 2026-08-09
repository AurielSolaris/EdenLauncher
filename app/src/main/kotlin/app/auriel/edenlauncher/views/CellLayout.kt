package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import kotlin.math.max

/**
 * One workspace page: a fixed grid that positions children by cell rather than by pixel.
 *
 * Ported from `CellLayout` (AOSP 7), reduced to placement and occupancy. Drag reordering, the
 * background scrim, and the folder-creation hit testing arrive with the drag controller in Phase 2.
 */
open class CellLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    var countX: Int = 0
        private set

    var countY: Int = 0
        private set

    /** Size of a single cell. Recomputed on measure from the space actually available. */
    var cellWidth: Int = 0
        private set

    var cellHeight: Int = 0
        private set

    /** Which cells are taken, kept in step with [addItem] / [removeView]. */
    var occupancy = GridOccupancy(0, 0)
        private set

    private val tmpCell = IntArray(2)

    init {
        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        setGridSize(idp.numColumns, idp.numRows)
        isMotionEventSplittingEnabled = false
        // Empty grid space must accept a long press so the workspace can enter overview.
        isLongClickable = true
        // The page card and its badges are drawn here in overview.
        setWillNotDraw(false)
    }

    // ---- overview decoration ---------------------------------------------------------------------

    /**
     * How far this page is into overview: 0 is the normal home screen, 1 is a fully drawn page
     * card. Animated by the workspace, so the outline and badges fade rather than pop.
     */
    var overviewProgress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** Whether this is the page HOME returns to; drawn as a filled badge. */
    var isHomePage: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Whether the remove badge is offered. The last remaining page has none. */
    var isRemovable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onHomeBadgeTap: (() -> Unit)? = null
    var onRemoveBadgeTap: (() -> Unit)? = null

    /** Tap anywhere else on the card: zoom back into this page. */
    var onPageTap: (() -> Unit)? = null

    private val inOverview: Boolean get() = overviewProgress > 0.5f

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val cardRect = RectF()
    private val homeBadgeRect = Rect()
    private val removeBadgeRect = Rect()
    private val homePath = Path()

    private val density = resources.displayMetrics.density
    private val badgeRadius = BADGE_RADIUS_DP * density
    private val badgeMargin = BADGE_MARGIN_DP * density
    private val cardRadius = CARD_RADIUS_DP * density

    private val cardFillColor = context.getColor(R.color.overview_card_fill)
    private val cardStrokeColor = context.getColor(R.color.overview_card_stroke)
    private val badgeIdleColor = context.getColor(R.color.overview_badge_idle)
    private val badgeActiveColor = context.getColor(R.color.eden_leaf)
    private val badgeGlyphColor = context.getColor(R.color.eden_surface)

    override fun onDraw(canvas: Canvas) {
        if (overviewProgress <= 0f) return

        val alpha = (255 * overviewProgress).toInt()
        cardRect.set(0f, 0f, width.toFloat(), height.toFloat())

        cardPaint.color = cardFillColor
        cardPaint.alpha = (Color.alpha(cardFillColor) * overviewProgress).toInt()
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        cardStrokePaint.color = cardStrokeColor
        cardStrokePaint.alpha = alpha
        cardStrokePaint.strokeWidth = 2f * density
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardStrokePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (overviewProgress <= 0f) return
        layOutBadges()
        drawHomeBadge(canvas)
        if (isRemovable) drawRemoveBadge(canvas)
    }

    private fun layOutBadges() {
        val size = (badgeRadius * 2).toInt()
        val top = badgeMargin.toInt()
        val centreX = width / 2
        homeBadgeRect.set(centreX - size / 2, top, centreX + size / 2, top + size)
        removeBadgeRect.set(
            width - badgeMargin.toInt() - size,
            top,
            width - badgeMargin.toInt(),
            top + size,
        )
    }

    /** A house outline: filled when this page is home, hollow when it is not. */
    private fun drawHomeBadge(canvas: Canvas) {
        val alpha = (255 * overviewProgress).toInt()
        val cx = homeBadgeRect.exactCenterX()
        val cy = homeBadgeRect.exactCenterY()

        badgePaint.color = if (isHomePage) badgeActiveColor else badgeIdleColor
        badgePaint.alpha = alpha
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint)

        val r = badgeRadius * 0.45f
        homePath.reset()
        homePath.moveTo(cx - r, cy + r * 0.7f)
        homePath.lineTo(cx - r, cy - r * 0.1f)
        homePath.lineTo(cx, cy - r * 0.9f)
        homePath.lineTo(cx + r, cy - r * 0.1f)
        homePath.lineTo(cx + r, cy + r * 0.7f)
        homePath.close()

        badgeGlyphPaint.color = if (isHomePage) badgeGlyphColor else badgeActiveColor
        badgeGlyphPaint.alpha = alpha
        badgeGlyphPaint.strokeWidth = 2f * density
        canvas.drawPath(homePath, badgeGlyphPaint)
    }

    /** An X: removes this page and everything on it. */
    private fun drawRemoveBadge(canvas: Canvas) {
        val alpha = (255 * overviewProgress).toInt()
        val cx = removeBadgeRect.exactCenterX()
        val cy = removeBadgeRect.exactCenterY()

        badgePaint.color = badgeIdleColor
        badgePaint.alpha = alpha
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint)

        val r = badgeRadius * 0.4f
        badgeGlyphPaint.color = badgeActiveColor
        badgeGlyphPaint.alpha = alpha
        badgeGlyphPaint.strokeWidth = 2.5f * density
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, badgeGlyphPaint)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, badgeGlyphPaint)
    }

    /**
     * In overview the page is a card, not a grid: icons stop taking clicks and taps go to the
     * badges or to zooming back in.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = inOverview

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!inOverview) return super.onTouchEvent(ev)
        if (ev.actionMasked != MotionEvent.ACTION_UP) return true

        val x = ev.x.toInt()
        val y = ev.y.toInt()
        when {
            homeBadgeRect.contains(x, y) -> onHomeBadgeTap?.invoke()
            isRemovable && removeBadgeRect.contains(x, y) -> onRemoveBadgeTap?.invoke()
            else -> onPageTap?.invoke()
        }
        return true
    }

    fun setGridSize(x: Int, y: Int) {
        if (countX == x && countY == y) return
        countX = x
        countY = y
        occupancy = GridOccupancy(x, y)
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, true)
        }
        requestLayout()
    }

    /**
     * Places [child] at ([lp].cellX, [lp].cellY).
     *
     * @return false when the target cells are outside the grid; the caller decides whether to
     *   look for another spot or drop the item.
     */
    fun addItem(child: View, index: Int, lp: LayoutParams, markOccupied: Boolean = true): Boolean {
        if (lp.cellX < 0 || lp.cellY < 0 ||
            lp.cellX + lp.cellHSpan > countX || lp.cellY + lp.cellVSpan > countY
        ) {
            return false
        }

        child.isFocusable = true
        addView(child, index, lp)
        if (markOccupied) occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, true)
        return true
    }

    override fun removeView(view: View) {
        markCellsForView(view, false)
        super.removeView(view)
    }

    override fun removeViewAt(index: Int) {
        markCellsForView(getChildAt(index), false)
        super.removeViewAt(index)
    }

    override fun removeAllViews() {
        occupancy.clear()
        super.removeAllViews()
    }

    private fun markCellsForView(view: View?, occupied: Boolean) {
        val lp = view?.layoutParams as? LayoutParams ?: return
        occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, occupied)
    }

    /** Finds a free cell for a [spanX] x [spanY] item, writing it into [outCell]. */
    fun findVacantCell(outCell: IntArray, spanX: Int = 1, spanY: Int = 1): Boolean =
        occupancy.findVacantCell(outCell, spanX, spanY)

    // ---- drop resolution -----------------------------------------------------------------------

    /**
     * Converts a point in this layout's coordinates to the cell it falls in, clamped to the grid.
     */
    fun pointToCell(x: Int, y: Int, outCell: IntArray) {
        val localX = x - paddingLeft
        val localY = y - paddingTop
        outCell[0] = if (cellWidth > 0) (localX / cellWidth).coerceIn(0, countX - 1) else 0
        outCell[1] = if (cellHeight > 0) (localY / cellHeight).coerceIn(0, countY - 1) else 0
    }

    /** Top-left pixel of a cell, in this layout's coordinates. */
    fun cellToPoint(cellX: Int, cellY: Int, outPoint: IntArray) {
        outPoint[0] = paddingLeft + cellX * cellWidth
        outPoint[1] = paddingTop + cellY * cellHeight
    }

    /** The child occupying ([cellX], [cellY]), or null. */
    fun getChildAtCell(cellX: Int, cellY: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (cellX >= lp.cellX && cellX < lp.cellX + lp.cellHSpan &&
                cellY >= lp.cellY && cellY < lp.cellY + lp.cellVSpan
            ) {
                return child
            }
        }
        return null
    }

    /**
     * Finds the free cell closest to ([x], [y]) for an item of the given span, writing it into
     * [outCell].
     *
     * Ported from `CellLayout.findNearestArea` (AOSP 7) without the reorder-and-shuffle pass:
     * a drop that lands on an occupied cell falls back to the nearest empty one instead of pushing
     * neighbours around. Shuffling arrives with the reorder animation.
     *
     * @return false when the page has no room.
     */
    fun findNearestVacantCell(x: Int, y: Int, spanX: Int, spanY: Int, outCell: IntArray): Boolean {
        pointToCell(x, y, outCell)
        if (occupancy.isRegionVacant(outCell[0], outCell[1], spanX, spanY)) return true

        val targetX = outCell[0]
        val targetY = outCell[1]
        var bestDistance = Int.MAX_VALUE
        var bestX = -1
        var bestY = -1

        for (cy in 0..countY - spanY) {
            for (cx in 0..countX - spanX) {
                if (!occupancy.isRegionVacant(cx, cy, spanX, spanY)) continue
                val dx = cx - targetX
                val dy = cy - targetY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestX = cx
                    bestY = cy
                }
            }
        }

        if (bestX < 0) return false
        outCell[0] = bestX
        outCell[1] = bestY
        return true
    }

    /** Re-marks occupancy for a child that has been moved to new cells. */
    fun onChildMoved(child: View, fromX: Int, fromY: Int, spanX: Int, spanY: Int) {
        occupancy.markCells(fromX, fromY, spanX, spanY, false)
        val lp = child.layoutParams as? LayoutParams ?: return
        occupancy.markCells(lp.cellX, lp.cellY, lp.cellHSpan, lp.cellVSpan, true)
        requestLayout()
    }

    /** True when the page has room for a 1x1 item. */
    val hasVacantCell: Boolean
        get() = occupancy.findVacantCell(tmpCell, 1, 1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val availableWidth = widthSize - paddingLeft - paddingRight
        val availableHeight = heightSize - paddingTop - paddingBottom

        // Cells divide the page evenly. Any remainder stays as unused edge space rather than
        // being smeared across cells, which would make icon columns visibly misalign.
        cellWidth = if (countX > 0) availableWidth / countX else availableWidth
        cellHeight = if (countY > 0) availableHeight / countY else availableHeight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            lp.setup(cellWidth, cellHeight)
            child.measure(
                MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY),
            )
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val left = paddingLeft + lp.x
            val top = paddingTop + lp.y
            child.layout(left, top, left + lp.width, top + lp.height)
        }
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams =
        if (p is LayoutParams) p else LayoutParams(0, 0, 1, 1)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    /** Grid placement for a child: an origin cell plus a span. */
    class LayoutParams : ViewGroup.MarginLayoutParams {

        @JvmField var cellX: Int = 0
        @JvmField var cellY: Int = 0
        @JvmField var cellHSpan: Int = 1
        @JvmField var cellVSpan: Int = 1

        /** Pixel offset inside the parent's content box, filled in by [setup]. */
        @JvmField var x: Int = 0

        @JvmField var y: Int = 0

        /** When true the child keeps the size it was given instead of filling its cells. */
        @JvmField var useCustomSize: Boolean = false

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(cellX: Int, cellY: Int, cellHSpan: Int, cellVSpan: Int) :
            super(MATCH_PARENT, MATCH_PARENT) {
            this.cellX = cellX
            this.cellY = cellY
            this.cellHSpan = max(1, cellHSpan)
            this.cellVSpan = max(1, cellVSpan)
        }

        /** Converts cell coordinates into the pixel box used by measure and layout. */
        fun setup(cellWidth: Int, cellHeight: Int) {
            if (useCustomSize) return
            width = cellHSpan * cellWidth - leftMargin - rightMargin
            height = cellVSpan * cellHeight - topMargin - bottomMargin
            x = cellX * cellWidth + leftMargin
            y = cellY * cellHeight + topMargin
        }

        override fun toString(): String = "($cellX, $cellY) span($cellHSpan, $cellVSpan)"
    }

    private companion object {
        const val BADGE_RADIUS_DP = 16f
        const val BADGE_MARGIN_DP = 10f
        const val CARD_RADIUS_DP = 18f
    }
}
