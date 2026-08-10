package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.auriel.edenlauncher.R

/**
 * The little menu that appears when an icon is held but not dragged.
 *
 * Ported in spirit from `PopupContainerWithArrow` (AOSP 8), which is where Launcher3 moved
 * per-icon actions once long-press-to-drag stopped being the only thing a long press could mean.
 *
 * It is anchored to the icon rather than centred like a dialog, and it lives inside the drag layer
 * rather than becoming a real window. Both for the same reason: this is a menu *about that icon*,
 * and losing the connection to which icon you pressed is how these end up feeling like a system
 * dialog that arrived from nowhere.
 *
 * The whole popup fills the drag layer and catches taps outside the panel itself, rather than
 * relying on the drag layer's touch-complete hook. That hook fires on the `ACTION_UP` of the very
 * long press that opened the menu, so using it made the popup appear and vanish inside one
 * gesture. A scrim added mid-gesture receives no part of the gesture already in flight, which is
 * exactly the behaviour wanted here.
 */
class IconOptionsPopup(context: Context) : FrameLayout(context) {

    /** One row. A disabled row is still shown, greyed - see [addRow]. */
    private data class Row(val labelRes: Int, val enabled: Boolean, val onClick: () -> Unit)

    private val rows = ArrayList<Row>(6)
    private var onDismissed: (() -> Unit)? = null
    private var dismissing = false

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.icon_options_background)
        elevation = context.resources.displayMetrics.density * 8f
        val padding = context.resources.getDimensionPixelSize(R.dimen.icon_options_row_padding)
        setPadding(0, padding, 0, padding)
    }

    init {
        addView(
            panel,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START },
        )
    }

    fun addAction(labelRes: Int, enabled: Boolean = true, onClick: () -> Unit) = apply {
        rows.add(Row(labelRes, enabled, onClick))
    }

    /** A tap anywhere off the panel closes the menu, as every menu on the platform does. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dismiss()
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * Places the popup next to [anchor] inside [dragLayer].
     *
     * Prefers to sit below the icon, flips above when there is no room, and is clamped to the
     * layer so a popup on an edge icon never runs off the screen.
     */
    fun showAt(dragLayer: DragLayer, anchor: View, onDismissed: () -> Unit) {
        this.onDismissed = onDismissed
        buildRows()

        dragLayer.addView(
            this,
            InsettableFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        dragLayer.setTopDrawnChild(this)

        // Positioned after a layout pass: the panel's size depends on its longest label, and the
        // anchor's position in the drag layer is not known until both have been laid out.
        post { positionPanel(dragLayer, anchor) }
    }

    private fun positionPanel(dragLayer: DragLayer, anchor: View) {
        val anchorRect = Rect()
        dragLayer.getDescendantRectRelativeToSelf(anchor, anchorRect)

        val panelWidth = panel.width
        val panelHeight = panel.height
        if (panelWidth == 0 || panelHeight == 0) return

        val margin = (resources.displayMetrics.density * 8f).toInt()
        val below = anchorRect.bottom + margin
        val above = anchorRect.top - panelHeight - margin
        val y = if (below + panelHeight <= height) below else above.coerceAtLeast(margin)

        val x = (anchorRect.centerX() - panelWidth / 2)
            .coerceIn(margin, (width - panelWidth - margin).coerceAtLeast(margin))

        panel.translationX = x.toFloat()
        panel.translationY = y.toFloat()

        // Grows towards the icon it belongs to, which is what makes it read as belonging to that
        // icon rather than merely appearing over it.
        panel.alpha = 0f
        panel.scaleX = 0.85f
        panel.scaleY = 0.85f
        panel.pivotX = (anchorRect.centerX() - x).toFloat().coerceIn(0f, panelWidth.toFloat())
        panel.pivotY = if (y == below) 0f else panelHeight.toFloat()
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ENTER_DURATION_MS).start()
    }

    private fun buildRows() {
        panel.removeAllViews()
        for (row in rows) addRow(row)
    }

    /**
     * A disabled row is shown rather than hidden.
     *
     * Icon packs are not implemented yet, and leaving the row out entirely would leave people
     * hunting for a feature they have seen in other launchers with no way to tell whether it
     * exists here. A greyed row that says so answers the question.
     */
    private fun addRow(row: Row) {
        val view = TextView(context).apply {
            text = context.getString(row.labelRes)
            setTextColor(
                context.getColor(
                    if (row.enabled) R.color.settings_text else R.color.settings_text_secondary,
                ),
            )
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            isClickable = row.enabled
            val padH = resources.getDimensionPixelSize(R.dimen.icon_options_padding)
            val padV = resources.getDimensionPixelSize(R.dimen.icon_options_row_padding)
            setPadding(padH, padV, padH, padV)
            if (row.enabled) {
                setOnClickListener {
                    dismiss()
                    row.onClick()
                }
            }
        }
        panel.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun dismiss() {
        if (dismissing) return
        dismissing = true

        val parent = parent as? ViewGroup
        onDismissed?.invoke()
        onDismissed = null

        if (parent == null) return
        (parent as? DragLayer)?.setTopDrawnChild(null)

        panel.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(EXIT_DURATION_MS)
            .withEndAction { parent.removeView(this) }
            .start()
    }

    private companion object {
        const val ENTER_DURATION_MS = 140L
        const val EXIT_DURATION_MS = 110L
    }
}
