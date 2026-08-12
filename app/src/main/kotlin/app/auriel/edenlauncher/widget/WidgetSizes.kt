package app.auriel.edenlauncher.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import app.auriel.edenlauncher.device.DeviceProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turning a provider's declared size into a number of grid cells, and telling a hosted widget how
 * big it actually ended up.
 *
 * A provider states its size in two incompatible ways depending on how old it is. Since API 31 it
 * can say what it wants in cells directly ([AppWidgetProviderInfo.targetCellWidth]), which is what
 * it means and needs no translation. Everything older states pixels, and the launcher has to work
 * back from that to cells against its own grid - so the same widget occupies a different number of
 * cells on a 4-column grid than on a 6-column one, which is correct and is what the user chose.
 */
object WidgetSizes {

    /**
     * The size this provider would like, in cells, clamped to the grid.
     *
     * Never returns zero: a provider that declares nothing still has to occupy something, and a
     * span of zero would place a widget that cannot be seen or picked back up.
     */
    fun defaultSpan(info: AppWidgetProviderInfo, profile: DeviceProfile): IntArray =
        spanFrom(info.minWidth, info.minHeight, targetCells(info), profile)

    /**
     * The smallest this provider may be resized to, in cells.
     *
     * `minResizeWidth` is optional and providers that omit it leave it at zero, which would say
     * "any size at all" - so it falls back to the declared minimum, which is the real floor.
     */
    fun minSpan(info: AppWidgetProviderInfo, profile: DeviceProfile): IntArray {
        val width = if (info.minResizeWidth in 1 until info.minWidth) info.minResizeWidth else info.minWidth
        val height = if (info.minResizeHeight in 1 until info.minHeight) info.minResizeHeight else info.minHeight
        return spanFrom(width, height, null, profile)
    }

    /**
     * The largest this provider may be resized to, in cells, or the whole grid when it says
     * nothing. `maxResizeWidth` only exists from API 31, and most providers never set it.
     */
    fun maxSpan(info: AppWidgetProviderInfo, profile: DeviceProfile): IntArray {
        val columns = profile.inv.numColumns
        val rows = profile.inv.numRows
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return intArrayOf(columns, rows)

        val width = info.maxResizeWidth
        val height = info.maxResizeHeight
        return intArrayOf(
            if (width > 0) min(columns, cellsFor(width, profile.cellWidthPx)) else columns,
            if (height > 0) min(rows, cellsFor(height, profile.cellHeightPx)) else rows,
        )
    }

    /** Which directions the provider allows resizing in, as a [AppWidgetProviderInfo] bitmask. */
    fun isResizable(info: AppWidgetProviderInfo): Boolean = info.resizeMode != 0

    /**
     * Tells the widget what size it is now, so its provider can pick the right layout.
     *
     * The API for this changed in 31: a widget can now be handed the list of sizes it might be
     * shown at rather than a min/max box, which is what lets a provider ship one layout per size
     * instead of guessing from a range. Both are called with the same single size here, because
     * Eden shows a widget at exactly one size at a time.
     */
    fun applySize(view: AppWidgetHostView, context: Context, widthPx: Int, heightPx: Int) {
        val widthDp = toDp(context, widthPx)
        val heightDp = toDp(context, heightPx)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(android.os.Bundle(), listOf(SizeF(widthDp, heightDp)))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(
                null,
                widthDp.toInt(),
                heightDp.toInt(),
                widthDp.toInt(),
                heightDp.toInt(),
            )
        }
    }

    /**
     * True when a provider cannot fit the current grid at all.
     *
     * Worth knowing before the picker offers it: binding a widget that will not fit ends in an
     * allocated id, a permission prompt, and then nowhere to put the result.
     */
    fun fitsGrid(info: AppWidgetProviderInfo, profile: DeviceProfile): Boolean {
        val span = minSpan(info, profile)
        return span[0] <= profile.inv.numColumns && span[1] <= profile.inv.numRows
    }

    private fun spanFrom(
        widthPx: Int,
        heightPx: Int,
        targetCells: IntArray?,
        profile: DeviceProfile,
    ): IntArray {
        val columns = profile.inv.numColumns
        val rows = profile.inv.numRows

        // A provider that stated cells outright means it; there is nothing to work back from.
        if (targetCells != null) {
            return intArrayOf(
                targetCells[0].coerceIn(1, columns),
                targetCells[1].coerceIn(1, rows),
            )
        }

        return intArrayOf(
            cellsFor(widthPx, profile.cellWidthPx).coerceIn(1, columns),
            cellsFor(heightPx, profile.cellHeightPx).coerceIn(1, rows),
        )
    }

    private fun targetCells(info: AppWidgetProviderInfo): IntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val x = info.targetCellWidth
        val y = info.targetCellHeight
        return if (x > 0 && y > 0) intArrayOf(x, y) else null
    }

    private fun cellsFor(sizePx: Int, cellPx: Int): Int {
        if (cellPx <= 0) return 1
        return max(1, ceil(sizePx.toDouble() / cellPx).toInt())
    }

    private fun toDp(context: Context, px: Int): Float =
        px / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics)
}
