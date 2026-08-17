package app.auriel.edenlauncher.allapps

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.views.BubbleTextView

/**
 * Continuous vertical grid: fling up and down through every app, no pages.
 *
 * The modern style (ASUS ZenUI, current Pixel Launcher). Backed by a [RecyclerView] so a device
 * with 300 apps still only holds a screenful of views.
 */
class AppDrawerVertical(context: Context) : AppDrawerView {

    private val columns = LauncherAppState.getInstance(context).let { state ->
        state.preferences.drawerColumns.takeIf { it > 0 } ?: state.invariantDeviceProfile.numColumns
    }

    private val adapter = AppListAdapter(
        apps = emptyList(),
        onClick = { onAppClick?.invoke(it) },
        onLongClick = { app, view -> onAppLongClick?.invoke(app, view) },
    )

    private val recyclerView = RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, columns)
        adapter = this@AppDrawerVertical.adapter
        setHasFixedSize(true)
        // Padding outside the scroll area, so the first and last rows are not flush with the edge
        // but still scroll under it.
        setPadding(
            resources.getDimensionPixelSize(R.dimen.all_apps_grid_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.all_apps_grid_padding_vertical),
            resources.getDimensionPixelSize(R.dimen.all_apps_grid_padding_horizontal),
            resources.getDimensionPixelSize(R.dimen.all_apps_grid_padding_vertical),
        )
        clipToPadding = false
        // The list is a flat grid of equally sized items, so recycling never changes item height.
        itemAnimator = null
        // A larger cache costs a few hundred KB and removes most inflation during a fling.
        setItemViewCacheSize(columns * 2)
        isVerticalScrollBarEnabled = true
    }

    override val asView: View get() = recyclerView

    override var onAppClick: ((AppInfo) -> Unit)? = null

    override var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    override fun submitApps(apps: List<AppInfo>) = adapter.submit(apps)

    override fun resetScroll() = recyclerView.scrollToPosition(0)

    /**
     * Only the attached views are touched. Everything else is off screen and will pick the new
     * bitmap up from its [AppInfo] the next time it is bound, which recycling guarantees.
     */
    override fun refreshIcons() {
        for (i in 0 until recyclerView.childCount) {
            val icon = recyclerView.getChildAt(i) as? BubbleTextView ?: continue
            (icon.itemInfo as? AppInfo)?.let { icon.setIconBitmap(it.icon) }
        }
    }

    /**
     * Asked of the RecyclerView itself rather than tracked alongside it. The list is the authority
     * on whether it has anywhere left to go, and a shadow copy of that would be one more thing to
     * keep in step through filters, rebinds and configuration changes.
     */
    override val isScrolledToTop: Boolean
        get() = !recyclerView.canScrollVertically(-1)
}
