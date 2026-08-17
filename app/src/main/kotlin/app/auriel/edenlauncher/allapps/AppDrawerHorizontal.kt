package app.auriel.edenlauncher.allapps

import android.content.Context
import android.view.View
import android.view.ViewGroup
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.views.BubbleTextView
import app.auriel.edenlauncher.views.CellLayout
import app.auriel.edenlauncher.views.PagedView

/**
 * Fixed grid pages, swiped left and right.
 *
 * The classic style (Samsung One UI, original AOSP Launcher3). Built on the same [PagedView] the
 * workspace uses, so paging feel is identical across the launcher rather than a second
 * implementation that drifts.
 *
 * Pages are rebuilt on every [submitApps]. That is a real cost with a few hundred apps, but it
 * happens on a filter change, not while scrolling, and the alternative - recycling across pages -
 * would mean reimplementing what [PagedView] deliberately does not do.
 */
class AppDrawerHorizontal(context: Context) : PagedView(context), AppDrawerView {

    private val appState = LauncherAppState.getInstance(context)
    private val idp = appState.invariantDeviceProfile
    private val columns = appState.preferences.drawerColumns.takeIf { it > 0 } ?: idp.numColumns
    private val rows = idp.numRows
    private val perPage = columns * rows

    private val cell = IntArray(2)

    init {
        centerPagesVertically = false
        clipToPadding = false
    }

    override val asView: View get() = this

    override var onAppClick: ((AppInfo) -> Unit)? = null

    override var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    override fun submitApps(apps: List<AppInfo>) {
        removeAllViews()
        if (apps.isEmpty()) return

        var index = 0
        while (index < apps.size) {
            val page = CellLayout(context).apply {
                setGridSize(columns, rows)
                layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            addView(page)

            val end = minOf(index + perPage, apps.size)
            while (index < end) {
                val app = apps[index]
                if (!page.findVacantCell(cell)) break
                val icon = BubbleTextView(context).apply {
                    applyFromApp(app)
                    setOnClickListener { onAppClick?.invoke(app) }
                    setOnLongClickListener {
                        onAppLongClick?.invoke(app, this)
                        true
                    }
                }
                page.addItem(icon, -1, CellLayout.LayoutParams(cell[0], cell[1], 1, 1))
                index++
            }
        }
        setCurrentPage(0)
    }

    override fun resetScroll() = setCurrentPage(0)

    /**
     * Every page is built up front here, so every entry is walked - there is no recycling to pick
     * up a changed bitmap later.
     */
    override fun refreshIcons() {
        for (p in 0 until childCount) {
            val page = getChildAt(p) as? CellLayout ?: continue
            for (i in 0 until page.childCount) {
                val icon = page.getChildAt(i) as? BubbleTextView ?: continue
                (icon.itemInfo as? AppInfo)?.let { icon.setIconBitmap(it.icon) }
            }
        }
    }

    /**
     * Always false: pull-to-close is a vertical gesture, and this drawer's whole gesture vocabulary
     * is horizontal. Offering it here would mean a downward drag doing something different in the
     * two modes for no reason the user chose.
     */
    override val isScrolledToTop: Boolean get() = false

    override fun getEdgeVerticalPosition(out: IntArray) {
        out[0] = 0
        out[1] = height
    }
}
