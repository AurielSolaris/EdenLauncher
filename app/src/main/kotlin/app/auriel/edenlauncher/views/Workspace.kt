package app.auriel.edenlauncher.views

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.AttributeSet
import android.util.LongSparseArray
import android.view.MotionEvent
import android.view.View
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.device.DeviceProfile
import app.auriel.edenlauncher.dragndrop.DragObject
import app.auriel.edenlauncher.dragndrop.DragSource
import app.auriel.edenlauncher.dragndrop.DropTarget
import app.auriel.edenlauncher.dragndrop.ItemPlacementHandler
import app.auriel.edenlauncher.folder.FolderIcon
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.util.EdenLog

/**
 * The home screen: a [PagedView] whose pages are [CellLayout]s.
 *
 * Ported from `Workspace` (AOSP 7). It hosts pages, accepts drops onto them, and manages the page
 * list (add, remove, reorder), including AOSP's trailing empty screen that appears while dragging
 * so an item can be flicked onto a brand new page.
 */
class Workspace @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PagedView(context, attrs, defStyleAttr), Insettable, DropTarget, DragSource {

    /** Page id by index, left to right. Kept in step with the child order. */
    private val screenOrder = ArrayList<Long>(8)

    /** Page lookup by id, so binding an item does not scan the child list. */
    private val screens = LongSparseArray<CellLayout>(8)

    private val tmpCell = IntArray(2)
    private val tmpPoint = IntArray(2)
    private val tmpRect = Rect()

    private var deviceProfile: DeviceProfile = currentDeviceProfile()

    /** Persists placements; supplied by the launcher. */
    var placementHandler: ItemPlacementHandler? = null

    /** Set while a drag is in flight, so the trailing empty page is only shown then. */
    private var hasExtraEmptyScreen = false

    init {
        centerPagesVertically = false
        isChildrenDrawingOrderEnabled = false
        clipToPadding = false
    }

    private fun currentDeviceProfile(): DeviceProfile {
        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return idp.profileFor(landscape)
    }

    override fun setInsets(insets: Rect) {
        deviceProfile = currentDeviceProfile()
        deviceProfile.setInsets(insets)
        val padding = deviceProfile.workspacePadding
        setPadding(
            insets.left + padding.left,
            insets.top + padding.top,
            insets.right + padding.right,
            insets.bottom + padding.bottom,
        )
        requestLayout()
    }

    // ---- pages ---------------------------------------------------------------------------------

    /** Page ids in display order, excluding the transient empty screen. */
    val screenIds: List<Long>
        get() = if (hasExtraEmptyScreen) screenOrder.dropLast(1) else screenOrder.toList()

    fun getScreenWithId(screenId: Long): CellLayout? = screens.get(screenId)

    fun getIdForScreen(layout: CellLayout): Long {
        val index = indexOfChild(layout)
        return if (index in screenOrder.indices) screenOrder[index] else -1L
    }

    fun getScreenIndex(screenId: Long): Int = screenOrder.indexOf(screenId)

    val currentCellLayout: CellLayout?
        get() = getPageAt(currentPageIndex) as? CellLayout

    /**
     * Rebuilds the page list from persisted ids. Existing pages are dropped: this runs once per
     * bind, and reusing views across a full rebind would mean reconciling two orderings for no
     * measurable gain.
     */
    fun bindScreens(ids: List<Long>) {
        removeAllViews()
        screenOrder.clear()
        screens.clear()
        hasExtraEmptyScreen = false
        for (id in ids) insertNewScreen(id, childCount)
        if (childCount == 0) insertNewScreen(FIRST_SCREEN_ID, 0)
        setCurrentPage(0)
    }

    /** Creates an empty page for [screenId] at [index] and returns it. */
    fun insertNewScreen(screenId: Long, index: Int = childCount): CellLayout {
        screens.get(screenId)?.let { return it }

        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        val page = CellLayout(context).apply {
            setGridSize(idp.numColumns, idp.numRows)
            layoutParams = LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // A long press that no icon claimed is a press on empty workspace: the only route to the
        // launcher's own options.
        page.setOnLongClickListener {
            onEmptySpaceLongPress?.invoke()
            onEmptySpaceLongPress != null
        }

        val safeIndex = index.coerceIn(0, childCount)
        addView(page, safeIndex)
        screenOrder.add(safeIndex, screenId)
        screens.put(screenId, page)

        // A page created while overview is open has to arrive already looking like a card.
        if (isInOverview) {
            page.overviewProgress = 1f
            bindOverviewCallbacks()
        }
        return page
    }

    /** Removes a page and everything on it. Refuses to remove the last remaining page. */
    fun removeScreen(screenId: Long): Boolean {
        if (childCount <= 1) return false
        val page = screens.get(screenId) ?: return false
        removeView(page)
        screens.remove(screenId)
        screenOrder.remove(screenId)
        return true
    }

    /**
     * Moves the page at [from] to [to]. Both the child order and the id list move together, so
     * page ids keep pointing at the same content.
     */
    fun reorderScreen(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in screenOrder.indices || to !in screenOrder.indices) return false

        val page = getChildAt(from)
        removeViewAt(from)
        addView(page, to)
        screenOrder.add(to, screenOrder.removeAt(from))

        placementHandler?.onScreenOrderChanged(screenIds)
        return true
    }

