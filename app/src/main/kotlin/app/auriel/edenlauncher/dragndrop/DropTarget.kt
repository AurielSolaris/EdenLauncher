package app.auriel.edenlauncher.dragndrop

import android.graphics.Rect
import android.view.View
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.views.DragView

/**
 * State of the drag in progress, handed to every target callback.
 *
 * Ported from `DropTarget.DragObject` (AOSP 7). One instance lives for the whole gesture and is
 * mutated in place - a fresh object per motion event would allocate on the touch path.
 */
class DragObject {
    /** Touch position in drag-layer coordinates. */
    @JvmField var x: Int = 0

    @JvmField var y: Int = 0

    /** Offset from the touch point to the top-left of [dragView]. */
    @JvmField var xOffset: Int = 0

    @JvmField var yOffset: Int = 0

    /** The floating view following the finger. */
    @JvmField var dragView: DragView? = null

    /** Model object being moved. */
    @JvmField var dragInfo: ItemInfo? = null

    /** Where the drag started, notified when it finishes. */
    @JvmField var dragSource: DragSource? = null

    /** The original view, hidden for the duration of the drag. */
    @JvmField var originalView: View? = null

    /** True when the drag ended without a drop (cancelled, or dropped on nothing). */
    @JvmField var cancelled: Boolean = false

    /** True once a target has accepted the drop and finished with it. */
    @JvmField var dragComplete: Boolean = false
}

/**
 * Something a drag can be dropped onto: a workspace page, the dock, a folder, the delete bar.
 *
 * Ported from `DropTarget` (AOSP 7).
 */
interface DropTarget {

    /** False while the target is uninterested in the current drag; it is then skipped entirely. */
    val isDropEnabled: Boolean

    /**
     * Final chance to refuse. Called just before [onDrop]; returning false sends the item back to
     * its source rather than dropping it into nowhere.
     */
    fun acceptDrop(d: DragObject): Boolean

    /** Takes ownership of the dragged item. Only called after [acceptDrop] returned true. */
    fun onDrop(d: DragObject)

    fun onDragEnter(d: DragObject)

    /** Called for every motion event while the finger is over this target. */
    fun onDragOver(d: DragObject)

    fun onDragExit(d: DragObject)

    /** Bounds of this target in drag-layer coordinates, used for hit testing. */
    fun getHitRectRelativeToDragLayer(outRect: Rect)
}

/**
 * Where a drag started. Notified once the gesture ends so it can restore or remove the original
 * view. Ported from `DragSource` (AOSP 7).
 */
interface DragSource {
    /**
     * @param target the view that accepted the drop, or null if nothing did.
     * @param success false when the item must go back where it came from.
     */
    fun onDropCompleted(target: View?, d: DragObject, success: Boolean)
}
