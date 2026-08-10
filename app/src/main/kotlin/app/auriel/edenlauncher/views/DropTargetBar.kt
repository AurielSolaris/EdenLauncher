package app.auriel.edenlauncher.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import app.auriel.edenlauncher.model.ItemInfo

/**
 * The bar of drop targets that appears at the top of the screen during a drag.
 *
 * Ported from `DropTargetBar` (AOSP 7). It holds Remove and Uninstall side by side and does
 * nothing else: the buttons are the drop targets, and the launcher registers them individually
 * with the drag controller.
 */
class DropTargetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    val removeTarget = ButtonDropTarget(context).apply { mode = ButtonDropTarget.Mode.REMOVE }
    val uninstallTarget = ButtonDropTarget(context).apply { mode = ButtonDropTarget.Mode.UNINSTALL }

    init {
        orientation = HORIZONTAL
        visibility = GONE

        // Equal halves, so neither is the one you hit by accident.
        addView(removeTarget, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(uninstallTarget, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    /** Shown only while a drag is running, as in AOSP. */
    fun setDragInProgress(inProgress: Boolean, info: ItemInfo? = null) {
        visibility = if (inProgress) VISIBLE else GONE
        removeTarget.onDragStart(if (inProgress) info else null)
        uninstallTarget.onDragStart(if (inProgress) info else null)
    }
}
