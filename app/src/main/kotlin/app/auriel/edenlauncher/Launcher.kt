package app.auriel.edenlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import app.auriel.edenlauncher.allapps.AllAppsContainerView
import app.auriel.edenlauncher.databinding.LauncherBinding
import app.auriel.edenlauncher.dragndrop.DragController
import app.auriel.edenlauncher.dragndrop.ItemPlacementHandler
import app.auriel.edenlauncher.folder.Folder
import app.auriel.edenlauncher.folder.FolderIcon
import app.auriel.edenlauncher.icons.CustomIcons
import app.auriel.edenlauncher.icons.IconPack
import app.auriel.edenlauncher.icons.IconPickerActivity
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ItemType
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.settings.LauncherPrefs
import app.auriel.edenlauncher.settings.SettingsActivity
import app.auriel.edenlauncher.util.EdenLog
import app.auriel.edenlauncher.views.BubbleTextView
import app.auriel.edenlauncher.views.ButtonDropTarget
import app.auriel.edenlauncher.views.CellLayout
import app.auriel.edenlauncher.views.IconOptionsPopup
import app.auriel.edenlauncher.views.Workspace
import app.auriel.edenlauncher.wallpaper.GLWallpaperService
import app.auriel.edenlauncher.wallpaper.picker.WallpaperPickerActivity
import app.auriel.edenlauncher.widget.LauncherAppWidgetHost
import app.auriel.edenlauncher.widget.WidgetBinding
import app.auriel.edenlauncher.widget.WidgetPickerActivity
import app.auriel.edenlauncher.widget.WidgetPlaceholderView
import app.auriel.edenlauncher.widget.WidgetResizeFrame
import app.auriel.edenlauncher.widget.WidgetSizes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The home activity.
 *
 * Ported from `Launcher` (AOSP 7). It owns the view hierarchy, drives the loader, and is the one
 * place that turns a gesture into a persisted change - the views themselves never touch the
 * database, they call through [ItemPlacementHandler].
 *
 * A plain [Activity] rather than an AppCompat one on purpose - the launcher hosts no action bar
 * and no AppCompat widgets, and skipping that layer keeps the resident process smaller.
 */
class Launcher : Activity(), ItemPlacementHandler {

    private lateinit var binding: LauncherBinding
    private lateinit var appState: LauncherAppState
    private lateinit var dragController: DragController

    private var openFolder: Folder? = null
    private var iconOptions: IconOptionsPopup? = null

    /** Grid settings as of the last resume; a change means the hierarchy has to be rebuilt. */
    private var gridSignature: String? = null

    /**
     * Icon pack as of the last resume.
     *
     * Icons are resolved during the load, so choosing a pack in settings changes nothing until
     * something reloads. Without this the setting appears to do nothing until the launcher happens
     * to be killed, which is the worst possible way for a setting to behave.
     */
    private var iconSignature: String? = null

    /**
     * Scope for work owned by this activity, cancelled in [onDestroy].
     *
     * Hand-rolled rather than pulled from `lifecycle-runtime-ktx`: one `CoroutineScope` is the
     * whole of what that dependency would provide here, and the launcher's dependency list is
     * kept to what it genuinely earns.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val allApps: AllAppsContainerView get() = binding.allApps

    /** Packages reported removed since the last rebind, waiting to be swept out of the database. */
    private val pendingRemovals = HashSet<String>(4)

    /** The coalescing rebind. Cancelled and rescheduled while packages keep arriving. */
    private var packageRefreshJob: Job? = null

    /** The icon pack being applied over an already-bound home screen. See [applyIconTheme]. */
    private var iconThemeJob: Job? = null

    /**
     * The folder a drag started inside, so the item can be taken out of it wherever it lands.
     *
     * Held for the length of the gesture rather than read off the open folder, because the folder
     * closes the moment the drag begins - the workspace the icon is heading for is behind it.
     */
    private var dragSourceFolder: FolderInfo? = null

    /** The app whose icon is being chosen, held across the trip to the document picker. */
    private var pendingIconComponent: android.content.ComponentName? = null

    /**
     * Eden's end of the widget contract.
     *
     * Created here rather than in [LauncherAppState] on purpose: a host that outlives the activity
     * outlives the views its widgets draw into, and the system keeps pushing updates at it.
     */
    private lateinit var widgetHost: LauncherAppWidgetHost

    /** The resize handles, when a widget is being resized. At most one at a time. */
    private var resizeFrame: WidgetResizeFrame? = null

    /**
     * The widget id allocated for an add that has not finished yet.
     *
     * Adding a widget is three trips through other activities - the picker, possibly the system's
     * bind prompt, possibly the provider's own configuration screen - and the id has to survive all
     * of them. Any path that gives up releases it; an id that is allocated and then forgotten is
     * never reclaimed by the system.
     */
    private var pendingWidgetId = LauncherAppWidgetInfo.NO_WIDGET_ID

