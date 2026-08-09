package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.dragndrop.DragObject
import app.auriel.edenlauncher.dragndrop.DropTarget
import app.auriel.edenlauncher.dragndrop.ItemPlacementHandler
import app.auriel.edenlauncher.model.Containers

/**
 * The dock: a single-row (or single-column, in landscape) [CellLayout] pinned below the workspace.
 *
 * Ported from `Hotseat` (AOSP 7). Rank-to-cell mapping lives here so the rest of the launcher can
 * treat a hotseat slot as an ordinal and stay orientation-agnostic.
 */
class Hotseat @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CellLayout(context, attrs, defStyleAttr), Insettable, DropTarget {

    private val isLandscape: Boolean
        get() = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    init {
        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        if (isLandscape) setGridSize(1, idp.numHotseatIcons) else setGridSize(idp.numHotseatIcons, 1)
    }

    /** Cell x for slot [rank]. In landscape the dock runs down the side, so x is always 0. */
    fun cellXForRank(rank: Int): Int = if (isLandscape) 0 else rank

    /**
     * Cell y for slot [rank]. In landscape ranks run bottom-to-top, matching the reading order a
     * user gets when they rotate the device.
     */
    fun cellYForRank(rank: Int): Int = if (isLandscape) countY - (rank + 1) else 0

    /** Inverse of [cellXForRank] / [cellYForRank]. */
    fun rankForCell(cellX: Int, cellY: Int): Int = if (isLandscape) countY - (cellY + 1) else cellX

    // ---- drop target ---------------------------------------------------------------------------

    var placementHandler: ItemPlacementHandler? = null

    private val tmpCell = IntArray(2)
    private val tmpPoint = IntArray(2)

    override val isDropEnabled: Boolean get() = true

    override fun acceptDrop(d: DragObject): Boolean = d.dragInfo != null

    override fun onDrop(d: DragObject) {
        val info = d.dragInfo ?: return
        val handler = placementHandler ?: return

        tmpPoint[0] = d.x
        tmpPoint[1] = d.y
        (parent as? DragLayer)?.mapCoordInSelfToDescendant(this, tmpPoint)

        // The dock is single-file, so the drop resolves to a rank, not a free-form cell.
        if (!findNearestVacantCell(tmpPoint[0], tmpPoint[1], 1, 1, tmpCell)) {
            d.cancelled = true
            return
        }

        handler.onItemPlaced(
            info = info,
            container = Containers.HOTSEAT,
            screenId = -1L,
            cellX = tmpCell[0],
            cellY = tmpCell[1],
            rank = rankForCell(tmpCell[0], tmpCell[1]),
        )
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

    override fun setInsets(insets: Rect) {
        val profile = LauncherAppState.getInstance(context)
            .invariantDeviceProfile
            .profileFor(isLandscape)
        profile.setInsets(insets)

        val lp = layoutParams
        if (isLandscape) {
            lp.width = profile.hotseatBarSizePx + insets.left + insets.right
            lp.height = MATCH_PARENT
            setPadding(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            lp.width = MATCH_PARENT
            lp.height = profile.hotseatBarSizePx + insets.bottom
            setPadding(insets.left, 0, insets.right, insets.bottom)
        }
        layoutParams = lp
        requestLayout()
    }

    private companion object {
        const val MATCH_PARENT = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
