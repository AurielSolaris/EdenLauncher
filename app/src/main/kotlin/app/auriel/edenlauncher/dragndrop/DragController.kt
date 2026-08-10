package app.auriel.edenlauncher.dragndrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.views.DragLayer
import app.auriel.edenlauncher.views.DragView

/**
 * Owns a drag from long-press to drop.
 *
 * Ported from `DragController` (AOSP 7). Responsibilities kept: build the floating [DragView],
 * consume the touch stream once a drag starts, track which [DropTarget] the finger is over,
 * and tell the [DragSource] how the gesture ended.
 *
 * The AOSP scroll-zone logic (dragging to the screen edge pages the workspace) is here too,
 * because a launcher where you cannot drag an icon to the next page is not a launcher.
 */
class DragController(private val dragLayer: DragLayer) {

    /** Targets in priority order; the first whose hit rect contains the finger wins. */
    private val dropTargets = ArrayList<DropTarget>(4)

    private val dragObject = DragObject()
    private val hitRect = Rect()
    private val coordinates = IntArray(2)

    private var lastDropTarget: DropTarget? = null

    var isDragging: Boolean = false
        private set

    /** Notified when a drag begins and ends, so the launcher can show or hide the delete bar. */
    var listener: DragListener? = null

    /** Called each move with the touch x, so the workspace can page at the edges. */
    var scrollListener: ScrollListener? = null

    private val edgeScrollZonePx =
        (EDGE_SCROLL_ZONE_DP * dragLayer.resources.displayMetrics.density).toInt()

    private var lastScrollTime = 0L

    // An armed but not yet started drag. See preparePendingDrag.
    private var pendingView: View? = null
    private var pendingSource: DragSource? = null
    private var pendingInfo: ItemInfo? = null
    private var pendingHideOriginal = true
    private var pendingOnPromoted: (() -> Unit)? = null
    private var pendingStartX = 0
    private var pendingStartY = 0

    private val touchSlopSquared: Int = android.view.ViewConfiguration
        .get(dragLayer.context)
        .scaledTouchSlop
        .let { it * it }

    interface DragListener {
        fun onDragStart(info: ItemInfo?)
        fun onDragEnd()
    }

    fun interface ScrollListener {
        /** @param direction -1 to page left, +1 to page right. */
        fun onDragScroll(direction: Int)
    }

    fun addDropTarget(target: DropTarget) {
        if (!dropTargets.contains(target)) dropTargets.add(target)
    }

    fun removeDropTarget(target: DropTarget) {
        dropTargets.remove(target)
    }

    /**
     * Lifts [view] into a drag.
     *
     * @param source where the item came from, notified on completion.
     * @param info the model object being moved.
     * @param hideOriginal whether the source view is hidden while the drag runs. Workspace icons
     *   hide (the item is moving); drawer icons do not (the item is being copied out).
     */
    /**
     * Arms a drag without starting one.
     *
     * A long press on an icon should offer its options, not immediately rip it off the page - but
     * long-press-and-drag is decades of muscle memory and cannot be broken to make room for a
     * menu. So the press arms the drag and shows the popup, and the first movement past the touch
     * slop promotes it: keep still and you get the menu, move and you get the drag you expected.
     * This is what AOSP Launcher3 does from Android 8 onwards.
     *
     * @param onPromoted called if the gesture turns into a real drag, so the caller can dismiss
     *   whatever it put on screen.
     */
    fun preparePendingDrag(
        view: View,
        source: DragSource,
        info: ItemInfo,
        hideOriginal: Boolean = true,
        onPromoted: () -> Unit = {},
    ) {
        if (isDragging) return
        pendingView = view
        pendingSource = source
        pendingInfo = info
        pendingHideOriginal = hideOriginal
        pendingOnPromoted = onPromoted
        pendingStartX = dragObject.x
        pendingStartY = dragObject.y
    }

    /** Drops an armed drag that never moved. */
    fun clearPendingDrag() {
        pendingView = null
        pendingSource = null
        pendingInfo = null
        pendingOnPromoted = null
    }

    private fun promotePendingDrag() {
        val view = pendingView ?: return
        val source = pendingSource ?: return
        val info = pendingInfo ?: return
        val onPromoted = pendingOnPromoted
        val hideOriginal = pendingHideOriginal
        clearPendingDrag()

        onPromoted?.invoke()
        startDrag(view, source, info, hideOriginal)
    }

