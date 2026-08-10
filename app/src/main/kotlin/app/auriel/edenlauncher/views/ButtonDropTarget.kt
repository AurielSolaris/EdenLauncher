package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.dragndrop.DragObject
import app.auriel.edenlauncher.dragndrop.DropTarget
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ShortcutInfo

/**
 * One button in the bar that slides in at the top of the screen during a drag.
 *
 * Ported from `ButtonDropTarget` (AOSP 7). Two of these sit side by side: Remove takes the icon
 * off the home screen, Uninstall takes the app off the phone. Keeping them as separate targets
 * rather than one button with a modifier is deliberate - they differ by whether the thing is
 * recoverable, and that distinction should be visible before you let go, not confirmed afterwards.
 */
class ButtonDropTarget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr), DropTarget {

    enum class Mode { REMOVE, UNINSTALL }

    var mode: Mode = Mode.REMOVE
        set(value) {
            field = value
            text = context.getString(
                when (value) {
                    Mode.REMOVE -> R.string.remove_drop_target_label
                    Mode.UNINSTALL -> R.string.uninstall_drop_target_label
                },
            )
        }

    /** Invoked when something is dropped here. */
    var onDropped: ((ItemInfo) -> Unit)? = null

    private var isHighlighted = false
    private var acceptsCurrentDrag = true

    private val idleColor = context.getColor(R.color.drop_target_idle)
    private val activeColor = context.getColor(
        if (mode == Mode.UNINSTALL) R.color.drop_target_uninstall else R.color.drop_target_active,
    )

    init {
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.settings_text))
        setBackgroundColor(idleColor)
        mode = Mode.REMOVE
    }

    /**
     * Called when a drag starts, so the button can grey itself out for things it cannot act on.
     *
     * Uninstall applies to an app, not to a folder or a widget, and a target that silently ignores
     * a drop is worse than one that visibly will not take it.
     */
    fun onDragStart(info: ItemInfo?) {
        acceptsCurrentDrag = when (mode) {
            Mode.REMOVE -> info != null
            Mode.UNINSTALL -> uninstallablePackage(info) != null
        }
        alpha = if (acceptsCurrentDrag) 1f else DISABLED_ALPHA
    }

    private fun setHighlighted(highlighted: Boolean) {
        if (isHighlighted == highlighted) return
        isHighlighted = highlighted
        setBackgroundColor(if (highlighted) activeColor else idleColor)
    }

    override val isDropEnabled: Boolean
        get() = visibility == VISIBLE && acceptsCurrentDrag

    override fun acceptDrop(d: DragObject): Boolean = acceptsCurrentDrag && d.dragInfo != null

    override fun onDrop(d: DragObject) {
        val info = d.dragInfo ?: return
        onDropped?.invoke(info)
    }

    override fun onDragEnter(d: DragObject) = setHighlighted(true)

    override fun onDragOver(d: DragObject) = Unit

    override fun onDragExit(d: DragObject) = setHighlighted(false)

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        val dragLayer = rootDragLayer() ?: run {
            outRect.set(left, top, right, bottom)
            return
        }
        dragLayer.getDescendantRectRelativeToSelf(this, outRect)
    }

    private fun rootDragLayer(): DragLayer? {
        var view = parent
        while (view != null) {
            if (view is DragLayer) return view
            view = view.parent
        }
        return null
    }

    companion object {
        private const val DISABLED_ALPHA = 0.3f

        /**
         * The package this item belongs to, or null if it is not something that can be uninstalled.
         *
         * Folders and widgets are not apps. A deep shortcut belongs to an app, but dragging a
         * shortcut to Uninstall meaning "remove the whole app" is a trap, so those are excluded
         * too - Remove is what that gesture means.
         */
        fun uninstallablePackage(info: ItemInfo?): String? {
            if (info == null || info is FolderInfo) return null
            val shortcut = info as? ShortcutInfo ?: return null
            if (shortcut.isDeepShortcut) return null
            return info.intent?.component?.packageName
        }
    }
}
