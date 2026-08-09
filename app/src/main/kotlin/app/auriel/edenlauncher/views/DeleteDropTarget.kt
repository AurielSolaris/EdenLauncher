package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.dragndrop.DragObject
import app.auriel.edenlauncher.dragndrop.DropTarget
import app.auriel.edenlauncher.dragndrop.ItemPlacementHandler
import app.auriel.edenlauncher.model.ItemInfo

/**
 * The "Remove" bar that slides in at the top of the screen during a drag.
 *
 * Ported from `DeleteDropTarget` / `ButtonDropTarget` (AOSP 7). Uninstall is not wired up yet -
 * that needs the package-installer intent and a result callback - so this only removes the item
 * from the launcher, which is the non-destructive half and the one users reach for most.
 */
class DeleteDropTarget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr), DropTarget {

    var placementHandler: ItemPlacementHandler? = null

    private var isHighlighted = false

    private val idleColor = context.getColor(R.color.drop_target_idle)
    private val activeColor = context.getColor(R.color.drop_target_active)

    init {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.settings_text))
        text = context.getString(R.string.remove_drop_target_label)
        setBackgroundColor(idleColor)
        visibility = GONE
    }

    /** Shown only while a drag is running, as in AOSP. */
    fun setDragInProgress(inProgress: Boolean) {
        visibility = if (inProgress) VISIBLE else GONE
        if (!inProgress) setHighlighted(false)
    }

    private fun setHighlighted(highlighted: Boolean) {
        if (isHighlighted == highlighted) return
        isHighlighted = highlighted
        setBackgroundColor(if (highlighted) activeColor else idleColor)
    }

    override val isDropEnabled: Boolean get() = visibility == VISIBLE

    override fun acceptDrop(d: DragObject): Boolean = d.dragInfo != null

    override fun onDrop(d: DragObject) {
        val info: ItemInfo = d.dragInfo ?: return
        placementHandler?.onItemRemoved(info)
    }

    override fun onDragEnter(d: DragObject) = setHighlighted(true)

    override fun onDragOver(d: DragObject) = Unit

    override fun onDragExit(d: DragObject) = setHighlighted(false)

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        val dragLayer = parent as? DragLayer ?: run {
            outRect.set(left, top, right, bottom)
            return
        }
        dragLayer.getDescendantRectRelativeToSelf(this, outRect)
    }

}
