package app.auriel.edenlauncher.allapps

import android.view.View
import app.auriel.edenlauncher.model.AppInfo

/**
 * Contract shared by the two drawer styles.
 *
 * The point of this interface is the plan's core promise: navigation style is the user's choice,
 * not the launcher's. [AppDrawerVertical] and [AppDrawerHorizontal] present the same list, the
 * same filter, and the same click and long-press behaviour; only the gesture differs.
 */
interface AppDrawerView {

    /** The view to attach to the container. */
    val asView: View

    /** Replaces the displayed list. Called on bind and after every filter change. */
    fun submitApps(apps: List<AppInfo>)

    /** Returns the drawer to its start (top of the list, or the first page). */
    fun resetScroll()

    /**
     * Re-reads the icon off every entry currently on screen.
     *
     * Called while the icon pack is being applied over a drawer that is already built. Deliberately
     * not a resubmit: the list, the filter and the scroll position have not changed, only the
     * bitmaps, and rebuilding would throw away where the user was.
     */
    fun refreshIcons()

    /**
     * True when a further downward drag has nothing left to scroll.
     *
     * The container asks before it takes a gesture over for pull-to-close. Answering true while
     * the list can still scroll would dismiss the drawer in the middle of a fling.
     */
    val isScrolledToTop: Boolean

    var onAppClick: ((AppInfo) -> Unit)?

    /** Long press starts a drag out of the drawer onto the workspace. */
    var onAppLongClick: ((AppInfo, View) -> Unit)?
}
