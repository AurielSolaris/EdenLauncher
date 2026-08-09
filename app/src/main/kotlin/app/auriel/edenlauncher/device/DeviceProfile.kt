package app.auriel.edenlauncher.device

import android.content.Context
import android.graphics.Rect
import app.auriel.edenlauncher.R
import kotlin.math.max

/**
 * Orientation-dependent metrics derived from an [InvariantDeviceProfile]: cell sizes, workspace
 * padding, hotseat height.
 *
 * Ported from `DeviceProfile` (AOSP 7), trimmed to what the workspace, hotseat, and page indicator
 * actually read. Everything is computed once per orientation and then only mutated when window
 * insets arrive, so layout passes stay allocation-free.
 */
class DeviceProfile(
    context: Context,
    @JvmField val inv: InvariantDeviceProfile,
    widthDp: Float,
    heightDp: Float,
    @JvmField val isLandscape: Boolean,
) {

    private val dm = context.resources.displayMetrics
    private val res = context.resources

    @JvmField val widthPx: Int = pxFromDp(widthDp, dm)
    @JvmField val heightPx: Int = pxFromDp(heightDp, dm)

    /** Window insets (status bar, navigation bar, cutout) applied by the drag layer. */
    @JvmField val insets = Rect()

    @JvmField val iconSizePx: Int = pxFromDp(inv.iconSizeDp, dm)
    @JvmField val iconTextSizePx: Int = pxFromSp(inv.iconTextSizeDp, dm)
    @JvmField val iconDrawablePaddingPx: Int = res.getDimensionPixelSize(R.dimen.dynamic_grid_icon_drawable_padding)
    @JvmField val hotseatIconSizePx: Int = pxFromDp(inv.hotseatIconSizeDp, dm)

    @JvmField val edgeMarginPx: Int = res.getDimensionPixelSize(R.dimen.dynamic_grid_edge_margin)
    @JvmField val pageIndicatorSizePx: Int = res.getDimensionPixelSize(R.dimen.dynamic_grid_page_indicator_height)
    private val hotseatBarPaddingPx = res.getDimensionPixelSize(R.dimen.dynamic_grid_hotseat_padding)

    /** Total height (portrait) or width (landscape) reserved for the dock. */
    @JvmField val hotseatBarSizePx: Int = hotseatIconSizePx + 2 * hotseatBarPaddingPx

    /** Space a single workspace cell occupies, icon plus label plus padding. */
    var cellWidthPx: Int = 0
        private set

    var cellHeightPx: Int = 0
        private set

    /** Padding around the grid inside a workspace page. */
    @JvmField val workspacePadding = Rect()

    /** Usable area after insets and the hotseat are removed. */
    var availableWidthPx: Int = 0
        private set

    var availableHeightPx: Int = 0
        private set

    init {
        updateAvailableDimensions()
    }

    /**
     * Applies new window insets and recomputes derived sizes. Called from the drag layer's
     * insets listener rather than at construction, because insets are not known that early.
     */
    fun setInsets(newInsets: Rect) {
        if (insets == newInsets) return
        insets.set(newInsets)
        updateAvailableDimensions()
    }

    private fun updateAvailableDimensions() {
        availableWidthPx = widthPx - insets.left - insets.right
        availableHeightPx = heightPx - insets.top - insets.bottom

        // A cell is the icon, the drawable padding, and one line of label. The label is measured
        // as 1.5x its text size, which is what AOSP assumes for a single line with descenders.
        val labelHeightPx = (iconTextSizePx * 1.5f).toInt()
        cellHeightPx = iconSizePx + iconDrawablePaddingPx + labelHeightPx
        cellWidthPx = iconSizePx + 2 * iconDrawablePaddingPx

        updateWorkspacePadding()
    }

    /**
     * The workspace keeps the hotseat and page indicator out of its own bounds, so items never
     * land underneath them.
     */
    private fun updateWorkspacePadding() {
        val padding = workspacePadding
        if (isLandscape) {
            // Landscape puts the dock on the right edge (or the left in RTL, handled by the view).
            padding.set(edgeMarginPx, edgeMarginPx, hotseatBarSizePx, edgeMarginPx)
        } else {
            val bottom = hotseatBarSizePx + pageIndicatorSizePx
            val horizontal = max(
                edgeMarginPx,
                (availableWidthPx - inv.numColumns * cellWidthPx) / 2,
            )
            padding.set(horizontal, edgeMarginPx, horizontal, bottom)
        }
    }

    /** Width available to the grid inside one page. */
    val workspaceCellAreaWidthPx: Int
        get() = availableWidthPx - workspacePadding.left - workspacePadding.right

    /** Height available to the grid inside one page. */
    val workspaceCellAreaHeightPx: Int
        get() = availableHeightPx - workspacePadding.top - workspacePadding.bottom
}
