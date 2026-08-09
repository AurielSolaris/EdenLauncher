package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin

/**
 * Horizontally paginated container: the base class behind the workspace and, later, the paged app
 * drawer.
 *
 * Ported from `PagedView` (AOSP 7). The paging maths - significant-move and fling thresholds,
 * velocity-scaled snap duration, the quintic ease-out - is kept exactly, because that is what
 * makes the launcher feel like Launcher3 rather than a generic ViewPager.
 *
 * Deliberately left out of this port: overview zoom/reordering and free-scroll mode (Phase 2), and
 * the oversized 2x measurement AOSP needed to support them. Pages are measured to the viewport, so
 * a swipe touches only the two visible children.
 *
 * Hot-path discipline: no allocation in [onTouchEvent], [onLayout], [dispatchDraw], or
 * [computeScroll]. Scratch state lives in reused fields.
 */
abstract class PagedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    // ---- scroll state ------------------------------------------------------------------------

    private val scroller = OverScroller(context, SCROLL_INTERPOLATOR)
    private var velocityTracker: VelocityTracker? = null

    /** Scroll x that places page i at the viewport origin. Indexed by child position. */
    private var pageScrolls = EMPTY_SCROLLS

    protected var currentPage: Int = 0
        private set

    /** Page being animated to, or [INVALID_PAGE] when settled. */
    protected var nextPage: Int = INVALID_PAGE
        private set

    private var maxScrollX = 0
    private var childCountOnLastLayout = 0
    private var firstLayout = true

    /** Page restored after a configuration change; applied on the next layout. */
    private var restorePage = INVALID_PAGE

    // ---- touch state -------------------------------------------------------------------------

    private var touchState = TOUCH_STATE_REST
    private var activePointerId = INVALID_POINTER

    private var downMotionX = 0f
    private var downMotionY = 0f
    private var lastMotionX = 0f
    private var lastMotionXRemainder = 0f
    private var totalMotionX = 0f

    private val touchSlop: Int
    private val maximumVelocity: Int
    private val flingThresholdVelocity: Int
    private val minFlingVelocity: Int
    private val minSnapVelocity: Int

    // ---- configuration -----------------------------------------------------------------------

    protected val isRtlLayout: Boolean = resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL

    /** Gap between adjacent pages, in pixels. */
    var pageSpacing: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** When false the view refuses to scroll past the first and last page. */
    protected var allowOverScroll = true

    /** Whether pages are centred vertically inside the viewport. */
    protected var centerPagesVertically = true

    protected var isPageMoving = false
        private set

    var pageIndicator: PageIndicator? = null
        set(value) {
            field = value
            value?.setMarkerCount(childCount)
            value?.setActiveMarker(currentPage)
        }

    // ---- scratch (reused; never allocate per frame) -------------------------------------------

    private val visiblePages = IntArray(2)
    private val edgeVerticalPosition = IntArray(2)
    private val edgeGlowLeft = EdgeEffect(context)
    private val edgeGlowRight = EdgeEffect(context)

    private var lastScreenCenter = -1
    private var forceScreenScrolled = false
    private var wasInOverScroll = false

    init {
        val configuration = ViewConfiguration.get(context)
        touchSlop = configuration.scaledPagingTouchSlop
        maximumVelocity = configuration.scaledMaximumFlingVelocity

        val density = resources.displayMetrics.density
        flingThresholdVelocity = (FLING_THRESHOLD_VELOCITY_DP * density).toInt()
        minFlingVelocity = (MIN_FLING_VELOCITY_DP * density).toInt()
        minSnapVelocity = (MIN_SNAP_VELOCITY_DP * density).toInt()

        isHapticFeedbackEnabled = false
        setWillNotDraw(false)
    }

    // ---- page access -------------------------------------------------------------------------

    val pageCount: Int get() = childCount

    /** Index of the settled page, for callers outside the view hierarchy. */
    val currentPageIndex: Int get() = currentPage

    fun getPageAt(index: Int): View? = getChildAt(index)

    /** Clamps [page] to the range of existing children. */
    private fun validatePage(page: Int): Int = page.coerceIn(0, max(0, pageCount - 1))

    /** Jumps to [page] with no animation. */
    fun setCurrentPage(page: Int) {
        if (!scroller.isFinished) abortScrollerAnimation(resetNextPage = true)
        if (childCount == 0) return

        forceScreenScrolled = true
        currentPage = validatePage(page)
        updateCurrentPageScroll()
        notifyPageSwitch()
        invalidate()
    }

    /** Applied instead of the current page at the next layout; used to survive recreation. */
    fun setRestorePage(page: Int) {
        restorePage = page
    }

    private fun updateCurrentPageScroll() {
        val x = if (currentPage in 0 until pageCount) getScrollForPage(currentPage) else 0
        scrollTo(x, 0)
        forceFinishScroller()
    }

    private fun abortScrollerAnimation(resetNextPage: Boolean) {
        scroller.abortAnimation()
        // Clear the pending page so computeScroll does not treat the abort as an arrival.
        if (resetNextPage) nextPage = INVALID_PAGE
    }

    private fun forceFinishScroller() {
        scroller.forceFinished(true)
        nextPage = INVALID_PAGE
    }

    /** Scroll x that puts page [index] at the viewport origin. */
    fun getScrollForPage(index: Int): Int =
        if (index < 0 || index >= pageScrolls.size) 0 else pageScrolls[index]

    /**
     * How far page [index] is from resting at the viewport origin, in the range [-1, 1].
     * Subclasses use it to drive per-page transforms in [screenScrolled].
     */
    protected fun getScrollProgress(screenCenter: Int, page: View, index: Int): Float {
        val halfScreenSize = viewportWidth / 2
        val delta = screenCenter - (getScrollForPage(index) + halfScreenSize)

        var adjacent = index + 1
        if ((delta < 0 && !isRtlLayout) || (delta > 0 && isRtlLayout)) adjacent = index - 1

        val totalDistance = if (adjacent < 0 || adjacent > pageCount - 1) {
            page.measuredWidth + pageSpacing
        } else {
            abs(getScrollForPage(adjacent) - getScrollForPage(index))
        }
        if (totalDistance == 0) return 0f
        return (delta / totalDistance.toFloat()).coerceIn(-MAX_SCROLL_PROGRESS, MAX_SCROLL_PROGRESS)
    }

    // ---- measurement & layout ------------------------------------------------------------------

    protected val viewportWidth: Int get() = measuredWidth
    protected val viewportHeight: Int get() = measuredHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        if (childCount == 0 ||
            widthMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.UNSPECIFIED ||
            widthSize <= 0 || heightSize <= 0
        ) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Every page gets exactly the viewport minus this view's padding. Pages must share a
        // width: the scroll maths assumes it.
        val childWidth = widthSize - paddingLeft - paddingRight
        val childHeight = heightSize - paddingTop - paddingBottom

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams

            val wSpec: Int
            val hSpec: Int
            if (lp.isFullScreenPage) {
                wSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
                hSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
            } else {
                val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
                val wMode = if (lp.width == wrapContent) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY
                val hMode = if (lp.height == wrapContent) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY
                wSpec = MeasureSpec.makeMeasureSpec(childWidth, wMode)
                hSpec = MeasureSpec.makeMeasureSpec(childHeight, hMode)
            }
            child.measure(wSpec, hSpec)
        }

        setMeasuredDimension(widthSize, heightSize)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val count = childCount
        if (count == 0) return

        // RTL lays pages out right-to-left, so page 0 ends up at the largest scroll x.
        val startIndex = if (isRtlLayout) count - 1 else 0
        val endIndex = if (isRtlLayout) -1 else count
        val delta = if (isRtlLayout) -1 else 1

        if (pageScrolls.size != count) pageScrolls = IntArray(count)

        var childLeft = paddingLeft
        var i = startIndex
        while (i != endIndex) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                val lp = child.layoutParams as LayoutParams
                val childHeight = child.measuredHeight
                val childTop = if (lp.isFullScreenPage) {
                    0
                } else {
                    var t = paddingTop
                    if (centerPagesVertically) {
                        t += (viewportHeight - paddingTop - paddingBottom - childHeight) / 2
                    }
                    t
                }

                child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + childHeight)
                pageScrolls[i] = childLeft - if (lp.isFullScreenPage) 0 else paddingLeft
                childLeft += child.measuredWidth + pageSpacing
            }
            i += delta
        }

        updateMaxScrollX()

        if (firstLayout && currentPage in 0 until count) {
            updateCurrentPageScroll()
            firstLayout = false
        }

        if (scroller.isFinished && childCountOnLastLayout != count) {
            if (restorePage != INVALID_PAGE) {
                setCurrentPage(restorePage)
                restorePage = INVALID_PAGE
            } else {
                setCurrentPage(if (nextPage != INVALID_PAGE) nextPage else currentPage)
            }
        }
        childCountOnLastLayout = count
    }

    private fun updateMaxScrollX() {
        maxScrollX = if (childCount > 0) {
            getScrollForPage(if (isRtlLayout) 0 else childCount - 1)
        } else {
            0
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        pageIndicator?.setMarkerCount(childCount)
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        pageIndicator?.setMarkerCount(childCount)
    }

    // ---- scrolling ---------------------------------------------------------------------------

    override fun scrollTo(x: Int, y: Int) {
        val beforeFirstPage = if (isRtlLayout) x > maxScrollX else x < 0
        val afterLastPage = if (isRtlLayout) x < 0 else x > maxScrollX

        when {
            beforeFirstPage -> {
                super.scrollTo(if (isRtlLayout) maxScrollX else 0, y)
                if (allowOverScroll) {
                    wasInOverScroll = true
                    overScroll(if (isRtlLayout) (x - maxScrollX).toFloat() else x.toFloat())
                }
            }

            afterLastPage -> {
                super.scrollTo(if (isRtlLayout) 0 else maxScrollX, y)
                if (allowOverScroll) {
                    wasInOverScroll = true
                    overScroll(if (isRtlLayout) x.toFloat() else (x - maxScrollX).toFloat())
                }
            }

            else -> {
                if (wasInOverScroll) {
                    overScroll(0f)
                    wasInOverScroll = false
                }
                super.scrollTo(x, y)
            }
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (scrollX != scroller.currX || scrollY != scroller.currY) {
                scrollTo(scroller.currX, scroller.currY)
            }
            invalidate()
            return
        }

        if (nextPage != INVALID_PAGE) {
            currentPage = validatePage(nextPage)
            nextPage = INVALID_PAGE
            notifyPageSwitch()

            // Only report the end of movement once the user has also let go.
            if (touchState == TOUCH_STATE_REST) endPageMoving()
        }
    }

    /** Total scroll range, used by the page indicator to track the finger. */
    protected val totalScrollRange: Int get() = maxScrollX

    private fun pageNearestToCenterOfScreen(): Int {
        var minDistance = Int.MAX_VALUE
        var nearest = 0
        val screenCenter = scrollX + viewportWidth / 2
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childCenter = getScrollForPage(i) + child.measuredWidth / 2
            val distance = abs(childCenter - screenCenter)
            if (distance < minDistance) {
                minDistance = distance
                nearest = i
            }
        }
        return nearest
    }

    protected fun snapToDestination() {
        snapToPage(pageNearestToCenterOfScreen(), PAGE_SNAP_ANIMATION_DURATION)
    }

    @JvmOverloads
    fun snapToPage(page: Int, duration: Int = PAGE_SNAP_ANIMATION_DURATION) {
        val target = validatePage(page)
        snapToPage(target, getScrollForPage(target) - scrollX, duration)
    }

    /** Jumps to [page] without animating, keeping the page-change bookkeeping intact. */
    fun snapToPageImmediately(page: Int) {
        snapToPage(page, 0)
        computeScroll()
    }

    /**
     * Snap whose duration follows the fling velocity, so a hard flick lands fast and a lazy one
     * glides. Straight from AOSP: distance is squashed towards half a screen so travel distance
     * barely changes the feel.
     */
    protected fun snapToPageWithVelocity(page: Int, velocity: Int) {
        val target = validatePage(page)
        val halfScreenSize = viewportWidth / 2
        val delta = getScrollForPage(target) - scrollX

        if (abs(velocity) < minFlingVelocity) {
            snapToPage(target, PAGE_SNAP_ANIMATION_DURATION)
            return
        }

        val distanceRatio = min(1f, abs(delta).toFloat() / (2 * halfScreenSize))
        val distance = halfScreenSize + halfScreenSize * distanceInfluenceForSnapDuration(distanceRatio)
        val clampedVelocity = max(minSnapVelocity, abs(velocity))

        // 4x approximates the derivative of the scroll interpolator at zero (5), slightly slowed.
        val duration = 4 * round(1000 * abs(distance / clampedVelocity)).toInt()
        snapToPage(target, delta, duration)
    }

    private fun snapToPage(page: Int, delta: Int, duration: Int) {
        nextPage = validatePage(page)
        beginPageMoving()

        val effectiveDuration = if (duration == 0) abs(delta) else duration
        if (!scroller.isFinished) abortScrollerAnimation(resetNextPage = false)
        scroller.startScroll(scrollX, 0, delta, 0, effectiveDuration)

        pageIndicator?.setActiveMarker(nextPage)
        forceScreenScrolled = true
        invalidate()
    }

    private fun distanceInfluenceForSnapDuration(f: Float): Float {
        var x = f - 0.5f // centre about zero
        x *= (0.3f * Math.PI / 2.0).toFloat()
        return sin(x)
    }

    fun scrollLeft() {
        if (currentPage > 0) snapToPage(currentPage - 1)
    }

    fun scrollRight() {
        if (currentPage < pageCount - 1) snapToPage(currentPage + 1)
    }

    // ---- movement callbacks ------------------------------------------------------------------

    private fun beginPageMoving() {
        if (!isPageMoving) {
            isPageMoving = true
            onPageBeginMoving()
        }
    }

    private fun endPageMoving() {
        if (isPageMoving) {
            isPageMoving = false
            onPageEndMoving()
        }
    }

    protected open fun onPageBeginMoving() = Unit

    protected open fun onPageEndMoving() {
        wasInOverScroll = false
    }

    /** Called once per frame while scrolling, with the scroll x of the viewport centre. */
    protected open fun screenScrolled(screenCenter: Int) = Unit

    /** Called when the settled page changes. */
    protected open fun onPageSwitch(page: View?, index: Int) = Unit

    private fun notifyPageSwitch() {
        val index = if (nextPage != INVALID_PAGE) nextPage else currentPage
        onPageSwitch(getPageAt(index), index)
        pageIndicator?.setActiveMarker(index)
    }

    // ---- touch handling ----------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        acquireVelocityTracker(ev)
        if (childCount <= 0) return super.onInterceptTouchEvent(ev)

        // Fast path: already dragging, keep intercepting.
        if (ev.action == MotionEvent.ACTION_MOVE && touchState == TOUCH_STATE_SCROLLING) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                // A move without a matching down (Workspace can swallow the down) falls through
                // to the down handling below, as in AOSP.
                if (activePointerId != INVALID_POINTER) determineScrollingStart(ev)

            MotionEvent.ACTION_DOWN -> {
                downMotionX = ev.x
                downMotionY = ev.y
                lastMotionX = ev.x
                lastMotionXRemainder = 0f
                totalMotionX = 0f
                activePointerId = ev.getPointerId(0)

                // Touching a nearly-settled fling stops it; touching a fast one grabs it.
                val xDist = abs(scroller.finalX - scroller.currX)
                if (scroller.isFinished || xDist < touchSlop / 3) {
                    touchState = TOUCH_STATE_REST
                    if (!scroller.isFinished) {
                        setCurrentPage(if (nextPage != INVALID_PAGE) nextPage else currentPage)
                        endPageMoving()
                    }
                } else {
                    touchState = TOUCH_STATE_SCROLLING
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetTouchState()

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
                releaseVelocityTracker()
            }
        }

        return touchState != TOUCH_STATE_REST
    }

    /** Promotes the gesture to a page drag once the finger passes the paging slop on x. */
    protected open fun determineScrollingStart(ev: MotionEvent, touchSlopScale: Float = 1f) {
        val pointerIndex = ev.findPointerIndex(activePointerId)
        if (pointerIndex == -1) return

        val x = ev.getX(pointerIndex)
        if (abs(x - lastMotionX) <= round(touchSlopScale * touchSlop)) return

        touchState = TOUCH_STATE_SCROLLING
        totalMotionX += abs(lastMotionX - x)
        lastMotionX = x
        lastMotionXRemainder = 0f
        onScrollInteractionBegin()
        beginPageMoving()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        super.onTouchEvent(ev)
        if (childCount <= 0) return super.onTouchEvent(ev)

        acquireVelocityTracker(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) abortScrollerAnimation(resetNextPage = false)

                downMotionX = ev.x
                lastMotionX = ev.x
                downMotionY = ev.y
                lastMotionXRemainder = 0f
                totalMotionX = 0f
                activePointerId = ev.getPointerId(0)

                if (touchState == TOUCH_STATE_SCROLLING) {
                    onScrollInteractionBegin()
                    beginPageMoving()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchState != TOUCH_STATE_SCROLLING) {
                    determineScrollingStart(ev)
                } else {
                    val pointerIndex = ev.findPointerIndex(activePointerId)
                    if (pointerIndex == -1) return true

                    val x = ev.getX(pointerIndex)
                    val deltaX = lastMotionX + lastMotionXRemainder - x
                    totalMotionX += abs(deltaX)

                    // Scroll only by whole pixels, carrying the fraction forward: scrollBy is
                    // integral, and dropping the remainder makes slow drags visibly lag the finger.
                    if (abs(deltaX) >= 1f) {
                        scrollTo(scrollX + deltaX.toInt(), scrollY)
                        lastMotionX = x
                        lastMotionXRemainder = deltaX - deltaX.toInt()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                when (touchState) {
                    TOUCH_STATE_SCROLLING -> handleScrollingUp(ev)
                    else -> if (touchState == TOUCH_STATE_REST) onUnhandledTap(ev)
                }
                resetTouchState()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (touchState == TOUCH_STATE_SCROLLING) snapToDestination()
                resetTouchState()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
                releaseVelocityTracker()
            }
        }

        return true
    }

    /**
     * Decides where the page lands on lift-off.
     *
     * A fling beats a large drag, so dragging left and flicking right goes right. Dragging past
     * [RETURN_TO_ORIGINAL_PAGE_THRESHOLD] and then flinging back returns to the starting page
     * instead of overshooting into the next one.
     */
    private fun handleScrollingUp(ev: MotionEvent) {
        val tracker = velocityTracker ?: return
        val pointerIndex = ev.findPointerIndex(activePointerId)
        if (pointerIndex == -1) {
            snapToDestination()
            return
        }

        val x = ev.getX(pointerIndex)
        tracker.computeCurrentVelocity(1000, maximumVelocity.toFloat())
        val velocityX = tracker.getXVelocity(activePointerId).toInt()
        val deltaX = (x - downMotionX).toInt()
        val pageWidth = getPageAt(currentPage)?.measuredWidth ?: viewportWidth

        totalMotionX += abs(lastMotionX + lastMotionXRemainder - x)

        val isSignificantMove = abs(deltaX) > pageWidth * SIGNIFICANT_MOVE_THRESHOLD
        val isFling = totalMotionX > MIN_LENGTH_FOR_FLING && abs(velocityX) > flingThresholdVelocity
        val returnToOriginalPage = isFling &&
            abs(deltaX) > pageWidth * RETURN_TO_ORIGINAL_PAGE_THRESHOLD &&
            sign(velocityX.toFloat()) != sign(deltaX.toFloat())

        val isDeltaLeft = if (isRtlLayout) deltaX > 0 else deltaX < 0
        val isVelocityLeft = if (isRtlLayout) velocityX > 0 else velocityX < 0

        val goPrevious = ((isSignificantMove && !isDeltaLeft && !isFling) || (isFling && !isVelocityLeft)) &&
            currentPage > 0
        val goNext = ((isSignificantMove && isDeltaLeft && !isFling) || (isFling && isVelocityLeft)) &&
            currentPage < childCount - 1

        when {
            goPrevious -> snapToPageWithVelocity(
                if (returnToOriginalPage) currentPage else currentPage - 1,
                velocityX,
            )

            goNext -> snapToPageWithVelocity(
                if (returnToOriginalPage) currentPage else currentPage + 1,
                velocityX,
            )

            else -> snapToDestination()
        }
        onScrollInteractionEnd()
    }

    private fun resetTouchState() {
        releaseVelocityTracker()
        touchState = TOUCH_STATE_REST
        activePointerId = INVALID_POINTER
        edgeGlowLeft.onRelease()
        edgeGlowRight.onRelease()
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val pointerIndex = ev.actionIndex
        if (ev.getPointerId(pointerIndex) != activePointerId) return

        // Transfer the gesture to another finger rather than dropping it mid-drag.
        val newPointerIndex = if (pointerIndex == 0) 1 else 0
        downMotionX = ev.getX(newPointerIndex)
        lastMotionX = downMotionX
        lastMotionXRemainder = 0f
        activePointerId = ev.getPointerId(newPointerIndex)
        velocityTracker?.clear()
    }

    private fun acquireVelocityTracker(ev: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(ev)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Triggered when a touch-driven scroll starts and ends; overridden to pause expensive work. */
    protected open fun onScrollInteractionBegin() = Unit

    protected open fun onScrollInteractionEnd() = Unit

    /** A tap that reached the pager without any child consuming it. */
    protected open fun onUnhandledTap(ev: MotionEvent) = Unit

    // ---- over-scroll glow --------------------------------------------------------------------

    protected open fun overScroll(amount: Float) {
        val screenSize = viewportWidth
        if (screenSize == 0) return
        val f = amount / screenSize
        when {
            f < 0 -> edgeGlowLeft.onPull(-f)
            f > 0 -> edgeGlowRight.onPull(f)
            else -> return
        }
        invalidate()
    }

    /** Vertical extent of the edge glow, so it does not paint over the dock or status bar. */
    protected open fun getEdgeVerticalPosition(out: IntArray) {
        out[0] = 0
        out[1] = height
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun dispatchDraw(canvas: Canvas) {
        val count = childCount
        if (count == 0) return

        val screenCenter = scrollX + viewportWidth / 2
        if (screenCenter != lastScreenCenter || forceScreenScrolled) {
            // Set the flag before the callback so screenScrolled can re-arm it for the next frame.
            forceScreenScrolled = false
            screenScrolled(screenCenter)
            lastScreenCenter = screenCenter
        }

        computeVisiblePages(visiblePages)
        val leftScreen = visiblePages[0]
        val rightScreen = visiblePages[1]
        if (leftScreen == -1 || rightScreen == -1) return

        // Only the one or two pages actually on screen are drawn; the rest cost nothing.
        val drawingTime = drawingTime
        canvas.save()
        canvas.clipRect(scrollX, scrollY, scrollX + width, scrollY + height)
        for (i in rightScreen downTo leftScreen) {
            val child = getChildAt(i)
            if (child != null && shouldDrawChild(child)) drawChild(canvas, child, drawingTime)
        }
        canvas.restore()
    }

    protected open fun shouldDrawChild(child: View): Boolean =
        child.visibility == VISIBLE && child.alpha > 0f

    /** Inclusive index range of pages intersecting the viewport, or -1/-1 when none do. */
    private fun computeVisiblePages(range: IntArray) {
        val count = childCount
        if (count == 0 || pageScrolls.size != count) {
            range[0] = -1
            range[1] = -1
            return
        }

        val screenLeft = scrollX
        val screenRight = screenLeft + viewportWidth
        var left = -1
        var right = -1
        for (i in 0 until count) {
            val child = getChildAt(i)
            val childLeft = pageScrolls[i] + paddingLeft
            val childRight = childLeft + child.measuredWidth
            if (childRight > screenLeft && childLeft < screenRight) {
                if (left == -1) left = i
                right = i
            }
        }
        range[0] = left
        range[1] = right
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (pageCount == 0) return

        if (!edgeGlowLeft.isFinished) {
            val restoreCount = canvas.save()
            canvas.rotate(270f)
            getEdgeVerticalPosition(edgeVerticalPosition)
            canvas.translate((-edgeVerticalPosition[1]).toFloat(), 0f)
            edgeGlowLeft.setSize(edgeVerticalPosition[1] - edgeVerticalPosition[0], width)
            if (edgeGlowLeft.draw(canvas)) postInvalidateOnAnimation()
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeGlowRight.isFinished) {
            val restoreCount = canvas.save()
            canvas.translate(getScrollForPage(if (isRtlLayout) 0 else pageCount - 1).toFloat(), 0f)
            canvas.rotate(90f)
            getEdgeVerticalPosition(edgeVerticalPosition)
            canvas.translate(edgeVerticalPosition[0].toFloat(), -width.toFloat())
            edgeGlowRight.setSize(edgeVerticalPosition[1] - edgeVerticalPosition[0], width)
            if (edgeGlowRight.draw(canvas)) postInvalidateOnAnimation()
            canvas.restoreToCount(restoreCount)
        }
    }

    // ---- layout params -----------------------------------------------------------------------

    class LayoutParams : ViewGroup.LayoutParams {
        /** A page that ignores this view's padding and fills the whole viewport. */
        @JvmField
        var isFullScreenPage: Boolean = false

        constructor(width: Int, height: Int) : super(width, height)
        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    /** Receives page count and position updates; implemented by [PageIndicatorDots]. */
    interface PageIndicator {
        fun setMarkerCount(count: Int)
        fun setActiveMarker(index: Int)
    }

    companion object {
        const val INVALID_PAGE = -1
        private const val INVALID_POINTER = -1

        private const val TOUCH_STATE_REST = 0
        private const val TOUCH_STATE_SCROLLING = 1

        const val PAGE_SNAP_ANIMATION_DURATION = 750

        /** Minimum travel before a flick counts as a fling, to absorb jittery taps. */
        private const val MIN_LENGTH_FOR_FLING = 25f

        /** Drag past this fraction of a page and lift: the page advances. */
        private const val SIGNIFICANT_MOVE_THRESHOLD = 0.4f

        /** Drag past this fraction then fling back: return to the page you started on. */
        private const val RETURN_TO_ORIGINAL_PAGE_THRESHOLD = 0.33f

        private const val MAX_SCROLL_PROGRESS = 1f

        // Scaled by density at construction.
        private const val FLING_THRESHOLD_VELOCITY_DP = 500
        private const val MIN_SNAP_VELOCITY_DP = 1500
        private const val MIN_FLING_VELOCITY_DP = 250

        private val EMPTY_SCROLLS = IntArray(0)

        /** Quintic ease-out; the Launcher3 page-snap curve. */
        private val SCROLL_INTERPOLATOR = android.view.animation.Interpolator { t ->
            val x = t - 1f
            x * x * x * x * x + 1f
        }
    }
}