    /** Deletes every page that holds no items, keeping at least one. Returns the ids removed. */
    fun removeEmptyScreens(): List<Long> {
        val removed = ArrayList<Long>(2)
        // Iterate over a copy: removeScreen mutates screenOrder.
        for (id in screenOrder.toList()) {
            if (childCount <= 1) break
            val page = screens.get(id) ?: continue
            if (page.childCount == 0 && removeScreen(id)) removed.add(id)
        }
        return removed
    }

    /**
     * Appends the "drop here for a new page" screen shown during a drag.
     *
     * Ported from `Workspace.addExtraEmptyScreen` (AOSP 7). It is not persisted: the id only
     * becomes real if something is dropped on it.
     */
    fun addExtraEmptyScreen() {
        if (hasExtraEmptyScreen) return
        insertNewScreen(EXTRA_EMPTY_SCREEN_ID, childCount)
        hasExtraEmptyScreen = true
    }

    /** Removes the transient page unless an item was dropped on it. */
    fun removeExtraEmptyScreen() {
        if (!hasExtraEmptyScreen) return
        val page = screens.get(EXTRA_EMPTY_SCREEN_ID)
        hasExtraEmptyScreen = false
        if (page != null && page.childCount == 0) {
            removeScreen(EXTRA_EMPTY_SCREEN_ID)
        }
    }

    /**
     * Turns the transient page into a real one and returns its new id, or -1 when there is
     * nothing to commit.
     */
    fun commitExtraEmptyScreen(newScreenId: Long): Long {
        val page = screens.get(EXTRA_EMPTY_SCREEN_ID) ?: return -1L
        screens.remove(EXTRA_EMPTY_SCREEN_ID)
        screens.put(newScreenId, page)

        val index = screenOrder.indexOf(EXTRA_EMPTY_SCREEN_ID)
        if (index >= 0) screenOrder[index] = newScreenId
        hasExtraEmptyScreen = false
        // Keep the page; a fresh transient screen is added if the drag continues.
        page.requestLayout()
        return newScreenId
    }

    val isShowingExtraEmptyScreen: Boolean get() = hasExtraEmptyScreen

    // ---- items ---------------------------------------------------------------------------------

    /**
     * Places [child] on the workspace according to [info].
     *
     * @return false when the target page is missing or the cells are out of range; the caller is
     *   expected to re-home the item rather than have it silently vanish.
     */
    fun addInScreen(child: View, info: ItemInfo): Boolean {
        if (info.container != Containers.DESKTOP) {
            EdenLog.e(TAG, "addInScreen called with container ${info.container}")
            return false
        }

        val page = screens.get(info.screenId) ?: run {
            EdenLog.e(TAG, "addInScreen: no page with id ${info.screenId}")
            return false
        }

        val lp = (child.layoutParams as? CellLayout.LayoutParams)
            ?: CellLayout.LayoutParams(info.cellX, info.cellY, info.spanX, info.spanY)
        lp.cellX = info.cellX
        lp.cellY = info.cellY
        lp.cellHSpan = info.spanX
        lp.cellVSpan = info.spanY

        return page.addItem(child, -1, lp)
    }

