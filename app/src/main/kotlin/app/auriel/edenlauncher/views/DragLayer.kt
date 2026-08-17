package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.auriel.edenlauncher.dragndrop.DragController

/**
 * The launcher's root container and the surface every drag is drawn on.
 *
 * Ported from `DragLayer` (AOSP 7). What it owns in this phase: window insets for the whole
 * hierarchy, the coordinate transforms a drag needs to map a point between any descendant and the
 * root, and the drawing-order hook that keeps a dragged view above its siblings. The drag
 * controller itself, widget resize frames, and the folder scrim arrive in Phase 2.
 */
class DragLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : InsettableFrameLayout(context, attrs) {

    /** Scratch buffers. Drag maths runs per touch event; none of it should allocate. */
    private val tmpXY = IntArray(2)
    private val tmpPoint = FloatArray(2)
    private val tmpInverse = Matrix()
    private val hitRect = Rect()
    private val ancestorChain = ArrayList<View>(8)

    /** View drawn last (on top) regardless of child order - the dragged item. */
    private var topDrawnChild: View? = null

    init {
        // A drag is a single-pointer gesture; splitting it across children causes stuck drags.
        isMotionEventSplittingEnabled = false
        isChildrenDrawingOrderEnabled = true
        // Required for the launcher to receive window insets rather than have them consumed
        // by the decor view.
        fitsSystemWindows = false
    }

    /** Raises [child] to the top of the drawing order; pass null to restore normal order. */
    fun setTopDrawnChild(child: View?) {
        if (topDrawnChild === child) return
        topDrawnChild = child
        invalidate()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val top = topDrawnChild ?: return drawingPosition
        val topIndex = indexOfChild(top)
        if (topIndex < 0) return drawingPosition

        return when {
            drawingPosition == childCount - 1 -> topIndex
            drawingPosition >= topIndex -> drawingPosition + 1
            else -> drawingPosition
        }
    }

    // ---- coordinate transforms ----------------------------------------------------------------

    /**
     * Maps a point given in [descendant]'s coordinates into this layer's coordinates.
     *
     * @param coord in/out point.
     * @param includeRootScroll whether the descendant's own scroll counts. TextViews use scroll to
     *   mean text offset, so callers dealing with labels pass false.
     * @return the accumulated scale between [descendant] and this layer.
     */
    fun getDescendantCoordRelativeToSelf(
        descendant: View,
        coord: IntArray,
        includeRootScroll: Boolean = false,
    ): Float {
        tmpPoint[0] = coord[0].toFloat()
        tmpPoint[1] = coord[1].toFloat()

        ancestorChain.clear()
        var view: View? = descendant
        while (view !== this && view != null) {
            ancestorChain.add(view)
            view = view.parent as? View
        }
        ancestorChain.add(this)

        var scale = 1f
        for (i in ancestorChain.indices) {
            val ancestor = ancestorChain[i]
            if (ancestor !== descendant || includeRootScroll) {
                tmpPoint[0] -= ancestor.scrollX.toFloat()
                tmpPoint[1] -= ancestor.scrollY.toFloat()
            }
            ancestor.matrix.mapPoints(tmpPoint)
            tmpPoint[0] += ancestor.left.toFloat()
            tmpPoint[1] += ancestor.top.toFloat()
            scale *= ancestor.scaleX
        }
        ancestorChain.clear()

        coord[0] = Math.round(tmpPoint[0])
        coord[1] = Math.round(tmpPoint[1])
        return scale
    }

    /** Inverse of [getDescendantCoordRelativeToSelf]. */
    fun mapCoordInSelfToDescendant(descendant: View, coord: IntArray): Float {
        tmpPoint[0] = coord[0].toFloat()
        tmpPoint[1] = coord[1].toFloat()

        ancestorChain.clear()
        var view: View? = descendant
        while (view !== this && view != null) {
            ancestorChain.add(view)
            view = view.parent as? View
        }
        ancestorChain.add(this)

        var scale = 1f
        for (i in ancestorChain.indices.reversed()) {
            val ancestor = ancestorChain[i]
            val next = if (i > 0) ancestorChain[i - 1] else null

            tmpPoint[0] += ancestor.scrollX.toFloat()
            tmpPoint[1] += ancestor.scrollY.toFloat()

            if (next != null) {
                tmpPoint[0] -= next.left.toFloat()
                tmpPoint[1] -= next.top.toFloat()
                next.matrix.invert(tmpInverse)
                tmpInverse.mapPoints(tmpPoint)
                scale *= next.scaleX
            }
        }
        ancestorChain.clear()

        coord[0] = Math.round(tmpPoint[0])
        coord[1] = Math.round(tmpPoint[1])
        return scale
    }

