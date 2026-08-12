package app.auriel.edenlauncher.folder

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.dragndrop.DragObject
import app.auriel.edenlauncher.dragndrop.DropTarget
import app.auriel.edenlauncher.dragndrop.ItemPlacementHandler
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.views.BubbleTextView
import app.auriel.edenlauncher.views.CellLayout
import app.auriel.edenlauncher.views.DragLayer
import app.auriel.edenlauncher.views.InsettableFrameLayout

/**
 * The opened folder: a titled panel of icons floating above the workspace.
 *
 * Ported from `Folder` (AOSP 7), reduced to the parts that make a folder a folder - open, close,
 * rename, add, remove, and accept a drop. AOSP's multi-page folders and the icon-to-folder morph
 * animation are not here; the folder opens with a scale-and-fade instead.
 */
class Folder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), DropTarget, FolderInfo.Listener {

    var folderInfo: FolderInfo? = null
        private set

    var placementHandler: ItemPlacementHandler? = null

    /** Invoked when the folder finishes closing, so the launcher can drop its reference. */
    var onClosed: (() -> Unit)? = null

    /** Called when an icon inside the folder is tapped. */
    var onItemClick: ((ShortcutInfo) -> Unit)? = null

    private val titleView: EditText
    private val content: CellLayout
    private val tmpCell = IntArray(2)