    /** Removes the view bound to [info], wherever it sits. */
    fun removeItem(info: ItemInfo): Boolean {
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? CellLayout ?: continue
            for (j in 0 until page.childCount) {
                val child = page.getChildAt(j)
                if (viewInfo(child) === info) {
                    page.removeView(child)
                    return true
                }
            }
        }
        return false
    }

    /**
     * Finds the first page with room for a [spanX] x [spanY] item, writing the free cell into
     * [outCell]. Returns the page id, or -1 when every page is full.
     */
    fun findFreeCell(outCell: IntArray, spanX: Int = 1, spanY: Int = 1): Long {
        for (i in screenOrder.indices) {
            val page = screens.get(screenOrder[i]) ?: continue
            if (page.findVacantCell(outCell, spanX, spanY)) return screenOrder[i]
        }
        return -1L
    }

    /** True when no page has room for a 1x1 item. */
    val isFull: Boolean
        get() = findFreeCell(tmpCell) == -1L

    // ---- drop target ---------------------------------------------------------------------------

    override val isDropEnabled: Boolean get() = true

    override fun acceptDrop(d: DragObject): Boolean = d.dragInfo != null && currentCellLayout != null

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

    /**
     * Resolves the drop.
     *
     * Order matters and mirrors AOSP: a drop onto an existing icon makes a folder, a drop onto a
     * folder adds to it, and anything else lands in the nearest free cell.
     */
    override fun onDrop(d: DragObject) {
        val info = d.dragInfo ?: return
        val page = currentCellLayout ?: return
        val handler = placementHandler ?: return
        val screenId = getIdForScreen(page)

        // Drag-layer coordinates -> page coordinates.
        tmpPoint[0] = d.x
        tmpPoint[1] = d.y
        (parent as? DragLayer)?.mapCoordInSelfToDescendant(page, tmpPoint)

        page.pointToCell(tmpPoint[0], tmpPoint[1], tmpCell)
        val existing = page.getChildAtCell(tmpCell[0], tmpCell[1])
        val existingInfo = existing?.let(::viewInfo)

        // Drop onto an open folder's icon: add to it.
        if (existing is FolderIcon && existingInfo is FolderInfo && info is ShortcutInfo) {
            handler.onItemAddedToFolder(existingInfo, info)
            return
        }

        // Drop onto another app: make a folder out of the pair.
        if (existingInfo is ShortcutInfo && info is ShortcutInfo && existingInfo !== info) {
            handler.onFolderCreationRequested(
                target = existingInfo,
                dropped = info,
                targetView = existing,
                container = Containers.DESKTOP,
                screenId = screenId,
                cellX = tmpCell[0],
                cellY = tmpCell[1],
            )
            return
        }

        // Plain move: nearest free cell to where the finger let go.
        if (!page.findNearestVacantCell(tmpPoint[0], tmpPoint[1], info.spanX, info.spanY, tmpCell)) {
            d.cancelled = true
            return
        }

        handler.onItemPlaced(
            info = info,
            container = Containers.DESKTOP,
            screenId = screenId,
            cellX = tmpCell[0],
            cellY = tmpCell[1],
            rank = 0,
        )
    }

    // ---- drag source ---------------------------------------------------------------------------

    override fun onDropCompleted(target: View?, d: DragObject, success: Boolean) {
        if (!success) {
            // Put the view back where it was; the model was never changed.
            d.originalView?.visibility = View.VISIBLE
        }
    }

    // ---- paging hooks --------------------------------------------------------------------------

    override fun getEdgeVerticalPosition(out: IntArray) {
        val page = getPageAt(pageCount - 1) ?: run {
            out[0] = 0
            out[1] = height
            return
        }
        out[0] = page.top
        out[1] = page.bottom
    }

    override fun onUnhandledTap(ev: MotionEvent) {
        // A tap on empty workspace does nothing yet; the settings sheet hooks in here.
    }

    // ---- swipe up to open the drawer -------------------------------------------------------------

    /** Invoked on an upward flick anywhere on the workspace. */
    var onSwipeUp: (() -> Unit)? = null

    /** Invoked on a long press on empty workspace space. */
    var onEmptySpaceLongPress: (() -> Unit)? = null

    // ---- overview ---------------------------------------------------------------------------------

    /**
     * Zoomed-out page management, ported from AOSP 7's `Workspace.State.OVERVIEW`.
     *
     * The workspace scales to [OVERVIEW_SCALE] and its pages become cards with badges: a house to
     * make a page home, an X to delete it. AOSP used the same 70% shrink and a button bar along the
     * bottom; Eden adds the home badge, which AOSP never had and every third-party launcher did.
     */
    var isInOverview: Boolean = false
        private set

    /** Called when a page card is tapped, so the launcher can leave overview on that page. */
    var onOverviewPageSelected: ((Int) -> Unit)? = null

    var onOverviewSetHome: ((Long) -> Unit)? = null
    var onOverviewRemovePage: ((Long) -> Unit)? = null

    /** Called when overview is entered or left, so chrome can follow. */
    var onOverviewChanged: ((Boolean) -> Unit)? = null

    fun enterOverview() {
        if (isInOverview) return
        isInOverview = true
        // Overscroll during overview would drag the cards off the edge of the screen.
        allowOverScroll = false
        bindOverviewCallbacks()
        animateOverview(1f)
        onOverviewChanged?.invoke(true)
    }

    fun exitOverview() {
        if (!isInOverview) return
        isInOverview = false
        allowOverScroll = true
        animateOverview(0f)
        onOverviewChanged?.invoke(false)
    }

    private var overviewAnimator: android.animation.ValueAnimator? = null

    /**
     * Scales the workspace into or out of overview.
     *
     * Driven per frame rather than by a [android.view.ViewPropertyAnimator]: the card outline and
     * badges fade in step with the zoom, which is what makes the pages look like they are becoming
     * cards rather than a scaled screenshot with decoration snapped on top.
     *
     * No vertical lift. The cards stay put and the panel simply arrives beneath them.
     */
    private fun animateOverview(target: Float) {
        overviewAnimator?.cancel()
        val start = if (target > 0f) 0f else 1f
        overviewAnimator = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = OVERVIEW_DURATION_MS
            addUpdateListener { applyOverviewProgress(it.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Guarantees the end state even where the animator is short-circuited by
                        // the system animation scale being off.
                        applyOverviewProgress(target)
                    }
                },
            )
            start()
        }
    }

    private fun applyOverviewProgress(progress: Float) {
        val scale = 1f - (1f - OVERVIEW_SCALE) * progress
        scaleX = scale
        scaleY = scale

        for (i in 0 until childCount) {
            (getChildAt(i) as? CellLayout)?.overviewProgress = progress
        }
    }

    /** Rewires page badges; called on entry and whenever pages are added or removed. */
    fun bindOverviewCallbacks(homeScreenId: Long = -1L) {
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? CellLayout ?: continue
            val screenId = screenOrder.getOrNull(i) ?: continue
            page.isHomePage = screenId == homeScreenId
            page.isRemovable = childCount > 1
            page.onHomeBadgeTap = { onOverviewSetHome?.invoke(screenId) }
            page.onRemoveBadgeTap = { onOverviewRemovePage?.invoke(screenId) }
            page.onPageTap = { onOverviewPageSelected?.invoke(indexOfChild(page)) }
        }
    }

    /** Marks which card carries the home badge. */
    fun setHomeScreen(screenId: Long) {
        for (i in 0 until childCount) {
            val page = getChildAt(i) as? CellLayout ?: continue
            page.isHomePage = screenOrder.getOrNull(i) == screenId
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var swipeConsumed = false
    private val swipeThresholdPx = SWIPE_THRESHOLD_DP * resources.displayMetrics.density

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            swipeConsumed = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * Checks for a vertical flick before letting [PagedView] claim the gesture as paging.
     *
     * The ratio test is what stops a diagonal page swipe from opening the drawer: the movement has
     * to be clearly more vertical than horizontal.
     */
    override fun determineScrollingStart(ev: MotionEvent, touchSlopScale: Float) {
        if (!swipeConsumed && onSwipeUp != null) {
            val dy = ev.y - downY
            val dx = ev.x - downX
            if (dy < -swipeThresholdPx && -dy > kotlin.math.abs(dx) * VERTICAL_BIAS) {
                swipeConsumed = true
                onSwipeUp?.invoke()
                return
            }
        }
        super.determineScrollingStart(ev, touchSlopScale)
    }

    override fun onPageEndMoving() {
        super.onPageEndMoving()
        // Phase 3 hooks the wallpaper parallax settle here.
    }

    companion object {
        private const val TAG = "Workspace"

        /** Id handed to the first page when the database has none yet. */
        const val FIRST_SCREEN_ID = 1L

        /** Id of the transient trailing page shown during a drag. Never persisted. */
        const val EXTRA_EMPTY_SCREEN_ID = -201L

        /**
         * Overview shrink factor. AOSP 7 used 70% (`config_workspaceOverviewShrinkPercentage`);
         * keeping the same number keeps the zoom-out feeling like Launcher3's.
         */
        private const val OVERVIEW_SCALE = 0.7f

        private const val OVERVIEW_DURATION_MS = 220L

        /** Minimum upward travel that counts as "open the drawer". */
        private const val SWIPE_THRESHOLD_DP = 48f

        /** How much more vertical than horizontal the flick must be. */
        private const val VERTICAL_BIAS = 1.5f

        /** The model object a workspace child stands for, if any. */
        fun viewInfo(view: View?): ItemInfo? = when (view) {
            is BubbleTextView -> view.itemInfo
            is FolderIcon -> view.folderInfo
            else -> null
        }
    }
}