    /** Bounds of [descendant] in this layer's coordinates; returns the accumulated scale. */
    fun getDescendantRectRelativeToSelf(descendant: View, out: Rect): Float {
        tmpXY[0] = 0
        tmpXY[1] = 0
        val scale = getDescendantCoordRelativeToSelf(descendant, tmpXY)
        out.set(
            tmpXY[0],
            tmpXY[1],
            (tmpXY[0] + scale * descendant.measuredWidth).toInt(),
            (tmpXY[1] + scale * descendant.measuredHeight).toInt(),
        )
        return scale
    }

    /** Position of [child] within this layer, written into [out]. */
    fun getLocationInDragLayer(child: View, out: IntArray): Float {
        out[0] = 0
        out[1] = 0
        return getDescendantCoordRelativeToSelf(child, out)
    }

    /** True when [ev] falls inside [view], in this layer's coordinates. */
    fun isEventOverView(view: View, ev: MotionEvent): Boolean {
        getDescendantRectRelativeToSelf(view, hitRect)
        return hitRect.contains(ev.x.toInt(), ev.y.toInt())
    }

    // ---- touch --------------------------------------------------------------------------------

    /**
     * Notified once per gesture when the touch sequence ends, so transient UI (a popup, an open
     * folder) can dismiss itself without every child tracking ACTION_UP.
     */
    fun interface TouchCompleteListener {
        fun onTouchComplete()
    }

    private var touchCompleteListener: TouchCompleteListener? = null

    fun setTouchCompleteListener(listener: TouchCompleteListener?) {
        touchCompleteListener = listener
    }

    /** Set by the launcher once the controller exists; null before then. */
    var dragController: DragController? = null

    /**
     * The widget resize handles, while they are showing, and what to do when the user touches
     * anywhere else.
     *
     * Handled here rather than by the frame itself because the frame only covers the widget: a tap
     * meant to dismiss it lands on the workspace, which the frame never sees. AOSP's DragLayer
     * does the same check for the same reason.
     */
    var activeResizeFrame: View? = null
    var onTouchOutsideResizeFrame: (() -> Unit)? = null

    /**
     * The open folder panel, when tapping away from it is meant to close it, and what to do.
     *
     * Same reasoning as the resize frame, plus one of its own: the panel floats over the workspace,
     * so a tap beside it lands on whatever icon happens to be behind. Closing the folder *and*
     * launching the app underneath is not what anybody meant by tapping off a folder, so the whole
     * gesture is swallowed here rather than let through.
     */
    var activeFolder: View? = null
    var onTouchOutsideFolder: (() -> Unit)? = null

    /** Set for the rest of a gesture that only ever meant "close the folder". */
    private var swallowingGesture = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
            touchCompleteListener?.onTouchComplete()
            touchCompleteListener = null
        }

        val frame = activeResizeFrame
        if (frame != null && ev.action == MotionEvent.ACTION_DOWN && !isEventOverView(frame, ev)) {
            onTouchOutsideResizeFrame?.invoke()
        }

        val folder = activeFolder
        if (folder != null && ev.action == MotionEvent.ACTION_DOWN && !isEventOverView(folder, ev)) {
            swallowingGesture = true
            onTouchOutsideFolder?.invoke()
            return true
        }

        // Once a drag is running the drag layer owns the gesture, and children are cancelled.
        if (dragController?.onInterceptTouchEvent(ev) == true) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dragController?.onTouchEvent(ev) == true) return true
        if (swallowingGesture) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                swallowingGesture = false
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams = LayoutParams(p)
}