    var isOpen: Boolean = false
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipToPadding = false
        isFocusable = true

        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.folder_corner_radius)
            setColor(context.getColor(R.color.folder_background))
        }

        val padding = resources.getDimensionPixelSize(R.dimen.folder_padding)
        setPadding(padding, padding, padding, padding)

        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile

        titleView = EditText(context).apply {
            setSingleLine()
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.settings_text))
            setBackgroundResource(0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isFocusableInTouchMode = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitTitle()
                    true
                } else {
                    false
                }
            }
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content = CellLayout(context).apply {
            setGridSize(idp.numFolderColumns, idp.numFolderRows)
        }
        addView(
            content,
            LayoutParams(
                idp.numFolderColumns * folderCellWidth(),
                idp.numFolderRows * folderCellHeight(),
            ),
        )
    }

    private fun profile() = LauncherAppState.getInstance(context).invariantDeviceProfile.profileFor(
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun folderCellWidth(): Int {
        val profile = profile()
        val prefs = LauncherAppState.getInstance(context).preferences
        return profile.cellWidthPx
            .coerceAtLeast(profile.iconSizePx + dp(prefs.iconPaddingHorizontalDp) * 2)
    }

    /**
     * A folder cell is as tall as the icon it holds *plus its label*, not square.
     *
     * It used to be square, sized from the workspace cell's width. A cell is wider than an icon but
     * taller still - the height has to carry the icon, the gap under it, one line of label, and the
     * vertical padding the user can set - so squaring it off left no room for the label at all.
     * Every app in an open folder showed as bare artwork with no name, while the same icon on the
     * home screen was labelled.
     *
     * Measured from the same numbers [app.auriel.edenlauncher.views.BubbleTextView] lays itself out
     * with rather than from `cellHeightPx`, because that figure leaves out the icon padding
     * preference and the workspace only gets away with it by dividing the page height between rows.
     */
    private fun folderCellHeight(): Int {
        val profile = profile()
        val prefs = LauncherAppState.getInstance(context).preferences
        val labelHeight = (profile.iconTextSizePx * LABEL_LINE_FACTOR).toInt()
        return profile.iconSizePx +
            dp(prefs.iconLabelSpacingDp) +
            labelHeight +
            dp(prefs.iconPaddingVerticalDp) * 2
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- open / close ---------------------------------------------------------------------------

    /** Binds [info] and animates the panel in, centred in [dragLayer]. */
    fun open(info: FolderInfo, dragLayer: DragLayer) {
        folderInfo?.removeListener(this)
        folderInfo = info
        info.addListener(this)
        info.isOpen = true

        titleView.setText(info.title ?: resources.getString(R.string.folder_name))
        rebuildContents()

        if (parent == null) {
            val lp = InsettableFrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER }
            dragLayer.addView(this, lp)
        }

        isOpen = true
        visibility = VISIBLE
        alpha = 0f
        scaleX = OPEN_START_SCALE
        scaleY = OPEN_START_SCALE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(OPEN_DURATION_MS)
            .start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        folderInfo?.isOpen = false
        commitTitle()

        animate()
            .alpha(0f)
            .scaleX(OPEN_START_SCALE)
            .scaleY(OPEN_START_SCALE)
            .setDuration(CLOSE_DURATION_MS)
            .withEndAction {
                visibility = GONE
                (parent as? DragLayer)?.removeView(this)
                folderInfo?.removeListener(this)
                folderInfo = null
                onClosed?.invoke()
            }
            .start()
    }

    private fun commitTitle() {
        val info = folderInfo ?: return
        val newTitle = titleView.text?.toString()?.trim().orEmpty()
        if (newTitle.isNotEmpty() && newTitle != info.title?.toString()) {
            info.rename(newTitle)
            placementHandler?.onItemPlaced(
                info = info,
                container = info.container,
                screenId = info.screenId,
                cellX = info.cellX,
                cellY = info.cellY,
                rank = info.rank,
            )
        }
    }

    // ---- contents ---------------------------------------------------------------------------------

    private fun rebuildContents() {
        content.removeAllViews()
        val info = folderInfo ?: return

        // The panel is as tall as it needs to be, not as tall as the folder could ever get. A
        // folder holding three apps used to open a full-height grid of empty cells under them.
        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        val columns = idp.numFolderColumns
        val rows = ((info.contents.size + columns - 1) / columns).coerceIn(1, idp.numFolderRows)
        content.setGridSize(columns, rows)
        content.layoutParams = LayoutParams(columns * folderCellWidth(), rows * folderCellHeight())

        for ((index, item) in info.contents.withIndex()) {
            if (!content.findVacantCell(tmpCell)) break
            val icon = BubbleTextView(context).apply {
                applyFrom(item, item.icon)
                setOnClickListener { onItemClick?.invoke(item) }
            }
            item.rank = index
            content.addItem(icon, -1, CellLayout.LayoutParams(tmpCell[0], tmpCell[1], 1, 1))
        }
    }

    override fun onAdd(item: ShortcutInfo) = rebuildContents()

    override fun onRemove(item: ShortcutInfo) {
        rebuildContents()
        // A folder with nothing left in it is noise; the launcher deletes it.
        val info = folderInfo ?: return
        if (info.contents.isEmpty()) {
            placementHandler?.onItemRemoved(info)
            close()
        }
    }

    override fun onTitleChanged(title: CharSequence?) {
        if (titleView.text?.toString() != title?.toString()) titleView.setText(title)
    }

    override fun onItemsChanged() = rebuildContents()

    // ---- drop target ------------------------------------------------------------------------------

    override val isDropEnabled: Boolean get() = isOpen

    override fun acceptDrop(d: DragObject): Boolean = isOpen && d.dragInfo is ShortcutInfo

    override fun onDrop(d: DragObject) {
        val item = d.dragInfo as? ShortcutInfo ?: return
        val info = folderInfo ?: return
        placementHandler?.onItemAddedToFolder(info, item)
    }

    override fun onDragEnter(d: DragObject) = Unit

    override fun onDragOver(d: DragObject) = Unit

    override fun onDragExit(d: DragObject) = Unit

    override fun getHitRectRelativeToDragLayer(outRect: Rect) {
        val dragLayer = parent as? DragLayer ?: run {
            outRect.set(left, top, right, bottom)
            return
        }
        dragLayer.getDescendantRectRelativeToSelf(this, outRect)
    }

    /** True when [x], [y] (drag-layer coordinates) fall inside the open panel. */
    fun containsPoint(x: Int, y: Int, out: Rect): Boolean {
        getHitRectRelativeToDragLayer(out)
        return out.contains(x, y)
    }

    private companion object {
        /** One line with descenders, the same figure `DeviceProfile` measures a label with. */
        const val LABEL_LINE_FACTOR = 1.5f

        const val OPEN_START_SCALE = 0.85f
        const val OPEN_DURATION_MS = 160L
        const val CLOSE_DURATION_MS = 120L
    }
}