    fun startDrag(
        view: View,
        source: DragSource,
        info: ItemInfo,
        hideOriginal: Boolean = true,
    ) {
        if (isDragging) return

        val bitmap = snapshot(view) ?: return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // Position the floating icon exactly over the view it replaces, then keep that offset.
        coordinates[0] = 0
        coordinates[1] = 0
        dragLayer.getDescendantCoordRelativeToSelf(view, coordinates)

        val registrationX = dragObject.x - coordinates[0]
        val registrationY = dragObject.y - coordinates[1]

        val dragView = DragView(
            dragLayer.context,
            bitmap,
            if (registrationX in 0..bitmap.width) registrationX else bitmap.width / 2,
            if (registrationY in 0..bitmap.height) registrationY else bitmap.height / 2,
            1f,
        )

        dragObject.dragView = dragView
        dragObject.dragInfo = info
        dragObject.dragSource = source
        dragObject.originalView = view
        dragObject.cancelled = false
        dragObject.dragComplete = false

        dragLayer.addView(dragView)
        dragLayer.setTopDrawnChild(dragView)
        dragView.move(dragObject.x, dragObject.y)
        dragView.animateLift(DragView.LIFT_SCALE)

        if (hideOriginal) view.visibility = View.INVISIBLE

        isDragging = true
        listener?.onDragStart(info)
    }

    /** Records the touch position even before a drag starts, so the lift lands under the finger. */
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        recordPosition(ev)

        if (pendingView != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = dragObject.x - pendingStartX
                    val dy = dragObject.y - pendingStartY
                    if (dx * dx + dy * dy > touchSlopSquared) {
                        promotePendingDrag()
                        // Returning true here takes the gesture away from the child, which is what
                        // cancels its press state and hands the rest of the stream to the drag.
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearPendingDrag()
            }
        }

        return isDragging
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isDragging) return false
        recordPosition(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragObject.dragView?.move(dragObject.x, dragObject.y)
                updateTarget()
                maybeScroll()
            }

            MotionEvent.ACTION_UP -> drop()

            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    private fun recordPosition(ev: MotionEvent) {
        dragObject.x = ev.x.toInt()
        dragObject.y = ev.y.toInt()
    }

    /** Finds the target under the finger and issues enter/over/exit in the right order. */
    private fun updateTarget() {
        val target = findDropTarget(dragObject.x, dragObject.y)
        if (target !== lastDropTarget) {
            lastDropTarget?.onDragExit(dragObject)
            target?.onDragEnter(dragObject)
            lastDropTarget = target
        }
        target?.onDragOver(dragObject)
    }

    private fun findDropTarget(x: Int, y: Int): DropTarget? {
        // Reverse order: targets added later (the delete bar, an open folder) sit on top.
        for (i in dropTargets.indices.reversed()) {
            val target = dropTargets[i]
            if (!target.isDropEnabled) continue
            target.getHitRectRelativeToDragLayer(hitRect)
            if (hitRect.contains(x, y)) return target
        }
        return null
    }

    /** Pages the workspace when the finger rests near either edge. */
    private fun maybeScroll() {
        val listener = scrollListener ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScrollTime < EDGE_SCROLL_INTERVAL_MS) return

        val direction = when {
            dragObject.x < edgeScrollZonePx -> -1
            dragObject.x > dragLayer.width - edgeScrollZonePx -> 1
            else -> return
        }
        lastScrollTime = now
        listener.onDragScroll(direction)
    }

    private fun drop() {
        val target = findDropTarget(dragObject.x, dragObject.y)
        val accepted = target != null && target.acceptDrop(dragObject)

        if (accepted) {
            target!!.onDrop(dragObject)
            dragObject.dragComplete = true
        } else {
            dragObject.cancelled = true
        }

        dragObject.dragSource?.onDropCompleted(target as? View, dragObject, accepted)
        endDrag()
    }

    /** Aborts the drag; the source restores the item where it was. */
    fun cancelDrag() {
        if (!isDragging) return
        dragObject.cancelled = true
        lastDropTarget?.onDragExit(dragObject)
        dragObject.dragSource?.onDropCompleted(null, dragObject, false)
        endDrag()
    }

    private fun endDrag() {
        isDragging = false
        lastDropTarget = null

        dragObject.originalView?.let { if (it.visibility == View.INVISIBLE) it.visibility = View.VISIBLE }
        dragObject.dragView?.remove()
        dragLayer.setTopDrawnChild(null)

        dragObject.dragView = null
        dragObject.dragInfo = null
        dragObject.dragSource = null
        dragObject.originalView = null

        listener?.onDragEnd()
    }

    /** Rasterises [view] as it appears now, including its icon and label. */
    private fun snapshot(view: View): Bitmap? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        return bitmap
    }

    private companion object {
        const val EDGE_SCROLL_ZONE_DP = 32f
        const val EDGE_SCROLL_INTERVAL_MS = 600L
    }
}