    /**
     * Apps appearing and disappearing while the launcher is alive.
     *
     * Without this the drawer only ever shows the package list as it stood when the activity was
     * created: install something and it is missing until the launcher process is killed, uninstall
     * something and its icon sits there pointing at nothing. Registered for the whole lifetime of
     * the activity rather than between onStart and onStop, because installing an app almost always
     * happens with the launcher in the background - which is exactly the case that has to work.
     */
    private val packageWatcher = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) =
            onPackagesChanged(packageName, removed = false)

        override fun onPackageRemoved(packageName: String, user: UserHandle) =
            onPackagesChanged(packageName, removed = true)

        /** An update, an enable, or a disable. The component list may have changed either way. */
        override fun onPackageChanged(packageName: String, user: UserHandle) =
            onPackagesChanged(packageName, removed = false)

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = onPackagesChanged(*packageNames, removed = false)

        /**
         * Storage unmounted, not uninstalled. The apps leave the drawer, but their placed icons
         * stay where they are and come back with the card, so nothing is purged here.
         */
        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = onPackagesChanged(*packageNames, removed = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appState = LauncherAppState.getInstance(this)

        // Draw behind the system bars: the wallpaper is the background, and the workspace applies
        // insets itself via the drag layer.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
        )

        binding = LauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyRotationSetting()

        widgetHost = LauncherAppWidgetHost(this)
        // A provider being installed, updated, or removed changes what a placed widget can render.
        // The same coalesced rebind the package watcher uses is exactly right here too.
        widgetHost.onProvidersChanged = { onPackagesChanged(removed = false) }

        setUpDragAndDrop()
        setUpWorkspace()
        setUpAllApps()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { onBackRequested() }
        }

        getSystemService(LauncherApps::class.java).registerCallback(packageWatcher)

        loadAndBind(savedInstanceState?.getInt(STATE_CURRENT_PAGE, -1) ?: -1)
    }

    // ---- setup -----------------------------------------------------------------------------------

    private fun setUpDragAndDrop() {
        dragController = DragController(binding.dragLayer)
        binding.dragLayer.dragController = dragController

        dragController.addDropTarget(binding.workspace)
        dragController.addDropTarget(binding.hotseat)
        dragController.addDropTarget(binding.dropTargetBar.removeTarget)
        dragController.addDropTarget(binding.dropTargetBar.uninstallTarget)

        binding.workspace.placementHandler = this
        binding.hotseat.placementHandler = this

        binding.dropTargetBar.removeTarget.onDropped = { info -> onItemRemoved(info) }
        binding.dropTargetBar.uninstallTarget.onDropped = { info -> uninstall(info) }

        // A trailing empty page appears while dragging so an item can be flicked onto a new page,
        // and empty pages are swept up when the gesture ends. This is AOSP's behaviour and the
        // reason a Launcher3 workspace never accumulates blank screens.
        dragController.listener = object : DragController.DragListener {
            override fun onDragStart(info: ItemInfo?) {
                exitOverview()
                closeIconOptions()
                closeResizeFrame()
                // An icon on its way out of a folder needs somewhere to go, and the workspace is
                // behind the panel. Closed here rather than when the drag was armed so the panel is
                // still on screen for the drag view to be snapshotted from.
                if (dragSourceFolder != null) closeFolder()
                binding.dropTargetBar.setDragInProgress(true, info)
                binding.workspace.addExtraEmptyScreen()
            }

            override fun onDragEnd() {
                // A drag that was cancelled leaves the item in its folder, which is right: nothing
                // was moved, so nothing should have been taken out.
                dragSourceFolder = null
                binding.dropTargetBar.setDragInProgress(false)
                commitScreenChanges()
            }
        }

        dragController.scrollListener = DragController.ScrollListener { direction ->
            if (direction < 0) binding.workspace.scrollLeft() else binding.workspace.scrollRight()
        }
    }

    private fun setUpWorkspace() {
        binding.workspace.pageIndicator = binding.pageIndicator
        // The dots already say which page you are on; tapping one is the obvious way to ask for a
        // different one, and it is a long way across five pages otherwise.
        binding.pageIndicator.onMarkerSelected = { index -> binding.workspace.snapToPage(index) }
        binding.workspace.onSwipeUp = { openAllApps() }
        // Long press on empty space zooms the workspace out, as AOSP Launcher3 does. Page
        // management happens on the cards themselves rather than in a list of words.
        binding.workspace.onEmptySpaceLongPress = { enterOverview() }

        binding.workspace.onOverviewPageSelected = { index ->
            binding.workspace.snapToPage(index)
            exitOverview()
        }
        binding.workspace.onOverviewSetHome = { screenId -> setHomePage(screenId) }
        binding.workspace.onOverviewRemovePage = { screenId -> confirmRemovePage(screenId) }

        binding.overviewPanel.onAddPageClick = { addPage() }
        binding.overviewPanel.onWallpaperClick = { openWallpaperPicker() }
        binding.overviewPanel.onWidgetsClick = { openWidgetPicker() }
        binding.overviewPanel.onSettingsClick = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ---- overview ---------------------------------------------------------------------------------

    private fun enterOverview() {
        if (binding.workspace.isInOverview) return
        closeFolder()
        closeAllApps()
        // Read on the way in rather than once at startup, so the setting takes effect the next time
        // overview is opened instead of the next time the launcher is killed.
        binding.overviewPanel.setWidgetsVisible(!appState.preferences.simpleMode)
        binding.workspace.bindOverviewCallbacks(appState.preferences.defaultScreenId)
        binding.workspace.enterOverview()
        binding.overviewPanel.open()
        binding.hotseat.animate().alpha(0f).setDuration(OVERVIEW_CHROME_FADE_MS).start()
    }

    private fun exitOverview() {
        if (!binding.workspace.isInOverview) return
        binding.workspace.exitOverview()
        binding.overviewPanel.close()
        binding.hotseat.animate().alpha(1f).setDuration(OVERVIEW_CHROME_FADE_MS).start()
    }

    /**
     * Chooses which page HOME returns to.
     *
     * Stock launchers assume the leftmost page is home, which is wrong for anyone who keeps their
     * home page in the middle with pages either side of it. The choice is stored by page id, so
     * reordering pages does not quietly move it.
     */
    private fun setHomePage(screenId: Long) {
        appState.preferences.defaultScreenId = screenId
        updateHomePageIndicator()
        Toast.makeText(this, R.string.home_page_set, Toast.LENGTH_SHORT).show()
    }

    /**
     * Index of the page HOME returns to, falling back to the leftmost one.
     *
     * Only meaningful once the pages are bound - the choice is stored as a page id, and turning an
     * id into an index needs the id list. Calling it earlier always answered zero.
     */
    private fun defaultPageIndex(): Int {
        val screenId = appState.preferences.defaultScreenId
        if (screenId == LauncherPrefs.NO_DEFAULT_SCREEN) return 0
        return binding.workspace.getScreenIndex(screenId).takeIf { it >= 0 } ?: 0
    }

    /** Puts the chosen home page's mark on the dots and its badge on the overview card. */
    private fun updateHomePageIndicator() {
        val screenId = appState.preferences.defaultScreenId
        binding.workspace.setHomeScreen(screenId)
        binding.pageIndicator.setHomeMarker(
            if (screenId == LauncherPrefs.NO_DEFAULT_SCREEN) -1 else binding.workspace.getScreenIndex(screenId),
        )
    }

    /**
     * Adds an empty page and moves to it.
     *
     * Pages exist because the user wants them, not because something is on them. A page created
     * here is written to the `workspaceScreens` table straight away, so an intentionally empty
     * screen survives a reboot exactly like a full one.
     */
    private fun addPage() {
        scope.launch {
            val screenId = appState.repository.addScreen()
            binding.workspace.insertNewScreen(screenId)
            appState.repository.reorderScreens(binding.workspace.screenIds)
            binding.workspace.bindOverviewCallbacks(appState.preferences.defaultScreenId)
            binding.workspace.snapToPage(binding.workspace.getScreenIndex(screenId))
            updateHomePageIndicator()
        }
    }

    /** Deletes a page, with everything on it. The last page is never removed. */
    /**
     * The X on an overview card.
     *
     * An empty page goes straight away - there is nothing to lose and asking would be noise. A page
     * with things on it asks first, because the rule this project holds itself to is that the
     * user's arrangement is never lost, and one mis-tap on a small badge is not consent to destroy
     * a screenful of work. AOSP asks too.
     */
    private fun confirmRemovePage(screenId: Long) {
        val occupied = binding.workspace.getScreenWithId(screenId)?.childCount ?: 0
        if (occupied == 0) {
            removePage(screenId)
            return
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.remove_page_title)
            .setMessage(resources.getQuantityString(R.plurals.remove_page_message, occupied, occupied))
            .setPositiveButton(R.string.remove_page_confirm) { _, _ -> removePage(screenId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePage(screenId: Long) {
        val workspace = binding.workspace
        if (!workspace.removeScreen(screenId)) {
            Toast.makeText(this, R.string.last_page_kept, Toast.LENGTH_SHORT).show()
            return
        }
        workspace.bindOverviewCallbacks(appState.preferences.defaultScreenId)
        scope.launch {
            appState.repository.removeScreen(screenId)
            appState.repository.reorderScreens(workspace.screenIds)
            // The home page just went away; fall back to the leftmost one.
            if (appState.preferences.defaultScreenId == screenId) {
                appState.preferences.defaultScreenId = LauncherPrefs.NO_DEFAULT_SCREEN
            }
            updateHomePageIndicator()
        }
    }

    /**
     * Opens Eden's own wallpaper picker.
     *
     * An explicit intent rather than `ACTION_SET_WALLPAPER` through a chooser: this is Eden's
     * picker and the user asked for it from Eden's overview, so putting a disambiguation dialog in
     * front of it would be asking a question nobody posed.
     */
    private fun openWallpaperPicker() {
        try {
            startActivity(Intent(this, WallpaperPickerActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpAllApps() {
        allApps.onAppClick = { app ->
            // Close first: the drawer covers the screen, and leaving it open means coming back
            // from the app lands on the drawer rather than the home screen.
            closeAllApps()
            startApp(app, null)
        }
        allApps.onAppLongClick = { app, view ->
            // Same two-in-one gesture as the workspace: hold for the options, move to drag the app
            // out onto the home screen. The drawer entry itself never moves - dragging out of the
            // drawer copies, which is why hideOriginal is false.
            dragController.preparePendingDrag(
                view = view,
                source = binding.workspace,
                info = app.toShortcut(),
                hideOriginal = false,
                onPromoted = {
                    closeIconOptions()
                    closeAllApps()
                },
            )
            showDrawerIconOptions(view, app)
        }

        // Pulling the drawer down past the top of the list closes it, which is the same gesture
        // that opened it, run backwards.
        allApps.onDismissRequested = { closeAllApps() }

        val prefs = appState.preferences
        prefs.appDrawerModeFlow()
            .onEach { allApps.setMode(it) }
            .launchIn(scope)
        prefs.appDrawerOpacityFlow()
            .onEach { allApps.setBackgroundOpacity(it) }
            .launchIn(scope)
    }

    // ---- loading ---------------------------------------------------------------------------------

    private fun loadAndBind(restoredPage: Int) {
        scope.launch {
            val started = android.os.SystemClock.uptimeMillis()
            val results = appState.model.load()
            bind(results, restoredPage)
            // The one measurement worth keeping: if the home screen is ever slow to appear, this
            // says whether the time went into the loader or into everything after it.
            EdenLog.i(
                TAG,
                "loaded ${results.allApps.size} apps, ${results.workspaceItems.size} placed, " +
                    "${results.screenIds.size} pages in " +
                    "${android.os.SystemClock.uptimeMillis() - started}ms",
            )
        }
    }

    /**
     * Replaces every bound view from a load result.
     *
     * The hierarchy is rebuilt rather than patched. A package change can add, remove, rename, and
     * re-icon any number of items at once, and rebuilding a few dozen views is cheaper than the
     * bookkeeping needed to work out which of them actually moved - and cannot go subtly wrong.
     *
     * @param targetPage the page to land on, or a negative number for "wherever home is". The
     *   choice is resolved *here*, after the pages exist. Resolving it in the caller meant asking
     *   an empty workspace which page carried a given id, and the only answer an empty workspace
     *   can give is "none of them" - so every cold start ignored the user's home page and opened
     *   on the leftmost one.
     */
    private fun bind(results: LauncherModel.LoaderResults, targetPage: Int) {
        closeFolder()
        binding.hotseat.removeAllViews()

        binding.workspace.bindScreens(results.screenIds)
        bindWorkspaceItems(results.workspaceItems)
        bindHotseatItems(results.hotseatItems)
        allApps.setApps(results.allApps)

        updateHomePageIndicator()
        val page = if (targetPage >= 0) targetPage else defaultPageIndex()
        val lastPage = (binding.workspace.childCount - 1).coerceAtLeast(0)
        binding.workspace.setCurrentPage(page.coerceIn(0, lastPage))

        applyIconTheme(results)
    }

    // ---- icon theming ----------------------------------------------------------------------------

    /**
     * Applies the icon pack after the home screen is already on screen.
     *
     * Resolving a themed icon is expensive in a way an app's own icon is not: the first one parses
     * the pack's `appfilter.xml`, and every app the pack has no entry for gets a background, a mask
     * and an overlay composed under and over it. Done inline, that is a few hundred milliseconds
     * of work per twenty apps sitting between the user and their home screen - which is what the
     * launcher used to feel like it was hanging on when a pack was selected.
     *
     * So the loader now resolves the apps' own icons only, the workspace binds and is usable, and
     * this pass replaces them a batch at a time. Placed icons go first because they are the ones
     * being looked at; the drawer, which is not open, follows. Nothing here runs at all unless a
     * pack or a hand-picked icon is actually in force.
     */
    private fun applyIconTheme(results: LauncherModel.LoaderResults) {
        // A rebind supersedes the pass started by the one before it; the items it was updating are
        // no longer the items on screen.
        iconThemeJob?.cancel()
        if (!appState.model.hasThemedIcons) return

        iconThemeJob = scope.launch {
            val started = android.os.SystemClock.uptimeMillis()

            val placed = ArrayList<ShortcutInfo>(32)
            collectShortcuts(results.workspaceItems, placed)
            collectShortcuts(results.hotseatItems, placed)

            for (batch in placed.chunked(ICON_THEME_BATCH)) {
                if (appState.model.applyThemedIcons(batch)) refreshPlacedIcons()
            }

            for (batch in results.allApps.chunked(ICON_THEME_BATCH)) {
                if (appState.model.applyThemedIconsToApps(batch)) allApps.refreshIcons()
            }

            EdenLog.i(
                TAG,
                "icon pack applied to ${placed.size} placed and ${results.allApps.size} drawer " +
                    "icons in ${android.os.SystemClock.uptimeMillis() - started}ms",
            )
        }
    }

    /** Flattens placed items to the shortcuts that carry an icon, folder contents included. */
    private fun collectShortcuts(items: List<ItemInfo>, into: MutableList<ShortcutInfo>) {
        for (item in items) {
            when (item) {
                is FolderInfo -> into.addAll(item.contents)
                is ShortcutInfo -> into.add(item)
                else -> Unit
            }
        }
    }

    /** Re-reads the icon off every bound workspace and dock view. */
    private fun refreshPlacedIcons() {
        refreshIconsIn(binding.hotseat)
        val workspace = binding.workspace
        for (i in 0 until workspace.childCount) {
            (workspace.getChildAt(i) as? CellLayout)?.let { refreshIconsIn(it) }
        }
    }

    private fun refreshIconsIn(page: android.view.ViewGroup) {
        for (i in 0 until page.childCount) {
            when (val child = page.getChildAt(i)) {
                is BubbleTextView -> (child.itemInfo as? ShortcutInfo)?.let {
                    child.setIconBitmap(it.icon)
                }

                // A folder tile is drawn from its contents, and the open folder listens to the same
                // notification, so one call updates both.
                is FolderIcon -> child.folderInfo?.notifyItemsChanged()

                else -> Unit
            }
        }
    }

    /**
     * Something was installed, updated, or uninstalled.
     *
     * Package events arrive in bursts - a restore, or one install that pulls in three more - so the
     * rebind is coalesced rather than run per package. Removals are accumulated across the burst
     * because the sweep needs all of them at once.
     */
    private fun onPackagesChanged(vararg packages: String, removed: Boolean) {
        for (name in packages) {
            appState.iconCache.removePackage(name)
            if (removed) pendingRemovals.add(name)
        }

        packageRefreshJob?.cancel()
        packageRefreshJob = scope.launch {
            delay(PACKAGE_REFRESH_DELAY_MS)

            // Rebinding mid-drag would delete the view under the finger. The drag is the user's;
            // the reload can wait for it.
            while (dragController.isDragging) delay(DRAG_SETTLE_POLL_MS)

            val gone = pendingRemovals.toSet()
            pendingRemovals.clear()
            val purged = appState.model.purgePackages(gone)

            bind(appState.model.load(), binding.workspace.currentPageIndex)
            EdenLog.i(
                TAG,
                "package change: removed=$gone purgedRows=$purged, drawer rebound",
            )
        }
    }

    private fun bindWorkspaceItems(items: List<ItemInfo>) {
        for (item in items) {
            val view = createItemView(item) ?: continue
            if (!binding.workspace.addInScreen(view, item)) {
                // The stored cell no longer fits (the grid shrank); re-home rather than drop.
                rehomeItem(item, view)
            }
        }
    }

    private fun bindHotseatItems(items: List<ItemInfo>) {
        val hotseat = binding.hotseat
        for (item in items) {
            val view = createItemView(item) ?: continue
            // A single-row dock has no room for labels, so the icon fills the cell instead.
            (view as? BubbleTextView)?.setLabelVisible(false)
            val cellX = hotseat.cellXForRank(item.rank)
            val cellY = hotseat.cellYForRank(item.rank)
            hotseat.addItem(view, -1, CellLayout.LayoutParams(cellX, cellY, 1, 1))
        }
    }

    /** Builds the right view for a model object, or null for kinds that cannot render yet. */
    private fun createItemView(item: ItemInfo): View? = when (item) {
        is FolderInfo -> FolderIcon(this).apply {
            applyFrom(item)
            setOnClickListener { openFolder(item) }
            setOnLongClickListener { startDragFromWorkspace(this, item) }
        }

        is ShortcutInfo -> BubbleTextView(this).apply {
            applyFrom(item, item.icon)
            setOnClickListener { startApp(item, this) }
            setOnLongClickListener { startDragFromWorkspace(this, item) }
        }

        is LauncherAppWidgetInfo -> createWidgetView(item)

        else -> null
    }

    // ---- widgets ---------------------------------------------------------------------------------

    /**
     * A hosted widget, or the placeholder when it cannot be hosted right now.
     *
     * The placeholder is not dead code: a widget whose provider has been uninstalled, or whose id
     * was never bound, still has a database row and a place on the grid. Drawing nothing there
     * would look like the launcher had lost it, and the user would have no way to remove something
     * they cannot see.
     */
    private fun createWidgetView(item: LauncherAppWidgetInfo): View {
        val providerInfo = if (item.appWidgetId >= 0) {
            WidgetBinding.providerFor(this, item.appWidgetId)
        } else {
            null
        }

        if (providerInfo == null) {
            return WidgetPlaceholderView(this).apply {
                applyFrom(item)
                tag = item
                setOnLongClickListener { startDragFromWorkspace(this, item) }
            }
        }

        return widgetHost.createView(this, item.appWidgetId, providerInfo).apply {
            setAppWidget(item.appWidgetId, providerInfo)
            // The launcher's own views carry their model object in a typed field; a host view is
            // the provider's, so the tag is the only place to put it. See Workspace.viewInfo.
            tag = item
            setOnLongClickListener { startDragFromWorkspace(this, item) }
            // The host view is measured by the cell, and the provider needs telling in dp what it
            // was actually given, or it picks a layout for the wrong size.
            post {
                if (width > 0 && height > 0) {
                    WidgetSizes.applySize(this, this@Launcher, width, height)
                }
            }
        }
    }

    private fun openWidgetPicker() {
        exitOverview()
        try {
            startActivityForResult(WidgetPickerActivity.intentFor(this), REQUEST_PICK_WIDGET)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Step two of adding a widget: an id is allocated and bound, either silently or by asking.
     *
     * The id is allocated before the bind because binding is what an id is *for* - there is no way
     * to ask the system whether a provider would be allowed without having one to offer it.
     */
    private fun bindWidget(provider: android.content.ComponentName) {
        val appWidgetId = widgetHost.allocateAppWidgetId()
        pendingWidgetId = appWidgetId

        if (WidgetBinding.bindIfAllowed(this, appWidgetId, provider)) {
            configureOrPlaceWidget(appWidgetId)
            return
        }

        try {
            startActivityForResult(
                WidgetBinding.bindPermissionIntent(appWidgetId, provider),
                REQUEST_BIND_WIDGET,
            )
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "no activity for the widget bind prompt", e)
            abandonPendingWidget()
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Step three: the provider's own settings screen, if it has one. Otherwise place it. */
    private fun configureOrPlaceWidget(appWidgetId: Int) {
        val info = WidgetBinding.providerFor(this, appWidgetId)
        val configuring = WidgetBinding.startConfigureIfNeeded(
            activity = this,
            host = widgetHost,
            appWidgetId = appWidgetId,
            info = info,
            requestCode = REQUEST_CONFIGURE_WIDGET,
        )
        if (!configuring) placeWidget(appWidgetId)
    }

    /**
     * The widget is bound and configured; find it a home.
     *
     * A widget that will not fit anywhere is refused *here* rather than earlier, because "anywhere"
     * includes pages the user has not filled yet and the picker cannot know about those.
     */
    private fun placeWidget(appWidgetId: Int) {
        pendingWidgetId = LauncherAppWidgetInfo.NO_WIDGET_ID

        val providerInfo = WidgetBinding.providerFor(this, appWidgetId) ?: run {
            widgetHost.releaseWidgetId(appWidgetId)
            Toast.makeText(this, R.string.widget_bind_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val profile = currentDeviceProfile()
        val span = WidgetSizes.defaultSpan(providerInfo, profile)
        val cell = IntArray(2)
        val screenId = binding.workspace.findFreeCell(cell, span[0], span[1])
        if (screenId < 0) {
            widgetHost.releaseWidgetId(appWidgetId)
            Toast.makeText(this, R.string.widget_no_space, Toast.LENGTH_SHORT).show()
            return
        }

        val info = LauncherAppWidgetInfo(appWidgetId, providerInfo.provider).apply {
            container = Containers.DESKTOP
            this.screenId = screenId
            cellX = cell[0]
            cellY = cell[1]
            spanX = span[0]
            spanY = span[1]
            title = providerInfo.loadLabel(packageManager)
        }

        scope.launch {
            info.id = appState.repository.addItem(info)
            val view = createWidgetView(info)
            if (binding.workspace.addInScreen(view, info)) {
                binding.workspace.snapToPage(binding.workspace.getScreenIndex(screenId))
            } else {
                rehomeItem(info, view)
            }
            EdenLog.i(TAG, "placed widget ${providerInfo.provider} as ${span[0]}x${span[1]}")
        }
    }

    /** Gives an allocated id back when the add is cancelled or fails part-way. */
    private fun abandonPendingWidget() {
        if (pendingWidgetId != LauncherAppWidgetInfo.NO_WIDGET_ID) {
            widgetHost.releaseWidgetId(pendingWidgetId)
            pendingWidgetId = LauncherAppWidgetInfo.NO_WIDGET_ID
        }
    }

    /** Puts the resize handles around a placed widget. */
    private fun startWidgetResize(info: LauncherAppWidgetInfo) {
        val view = findViewFor(info) ?: return
        val page = view.parent as? CellLayout ?: return
        val providerInfo = WidgetBinding.providerFor(this, info.appWidgetId) ?: return

        closeIconOptions()
        val profile = currentDeviceProfile()
        val frame = resizeFrame ?: WidgetResizeFrame(this).also { resizeFrame = it }
        frame.attach(
            dragLayer = binding.dragLayer,
            widget = view,
            page = page,
            minSpan = WidgetSizes.minSpan(providerInfo, profile),
            maxSpan = WidgetSizes.maxSpan(providerInfo, profile),
        ) { cellX, cellY, spanX, spanY ->
            info.cellX = cellX
            info.cellY = cellY
            info.spanX = spanX
            info.spanY = spanY
            WidgetSizes.applySize(
                view as android.appwidget.AppWidgetHostView,
                this,
                spanX * page.cellWidth,
                spanY * page.cellHeight,
            )
            scope.launch { appState.repository.updateItem(info) }
        }

        binding.dragLayer.activeResizeFrame = frame
        binding.dragLayer.onTouchOutsideResizeFrame = { closeResizeFrame() }
    }

    /** Takes the resize handles away. Returns true when there were any, so back can consume it. */
    private fun closeResizeFrame(): Boolean {
        val frame = resizeFrame ?: return false
        if (!frame.isAttached) return false
        frame.detach()
        binding.dragLayer.activeResizeFrame = null
        binding.dragLayer.onTouchOutsideResizeFrame = null
        return true
    }

    /**
     * Locks the home screen to the device's natural orientation unless the user asked otherwise.
     *
     * Applied here rather than only in the manifest so the setting takes effect on the way back
     * from settings instead of the next time the process is killed. `nosensor` rather than
     * `portrait`: it pins the activity to whatever the device's natural orientation is, which is
     * landscape on some tablets and foldables, so this never forces one to be sideways-wrong.
     */
    private fun applyRotationSetting() {
        val wanted = if (appState.preferences.allowRotation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    private fun currentDeviceProfile() = appState.invariantDeviceProfile.profileFor(
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
    )

    /**
     * A long press on a workspace icon.
     *
     * Arms a drag and shows the icon's options at the same time. Hold still and you get the menu;
     * move and the drag you were expecting takes over and the menu disappears. Neither gesture had
     * to be given up to make room for the other - see [DragController.preparePendingDrag].
     */
    private fun startDragFromWorkspace(view: View, info: ItemInfo): Boolean {
        closeFolder()
        closeIconOptions()
        // This one is not coming out of a folder, whatever the last armed gesture was.
        dragSourceFolder = null

        dragController.preparePendingDrag(
            view = view,
            source = binding.workspace,
            info = info,
            hideOriginal = true,
            onPromoted = { closeIconOptions() },
        )
        showIconOptions(view, info)
        return true
    }

    // ---- icon options ----------------------------------------------------------------------------

    private fun showIconOptions(anchor: View, info: ItemInfo) {
        // v0.2.0 had no per-icon menu at all: a long press armed a drag and that was the whole
        // gesture. Everything this menu offers arrived later, so in Simple mode it does not appear
        // and the press goes straight to dragging. Removing and uninstalling are still there, on
        // the drop targets at the top of the screen, which is where 0.2.0 had them.
        if (appState.preferences.simpleMode) return

        val uninstallablePackage = ButtonDropTarget.uninstallablePackage(info)

        val popup = IconOptionsPopup(this)
            .addAction(R.string.icon_option_rename) { showRenameDialog(info, anchor) }

        // Folders already are folders; only a loose icon can be put into a new one.
        if (info is ShortcutInfo && info !is FolderInfo) {
            popup.addAction(R.string.icon_option_new_folder) { createFolderAround(info) }
        }

        // Only offered for a widget whose provider says it can be resized at all. A fixed-size
        // widget with handles that refuse to move is worse than no handles.
        if (info is LauncherAppWidgetInfo) {
            val providerInfo = WidgetBinding.providerFor(this, info.appWidgetId)
            popup.addAction(
                R.string.widget_option_resize,
                enabled = providerInfo != null && WidgetSizes.isResizable(providerInfo),
            ) { startWidgetResize(info) }
        }

        popup
            .addAction(
                R.string.icon_option_icon,
                enabled = info.intent?.component != null,
            ) { showIconChooser(info.intent?.component) }
            .addAction(R.string.icon_option_app_info, enabled = info.intent?.component != null) {
                showAppInfo(info)
            }
            .addAction(R.string.icon_option_remove) { onItemRemoved(info) }
            .addAction(R.string.icon_option_uninstall, enabled = uninstallablePackage != null) {
                uninstall(info)
            }

        iconOptions = popup
        // The popup owns its own dismissal. Routing it through the drag layer's touch-complete
        // hook would close it on the ACTION_UP of the long press that opened it.
        popup.showAt(binding.dragLayer, anchor) { iconOptions = null }
    }

    /**
     * The drawer's version of the icon menu.
     *
     * A shorter list, because two of the workspace actions have no meaning here: a drawer entry is
     * not on the home screen, so it cannot be removed from it, and it is not in a folder and
     * cannot be put in one.
     *
     * Renaming is here, and it applies everywhere the app is shown. Search still matches the name
     * the app gives itself, so renaming can never make an app unfindable.
     *
     * Uninstall is here because this is where people go looking for it.
     */
    private fun showDrawerIconOptions(anchor: View, app: AppInfo) {
        // Same reasoning as the workspace menu: v0.2.0 had none, so Simple mode has none, and a
        // long press in the drawer goes straight to dragging the app out onto the home screen.
        if (appState.preferences.simpleMode) return

        val info = app.toShortcut()

        val popup = IconOptionsPopup(this)
            .addAction(
                R.string.icon_option_rename,
                enabled = app.componentName != null,
            ) { showDrawerRenameDialog(app) }
            .addAction(
                R.string.icon_option_icon,
                enabled = app.componentName != null,
            ) { showIconChooser(app.componentName) }
            .addAction(R.string.icon_option_app_info, enabled = info.intent?.component != null) {
                showAppInfo(info)
            }
            .addAction(
                R.string.icon_option_uninstall,
                enabled = ButtonDropTarget.uninstallablePackage(info) != null,
            ) { uninstall(info) }

        iconOptions = popup
        popup.showAt(binding.dragLayer, anchor) { iconOptions = null }
    }

    /**
     * Renames an app in the drawer.
     *
     * Stored against the component rather than against a database row, because a drawer entry has
     * no row - it is rebuilt from the package manager on every load. Clearing the field restores
     * the app's own name.
     *
     * The drawer is reloaded afterwards rather than patched in place: the list is sorted by the
     * name shown, so a rename can move the app, and quietly leaving it under the old letter would
     * be worse than the reload.
     */
    private fun showDrawerRenameDialog(app: AppInfo) {
        val component = app.componentName ?: return

        val input = android.widget.EditText(this).apply {
            setText(app.title?.toString().orEmpty())
            setSelection(text.length)
            setSingleLine()
        }

        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                applyDrawerRename(component, input.text?.toString().orEmpty().trim())
            }
            .setNeutralButton(R.string.rename_dialog_reset) { _, _ ->
                applyDrawerRename(component, "")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDrawerRename(component: android.content.ComponentName, title: String) {
        appState.preferences.setAppTitleOverride(
            component.flattenToString(),
            title.ifBlank { null },
        )
        scope.launch {
            val results = appState.model.load()
            allApps.setApps(results.allApps)
        }
    }

    // ---- icons -----------------------------------------------------------------------------------

    /**
     * The per-app icon menu: pick a picture, or go back to whatever the app or the pack provides.
     *
     * Deliberately separate from the icon pack setting. A pack is a decision about every app at
     * once; this is a decision about one, and it wins over the pack - which is why "use the
     * default" has to exist as a way back rather than being implied by changing packs.
     */
    private fun showIconChooser(component: android.content.ComponentName?) {
        if (component == null) return
        pendingIconComponent = component

        // "From the pack" only appears when a pack is actually in use, because otherwise there is
        // nothing to browse and an option that opens an empty screen is worse than no option.
        val pack = appState.preferences.iconPackPackage
        val options = ArrayList<String>(3)
        if (pack != null) options.add(getString(R.string.icon_choose_from_pack))
        options.add(getString(R.string.icon_choose_picture))
        if (CustomIcons.has(this, component)) options.add(getString(R.string.icon_reset))

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.icon_option_icon)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.icon_choose_from_pack) -> browsePackIcons(pack)
                    getString(R.string.icon_choose_picture) -> pickCustomIcon()
                    else -> clearCustomIcon(component)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingIconComponent = null }
            .show()
    }

    private fun browsePackIcons(pack: String?) {
        if (pack == null) return
        try {
            startActivityForResult(IconPickerActivity.intentFor(this, pack), REQUEST_PACK_ICON)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "Could not open the icon browser", e)
            pendingIconComponent = null
        }
    }

    private fun pickCustomIcon() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        try {
            startActivityForResult(intent, REQUEST_CUSTOM_ICON)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No document picker for a custom icon", e)
            pendingIconComponent = null
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearCustomIcon(component: android.content.ComponentName) {
        pendingIconComponent = null
        CustomIcons.clear(this, component)
        reloadForIconChange()
    }

    @Deprecated("Plain Activity has no result registry; the androidx one is not worth the dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        // The widget flow owns its own state and has to see a cancel as clearly as a success:
        // every abandoned step leaves an allocated id behind if nobody releases it.
        when (requestCode) {
            REQUEST_PICK_WIDGET -> {
                // The typed overload is API 33; this has to keep working on 29.
                @Suppress("DEPRECATION")
                val provider = data
                    ?.getParcelableExtra<android.content.ComponentName>(
                        WidgetPickerActivity.EXTRA_PROVIDER,
                    )
                if (resultCode == RESULT_OK && provider != null) bindWidget(provider)
                return
            }

            REQUEST_BIND_WIDGET -> {
                val appWidgetId = data?.getIntExtra(
                    android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                    pendingWidgetId,
                ) ?: pendingWidgetId
                if (resultCode == RESULT_OK) {
                    configureOrPlaceWidget(appWidgetId)
                } else {
                    // The user said no to the permission prompt. Nothing is bound to the id.
                    abandonPendingWidget()
                }
                return
            }

            REQUEST_CONFIGURE_WIDGET -> {
                val appWidgetId = data?.getIntExtra(
                    android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                    pendingWidgetId,
                ) ?: pendingWidgetId
                if (resultCode == RESULT_OK) {
                    placeWidget(appWidgetId)
                } else {
                    // Backing out of a configuration screen means the widget was never wanted.
                    abandonPendingWidget()
                }
                return
            }
        }

        val component = pendingIconComponent
        pendingIconComponent = null
        if (resultCode != RESULT_OK || component == null) return

        val size = appState.invariantDeviceProfile.iconBitmapSizePx
        when (requestCode) {
            REQUEST_CUSTOM_ICON -> {
                val uri = data?.data ?: return
                applyChosenIcon {
                    CustomIcons.set(this@Launcher, component, uri, size)
                }
            }

            REQUEST_PACK_ICON -> {
                val drawable = data?.getStringExtra(IconPickerActivity.EXTRA_DRAWABLE) ?: return
                val pack = appState.preferences.iconPackPackage ?: return
                applyChosenIcon {
                    val bitmap = IconPack(this@Launcher, pack).bitmapFor(drawable, size)
                    bitmap != null && CustomIcons.setFromBitmap(this@Launcher, component, bitmap)
                }
            }
        }
    }

    private fun applyChosenIcon(store: suspend () -> Boolean) {
        scope.launch {
            val stored = kotlinx.coroutines.withContext(Dispatchers.IO) { store() }
            if (!stored) {
                Toast.makeText(this@Launcher, R.string.icon_set_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            reloadForIconChange()
        }
    }

    /** Icons are resolved during the load, so a changed icon means one more load and one rebind. */
    private fun reloadForIconChange() {
        appState.iconCache.clear()
        scope.launch { bind(appState.model.load(), binding.workspace.currentPageIndex) }
    }

    private fun closeIconOptions() {
        iconOptions?.dismiss()
        iconOptions = null
    }

    /**
     * Renames an item, storing the new label against its row.
     *
     * The title is Eden's, not the app's: the app keeps whatever it calls itself, and clearing the
     * field puts the original name back rather than leaving a blank icon. That reversibility is
     * the whole reason renaming is safe to offer.
     */
    private fun showRenameDialog(info: ItemInfo, anchor: View) {
        val input = android.widget.EditText(this).apply {
            setText(info.title?.toString().orEmpty())
            setSelection(text.length)
            setSingleLine()
        }

        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                applyRename(info, input.text?.toString().orEmpty().trim(), anchor)
            }
            .setNeutralButton(R.string.rename_dialog_reset) { _, _ ->
                applyRename(info, "", anchor)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyRename(info: ItemInfo, newTitle: String, anchor: View) {
        val resolved = newTitle.ifEmpty { originalTitleFor(info) }

        when (info) {
            is FolderInfo -> info.rename(resolved)
            else -> info.title = resolved
        }

        when (anchor) {
            is BubbleTextView -> anchor.setLabelVisible(info.container != Containers.HOTSEAT)
            is FolderIcon -> anchor.applyFrom(info as FolderInfo)
        }

        scope.launch { appState.repository.updateItem(info) }
    }

    /** The name the app gives itself, used when a custom name is cleared. */
    private fun originalTitleFor(info: ItemInfo): String {
        val component = info.intent?.component ?: return getString(R.string.folder_name)
        return runCatching {
            val activityInfo = packageManager.getActivityInfo(component, 0)
            activityInfo.loadLabel(packageManager).toString()
        }.getOrDefault(info.title?.toString() ?: "")
    }

    /**
     * Wraps a single icon in a new folder, in place.
     *
     * Until now the only way to make a folder was to stack one icon onto another, which quietly
     * requires you to already own two things you want together. Sometimes you just want the folder
     * first.
     */
    private fun createFolderAround(item: ShortcutInfo) {
        scope.launch {
            val folder = FolderInfo().apply {
                rename(getString(R.string.folder_name))
                container = item.container
                screenId = item.screenId
                cellX = item.cellX
                cellY = item.cellY
                rank = item.rank
                itemType = ItemType.FOLDER
            }
            appState.repository.addItem(folder)

            findViewFor(item)?.let { removeViewFromParent(it) }

            item.container = folder.id
            item.screenId = -1L
            item.cellX = -1
            item.cellY = -1
            item.rank = 0
            folder.contents.add(item)
            appState.repository.updateItem(item)

            val icon = createItemView(folder) ?: return@launch
            if (folder.container == Containers.HOTSEAT) {
                binding.hotseat.addItem(
                    icon,
                    -1,
                    CellLayout.LayoutParams(folder.cellX, folder.cellY, 1, 1),
                )
            } else {
                binding.workspace.addInScreen(icon, folder)
            }
            Toast.makeText(this@Launcher, R.string.folder_created, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppInfo(info: ItemInfo) {
        val component = info.intent?.component ?: return
        runCatching {
            getSystemService(LauncherApps::class.java)
                .startAppDetailsActivity(component, info.user, null, null)
        }.onFailure {
            EdenLog.w(TAG, "Could not open app info for $component", it)
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Hands off to the system uninstaller.
     *
     * `ACTION_DELETE` rather than a silent removal on purpose: uninstalling is the one irreversible
     * thing a launcher can trigger, and the system's own confirmation is the right last word. Eden
     * does not remove the icon itself - the model reload after the app disappears does that, so a
     * cancelled uninstall leaves the home screen exactly as it was.
     */
    private fun uninstall(info: ItemInfo) {
        val packageName = ButtonDropTarget.uninstallablePackage(info)
        if (packageName == null) {
            Toast.makeText(this, R.string.uninstall_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No uninstaller for $packageName", e)
            Toast.makeText(this, R.string.uninstall_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Puts an item that no longer fits into the first free cell anywhere on the workspace. */
    private fun rehomeItem(item: ItemInfo, view: View) {
        val cell = IntArray(2)
        val screenId = binding.workspace.findFreeCell(cell, item.spanX, item.spanY)
        if (screenId < 0) return

        item.screenId = screenId
        item.cellX = cell[0]
        item.cellY = cell[1]
        if (binding.workspace.addInScreen(view, item)) {
            scope.launch { appState.repository.updateItem(item) }
        }
    }

    // ---- launching -------------------------------------------------------------------------------

    private fun startApp(info: ItemInfo, source: View?) {
        val intent = info.intent ?: return
        try {
            val launcherApps = getSystemService(LauncherApps::class.java)

            // A deep shortcut has no launchable intent: it is started through LauncherApps by id.
            val shortcut = info as? ShortcutInfo
            if (shortcut != null && shortcut.isDeepShortcut) {
                launcherApps.startShortcut(
                    shortcut.deepShortcutPackage!!,
                    shortcut.deepShortcutId!!,
                    source?.clipBounds,
                    null,
                    shortcut.user,
                )
                return
            }

            val component = intent.component
            when {
                // Eden's own activities: a MAIN/LAUNCHER intent with NEW_TASK matches the
                // launcher's own already-running task, so the system just brings the home screen
                // back to front and the target never starts. An explicit intent in this task does.
                component != null && component.packageName == packageName ->
                    startActivity(Intent().setComponent(component))

                // startMainActivity carries the profile, so work-profile apps launch correctly.
                component != null ->
                    launcherApps.startMainActivity(component, info.user, source?.clipBounds, null)

                else -> startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No activity for $intent", e)
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Some components refuse LauncherApps.startMainActivity (notably activities of the
            // launcher's own package). A plain intent launch still works for those.
            EdenLog.w(TAG, "startMainActivity refused for $intent, falling back", e)
            try {
                startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (fallback: ActivityNotFoundException) {
                EdenLog.w(TAG, "Fallback launch failed for $intent", fallback)
                Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- ItemPlacementHandler --------------------------------------------------------------------

    override fun onItemPlaced(
        info: ItemInfo,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
        rank: Int,
    ) {
        // Done before the coroutine: the drop notifies this handler and then ends the drag, so by
        // the time a suspended body resumed, the folder it came out of would already be forgotten.
        detachFromDragSourceFolder(info)

        scope.launch {
            var targetScreen = screenId

            // Dropping on the transient trailing page turns it into a real, persisted page.
            if (container == Containers.DESKTOP &&
                targetScreen == Workspace.EXTRA_EMPTY_SCREEN_ID
            ) {
                targetScreen = appState.repository.addScreen()
                binding.workspace.commitExtraEmptyScreen(targetScreen)
            }

            val existingView = findViewFor(info)
            existingView?.let { removeViewFromParent(it) }

            info.container = container
            info.screenId = if (container == Containers.DESKTOP) targetScreen else -1L
            info.cellX = cellX
            info.cellY = cellY
            info.rank = rank

            val view = existingView ?: createItemView(info) ?: return@launch
            // The dock has no room for labels; moving the same view between the two has to put
            // the label back.
            (view as? BubbleTextView)?.setLabelVisible(container != Containers.HOTSEAT)

            when (container) {
                Containers.HOTSEAT -> binding.hotseat.addItem(
                    view,
                    -1,
                    CellLayout.LayoutParams(cellX, cellY, info.spanX, info.spanY),
                )

                else -> binding.workspace.addInScreen(view, info)
            }
            view.visibility = View.VISIBLE

            if (info.id == app.auriel.edenlauncher.model.NO_ID) {
                appState.repository.addItem(info)
            } else {
                appState.repository.updateItem(info)
            }
        }
    }

    /**
     * Takes an item that was dragged out of a folder out of that folder's contents.
     *
     * The database row moves with the drop, but the in-memory folder is a separate list, and one
     * left holding an app that is now on the workspace shows it in two places until the next load -
     * and puts it back in the folder on the one after that.
     */
    private fun detachFromDragSourceFolder(info: ItemInfo) {
        val folder = dragSourceFolder ?: return
        val item = info as? ShortcutInfo ?: return
        if (!folder.contents.contains(item)) return
        dragSourceFolder = null
        folder.remove(item)

        // A folder with nothing left in it goes, the same as when the last icon is removed from an
        // open one. Checked here rather than left to the panel's own listener: the panel closed
        // when the drag started and stopped listening on the way out, so by now there is nothing
        // watching the folder but this.
        if (folder.contents.isEmpty()) onItemRemoved(folder)
    }

    override fun onItemRemoved(info: ItemInfo) {
        closeResizeFrame()
        detachFromDragSourceFolder(info)
        scope.launch {
            findViewFor(info)?.let { removeViewFromParent(it) }
            if (info.id != app.auriel.edenlauncher.model.NO_ID) {
                appState.repository.deleteItem(info)
            }
            // The row is gone, so nothing will ever ask for this id again. Left allocated, it is
            // leaked for the life of the install and its provider keeps being told it exists.
            if (info is LauncherAppWidgetInfo) widgetHost.releaseWidgetId(info.appWidgetId)
            if (info is FolderInfo && openFolder?.folderInfo === info) closeFolder()
        }
    }

    override fun onFolderCreationRequested(
        target: ShortcutInfo,
        dropped: ShortcutInfo,
        targetView: View?,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
    ) {
        scope.launch {
            val folder = FolderInfo().apply {
                title = getString(R.string.folder_name)
                this.container = container
                this.screenId = screenId
                this.cellX = cellX
                this.cellY = cellY
                itemType = ItemType.FOLDER
            }
            appState.repository.addItem(folder)

            // The two icons become folder children; their rows move under the folder's id.
            targetView?.let { removeViewFromParent(it) }
            findViewFor(dropped)?.let { removeViewFromParent(it) }

            for ((rank, item) in listOf(target, dropped).withIndex()) {
                item.container = folder.id
                item.screenId = -1L
                item.cellX = -1
                item.cellY = -1
                item.rank = rank
                folder.contents.add(item)
                if (item.id == app.auriel.edenlauncher.model.NO_ID) {
                    appState.repository.addItem(item)
                } else {
                    appState.repository.updateItem(item)
                }
            }

            val icon = createItemView(folder) ?: return@launch
            binding.workspace.addInScreen(icon, folder)
        }
    }

    override fun onItemAddedToFolder(folder: FolderInfo, item: ShortcutInfo) {
        scope.launch {
            findViewFor(item)?.let { removeViewFromParent(it) }

            item.container = folder.id
            item.screenId = -1L
            item.cellX = -1
            item.cellY = -1
            item.rank = folder.contents.size
            folder.add(item)

            if (item.id == app.auriel.edenlauncher.model.NO_ID) {
                appState.repository.addItem(item)
            } else {
                appState.repository.updateItem(item)
            }
        }
    }

    override fun onScreenOrderChanged(screenIds: List<Long>) {
        scope.launch { appState.repository.reorderScreens(screenIds) }
    }

    /**
     * Drops only the transient trailing page once a drag ends.
     *
     * AOSP also swept up every page left empty. Eden does not: a page the user deliberately made
     * empty is a page they wanted, and having the launcher delete it the moment the last icon
     * moves off is exactly the behaviour that makes stock launchers feel like they are arguing
     * with you. Pages go away when the user removes them.
     */
    private fun commitScreenChanges() {
        binding.workspace.removeExtraEmptyScreen()
        scope.launch { appState.repository.reorderScreens(binding.workspace.screenIds) }
    }

    // ---- view lookup -----------------------------------------------------------------------------

    private fun findViewFor(info: ItemInfo): View? {
        binding.hotseat.let { hotseat ->
            for (i in 0 until hotseat.childCount) {
                val child = hotseat.getChildAt(i)
                if (Workspace.viewInfo(child) === info) return child
            }
        }
        val workspace = binding.workspace
        for (i in 0 until workspace.childCount) {
            val page = workspace.getChildAt(i) as? CellLayout ?: continue
            for (j in 0 until page.childCount) {
                val child = page.getChildAt(j)
                if (Workspace.viewInfo(child) === info) return child
            }
        }
        return null
    }

    private fun removeViewFromParent(view: View) {
        (view.parent as? CellLayout)?.removeView(view) ?: run {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        }
    }

    // ---- folders ---------------------------------------------------------------------------------

    private fun openFolder(info: FolderInfo) {
        closeFolder()
        val folder = Folder(this).apply {
            placementHandler = this@Launcher
            onItemClick = { startApp(it, null) }
            onItemLongClick = { item, view -> startDragFromFolder(info, item, view) }
            onClosed = {
                dragController.removeDropTarget(this)
                // Guarded because closing is animated: opening a second folder starts the first
                // one closing, and the callback that arrives afterwards must not tidy away the
                // state that now belongs to the folder the user is looking at.
                if (openFolder === this) openFolder = null
                if (binding.dragLayer.activeFolder === this) {
                    binding.dragLayer.activeFolder = null
                    binding.dragLayer.onTouchOutsideFolder = null
                }
            }
        }
        openFolder = folder
        dragController.addDropTarget(folder)
        folder.open(info, binding.dragLayer)

        // Tapping the home screen around the panel is the obvious way to put it away, and the
        // drag layer is the only view that sees a tap which lands on neither.
        if (appState.preferences.closeFolderOnTapOutside) {
            binding.dragLayer.activeFolder = folder
            binding.dragLayer.onTouchOutsideFolder = { closeFolder() }
        }
    }

    private fun closeFolder() {
        // Cleared before the animation rather than in onClosed, so a second tap during the close
        // does not ask a folder that is already going away to close again.
        binding.dragLayer.activeFolder = null
        binding.dragLayer.onTouchOutsideFolder = null
        openFolder?.close()
        openFolder = null
    }

    /**
     * A long press on an icon inside an open folder.
     *
     * The same two-in-one gesture the workspace uses: hold still for the menu, move to drag the app
     * out onto the home screen. Folders had neither, which made them a place apps could be put and
     * not taken out of - the one arrangement in the launcher the user could not undo.
     */
    private fun startDragFromFolder(folder: FolderInfo, item: ShortcutInfo, view: View): Boolean {
        closeIconOptions()
        dragSourceFolder = folder

        dragController.preparePendingDrag(
            view = view,
            source = binding.workspace,
            info = item,
            hideOriginal = true,
            onPromoted = { closeIconOptions() },
        )
        showFolderIconOptions(view, folder, item)
        return true
    }

    /**
     * The icon menu for an app inside a folder.
     *
     * Two of the workspace actions read wrong in here and are replaced by one that does not: an app
     * in a folder is not loose on a page, so "put it in a new folder" is meaningless, and "remove
     * from home screen" is not what someone reaching into a folder is usually after. What they want
     * is the app back out on the page, which is what [removeFromFolder] does.
     */
    private fun showFolderIconOptions(anchor: View, folder: FolderInfo, item: ShortcutInfo) {
        // Same reasoning as the workspace menu: v0.2.0 had no per-icon menu, so Simple mode has
        // none either and the press goes straight to dragging the app out.
        if (appState.preferences.simpleMode) return

        val popup = IconOptionsPopup(this)
            .addAction(R.string.icon_option_rename) { showRenameDialog(item, anchor) }
            .addAction(R.string.icon_option_take_out_of_folder) { removeFromFolder(folder, item) }
            .addAction(
                R.string.icon_option_icon,
                enabled = item.intent?.component != null,
            ) { showIconChooser(item.intent?.component) }
            .addAction(R.string.icon_option_app_info, enabled = item.intent?.component != null) {
                showAppInfo(item)
            }
            .addAction(R.string.icon_option_remove) {
                // Out of the folder's contents as well as out of the database, or the folder would
                // keep showing an app whose row no longer exists.
                folder.remove(item)
                onItemRemoved(item)
            }
            .addAction(
                R.string.icon_option_uninstall,
                enabled = ButtonDropTarget.uninstallablePackage(item) != null,
            ) { uninstall(item) }

        iconOptions = popup
        // The menu fills the drag layer and dismisses itself on a tap outside its panel. Leaving
        // the folder's own outside-tap hook armed underneath it would swallow the tap that was
        // meant for a menu row, because the drag layer sees it first.
        val folderHook = binding.dragLayer.activeFolder
        binding.dragLayer.activeFolder = null
        popup.showAt(binding.dragLayer, anchor) {
            iconOptions = null
            if (openFolder != null) binding.dragLayer.activeFolder = folderHook
        }
    }

    /**
     * Takes [item] out of [folder] and puts it back on the home screen.
     *
     * Onto the folder's own page when there is room on it, because the app should reappear where
     * the user was looking rather than on whichever screen happens to be leftmost. A full home
     * screen is reported rather than worked around: silently deleting the app, or dropping it three
     * pages away, are both worse than saying there is no room.
     */
    private fun removeFromFolder(folder: FolderInfo, item: ShortcutInfo) {
        val cell = IntArray(2)
        val screenId = binding.workspace.findFreeCell(
            outCell = cell,
            preferredScreenId = folder.screenId,
        )
        if (screenId < 0) {
            Toast.makeText(this, R.string.folder_no_space, Toast.LENGTH_SHORT).show()
            return
        }

        // Removing empties the folder in the model, which is what tells the open panel and the
        // closed tile to redraw - and, if it was the last app, deletes the folder itself.
        folder.remove(item)
        onItemPlaced(
            info = item,
            container = Containers.DESKTOP,
            screenId = screenId,
            cellX = cell[0],
            cellY = cell[1],
            rank = 0,
        )
        binding.workspace.snapToPage(binding.workspace.getScreenIndex(screenId))
    }

    // ---- all apps --------------------------------------------------------------------------------

    private fun openAllApps() {
        if (allApps.isOpen) return
        closeFolder()
        exitOverview()
        allApps.open()
        setHomeContentVisible(false)
        setWallpaperPaused(true)
    }

    private fun closeAllApps() {
        if (!allApps.isOpen) return
        allApps.close()
        setHomeContentVisible(true)
        setWallpaperPaused(false)
    }

    /**
     * Tells a live wallpaper to stop drawing while the app drawer covers it.
     *
     * The system does not treat the wallpaper as hidden here: the launcher window still declares
     * that it shows the wallpaper, so a live one would keep rendering thirty frames a second
     * behind an opaque drawer, for nobody. `sendWallpaperCommand` is the sanctioned way for the
     * home app to say so.
     *
     * Only sent when the drawer is opaque enough to actually hide it. Below that the user chose a
     * see-through drawer precisely so they could watch the wallpaper, and freezing it would look
     * like a bug.
     */
    private fun setWallpaperPaused(paused: Boolean) {
        if (paused && appState.preferences.appDrawerOpacity < OPAQUE_DRAWER_THRESHOLD) return

        val token = window.decorView.windowToken ?: return
        val command = if (paused) {
            GLWallpaperService.COMMAND_PAUSE
        } else {
            GLWallpaperService.COMMAND_RESUME
        }
        runCatching {
            android.app.WallpaperManager.getInstance(this)
                .sendWallpaperCommand(token, command, 0, 0, 0, null)
        }
    }

    /**
     * Fades the home screen out behind the drawer.
     *
     * Without this, a drawer set to low opacity shows the dock and workspace icons through itself
     * and the two grids overlap. AOSP does the same thing through its workspace state animation;
     * the drawer's own transparency is meant to reveal the wallpaper, not the home screen.
     */
    private fun setHomeContentVisible(visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        for (view in arrayOf<View>(binding.workspace, binding.hotseat, binding.pageIndicator)) {
            if (visible) view.visibility = View.VISIBLE
            view.animate()
                .alpha(targetAlpha)
                .setDuration(HOME_FADE_DURATION_MS)
                .withEndAction { if (!visible) view.visibility = View.INVISIBLE }
                .start()
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * Widget updates only arrive while the host is listening.
     *
     * Tied to start and stop rather than to the activity's whole life: a launcher sitting behind
     * another app has nothing to draw updates into, and a provider woken to push RemoteViews at a
     * screen nobody is looking at is exactly the background cost this project is trying to avoid.
     */
    override fun onStart() {
        super.onStart()
        widgetHost.startListeningSafely()
    }

    override fun onStop() {
        widgetHost.stopListeningSafely()
        super.onStop()
    }

    /**
     * Grid settings change the whole view hierarchy, so returning from settings with a different
     * grid recreates the activity rather than trying to reshape the workspace in place.
     */
    override fun onResume() {
        super.onResume()

        applyRotationSetting()

        val signature = appState.preferences.gridSignature
        if (gridSignature == null) {
            gridSignature = signature
        } else if (gridSignature != signature) {
            gridSignature = signature
            appState.onConfigurationChanged()
            // A recreate reloads everything, icons included; nothing more to do here.
            recreate()
            return
        }

        // A changed icon pack needs a reload, not a recreate: the views are all still the right
        // shape, they are only holding the wrong bitmaps. Simple mode rides along in the same
        // signature because turning it on stops packs and custom icons being consulted, which is
        // the same kind of change.
        val icons = appState.preferences.iconPackPackage.orEmpty() +
            if (appState.preferences.simpleMode) "|simple" else ""
        if (iconSignature == null) {
            iconSignature = icons
        } else if (iconSignature != icons) {
            iconSignature = icons
            reloadForIconChange()
        }
    }

    override fun onDestroy() {
        runCatching { getSystemService(LauncherApps::class.java).unregisterCallback(packageWatcher) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_PAGE, binding.workspace.currentPageIndex)
    }

    /**
     * HOME press while already home: dismiss whatever is open, then return to the first page.
     * AOSP does the same, and it is what makes the home key feel like "go back to the top".
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_MAIN) return
        if (allApps.isOpen) {
            closeAllApps()
            return
        }
        exitOverview()
        closeFolder()
        binding.workspace.snapToPage(defaultPageIndex())
    }

    /**
     * The home screen is the root of the back stack: back dismisses overlays and otherwise does
     * nothing. It must never finish the activity, or the system would have to relaunch the
     * launcher on the next HOME press.
     *
     * Android 13+ routes back through [android.window.OnBackInvokedDispatcher], registered in
     * [onCreate]; the deprecated override below is what still runs on Android 10-12.
     */
    private fun onBackRequested() {
        when {
            dragController.isDragging -> dragController.cancelDrag()
            closeResizeFrame() -> Unit
            iconOptions != null -> closeIconOptions()
            binding.workspace.isInOverview -> exitOverview()
            allApps.isOpen -> closeAllApps()
            openFolder != null -> closeFolder()

            // Nothing is open, so back means "take me home" - the same page HOME returns to,
            // which is not necessarily the leftmost one.
            else -> binding.workspace.snapToPage(defaultPageIndex())
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation", "MissingSuperCall")
    override fun onBackPressed() {
        onBackRequested()
    }

    private companion object {
        const val TAG = "Launcher"
        const val STATE_CURRENT_PAGE = "launcher.currentPage"
        const val HOME_FADE_DURATION_MS = 160L
        const val OVERVIEW_CHROME_FADE_MS = 200L

        /** Drawer opacity at or above which the wallpaper behind it is not worth drawing. */
        const val OPAQUE_DRAWER_THRESHOLD = 90

        /**
         * How long to let a burst of package events settle before reloading. Long enough that a
         * restore installing forty apps costs one rebind, short enough that a single install has
         * landed in the drawer before the user can get back to the launcher and look.
         */
        const val PACKAGE_REFRESH_DELAY_MS = 400L

        /** How often to re-check whether a drag has finished before rebinding under it. */
        const val DRAG_SETTLE_POLL_MS = 250L

        /**
         * How many icons the icon pack is applied to between redraws.
         *
         * Small enough that the pack visibly arrives in waves rather than all at the end, large
         * enough that the launcher is not hopping between threads once per icon.
         */
        const val ICON_THEME_BATCH = 12

        const val REQUEST_CUSTOM_ICON = 1
        const val REQUEST_PACK_ICON = 2

        /** The three legs of adding a widget: choose it, be allowed to bind it, configure it. */
        const val REQUEST_PICK_WIDGET = 3
        const val REQUEST_BIND_WIDGET = 4
        const val REQUEST_CONFIGURE_WIDGET = 5
    }
}
