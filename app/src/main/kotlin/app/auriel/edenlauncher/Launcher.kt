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
import app.auriel.edenlauncher.widget.WidgetPlaceholderView
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

    /** The app whose icon is being chosen, held across the trip to the document picker. */
    private var pendingIconComponent: android.content.ComponentName? = null

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
                binding.dropTargetBar.setDragInProgress(true, info)
                binding.workspace.addExtraEmptyScreen()
            }

            override fun onDragEnd() {
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
        binding.workspace.onSwipeUp = { openAllApps() }
        // Long press on empty space zooms the workspace out, as AOSP Launcher3 does. Page
        // management happens on the cards themselves rather than in a list of words.
        binding.workspace.onEmptySpaceLongPress = { enterOverview() }

        binding.workspace.onOverviewPageSelected = { index ->
            binding.workspace.snapToPage(index)
            exitOverview()
        }
        binding.workspace.onOverviewSetHome = { screenId -> setHomePage(screenId) }
        binding.workspace.onOverviewRemovePage = { screenId -> removePage(screenId) }

        binding.overviewPanel.onAddPageClick = { addPage() }
        binding.overviewPanel.onWallpaperClick = { openWallpaperPicker() }
        binding.overviewPanel.onWidgetsClick = {
            Toast.makeText(this, R.string.pin_widget_unsupported, Toast.LENGTH_SHORT).show()
        }
        binding.overviewPanel.onSettingsClick = {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ---- overview ---------------------------------------------------------------------------------

    private fun enterOverview() {
        if (binding.workspace.isInOverview) return
        closeFolder()
        closeAllApps()
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
        binding.workspace.setHomeScreen(screenId)
        updateHomePageIndicator()
        Toast.makeText(this, R.string.home_page_set, Toast.LENGTH_SHORT).show()
    }

    /** Index of the page HOME returns to, falling back to the leftmost one. */
    private fun defaultPageIndex(): Int {
        val screenId = appState.preferences.defaultScreenId
        if (screenId == LauncherPrefs.NO_DEFAULT_SCREEN) return 0
        return binding.workspace.getScreenIndex(screenId).takeIf { it >= 0 } ?: 0
    }

    private fun updateHomePageIndicator() {
        val screenId = appState.preferences.defaultScreenId
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
            bind(results, if (restoredPage >= 0) restoredPage else defaultPageIndex())
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
     */
    private fun bind(results: LauncherModel.LoaderResults, targetPage: Int) {
        closeFolder()
        binding.hotseat.removeAllViews()

        binding.workspace.bindScreens(results.screenIds)
        bindWorkspaceItems(results.workspaceItems)
        bindHotseatItems(results.hotseatItems)
        allApps.setApps(results.allApps)

        updateHomePageIndicator()
        val lastPage = (binding.workspace.childCount - 1).coerceAtLeast(0)
        binding.workspace.setCurrentPage(targetPage.coerceIn(0, lastPage))
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

        // Widgets are modelled and stored, but hosting is not implemented yet. A placeholder tile
        // keeps the item visible and its database row intact until hosting lands.
        is LauncherAppWidgetInfo -> WidgetPlaceholderView(this).apply { applyFrom(item) }

        else -> null
    }

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
        val uninstallablePackage = ButtonDropTarget.uninstallablePackage(info)

        val popup = IconOptionsPopup(this)
            .addAction(R.string.icon_option_rename) { showRenameDialog(info, anchor) }

        // Folders already are folders; only a loose icon can be put into a new one.
        if (info is ShortcutInfo && info !is FolderInfo) {
            popup.addAction(R.string.icon_option_new_folder) { createFolderAround(info) }
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

    override fun onItemRemoved(info: ItemInfo) {
        scope.launch {
            findViewFor(info)?.let { removeViewFromParent(it) }
            if (info.id != app.auriel.edenlauncher.model.NO_ID) {
                appState.repository.deleteItem(info)
            }
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
            onClosed = {
                openFolder = null
                dragController.removeDropTarget(this)
            }
        }
        openFolder = folder
        dragController.addDropTarget(folder)
        folder.open(info, binding.dragLayer)
    }

    private fun closeFolder() {
        openFolder?.close()
        openFolder = null
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
     * Grid settings change the whole view hierarchy, so returning from settings with a different
     * grid recreates the activity rather than trying to reshape the workspace in place.
     */
    override fun onResume() {
        super.onResume()

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
        // shape, they are only holding the wrong bitmaps.
        val icons = appState.preferences.iconPackPackage.orEmpty()
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

        const val REQUEST_CUSTOM_ICON = 1
        const val REQUEST_PACK_ICON = 2
    }
}
