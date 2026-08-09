package app.auriel.edenlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import app.auriel.edenlauncher.data.LauncherRepository
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ItemType
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.util.ComponentKey
import app.auriel.edenlauncher.util.IconCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads everything the launcher displays: the installed app list for the drawer, and the placed
 * items for the workspace, hotseat, and folders.
 *
 * Ported from `LauncherModel` (AOSP 7), which ran a hand-rolled loader thread with a task queue
 * and a bind-in-batches protocol. That whole machine collapses into one suspend function: the
 * caller awaits a [LoaderResults] and binds it. Cancellation comes free with the activity scope,
 * which is what AOSP's `mStopped` flag was emulating.
 *
 * Widget loading is deliberately left as a placeholder - see [loadWidgetPlaceholders].
 */
class LauncherModel(
    private val context: Context,
    private val repository: LauncherRepository,
    private val iconCache: IconCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Everything a bind pass needs, resolved and icon-loaded. */
    class LoaderResults(
        /** Page ids, left to right. */
        val screenIds: List<Long>,
        /** Items with `container == DESKTOP`. */
        val workspaceItems: List<ItemInfo>,
        /** Items with `container == HOTSEAT`, ordered by rank. */
        val hotseatItems: List<ItemInfo>,
        /** Every launchable activity, sorted for the drawer. */
        val allApps: List<AppInfo>,
    )

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Resolved activities, keyed for fast icon and title lookup during binding. */
    private val activityCache = HashMap<ComponentKey, LauncherActivityInfo>(128)

    /**
     * One full load. Safe to call again; it re-reads both the package manager and the database.
     *
     * On a first run, the workspace is empty, so a default layout is written before the read so
     * the user is not handed a blank home screen.
     */
    suspend fun load(): LoaderResults = withContext(io) {
        val apps = loadAllApps()

        repository.ensureDefaultScreen()
        if (repository.isEmpty()) {
            writeDefaultLayout(apps)
        }

        val data = repository.loadWorkspace()
        val screenIds = repository.loadScreenIds()

        // Resolve titles and icons for placed items before the bind, so the workspace never
        // renders a frame of blank squares.
        resolveIcons(data.workspaceItems)
        resolveIcons(data.hotseatItems)

        LoaderResults(
            screenIds = screenIds,
            workspaceItems = data.workspaceItems,
            hotseatItems = data.hotseatItems,
            allApps = apps,
        )
    }

    /** All launchable activities across every profile the user has, sorted by label. */
    private fun loadAllApps(): List<AppInfo> {
        activityCache.clear()
        val apps = ArrayList<AppInfo>(196)

        for (user in userManager.userProfiles) {
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot query activities for $user", e)
                continue
            }

            for (activity in activities) {
                activityCache[ComponentKey(activity.componentName, user)] = activity
                apps.add(
                    AppInfo(activity, user).also {
                        it.icon = iconCache.getIcon(activity, user)
                    },
                )
            }
        }

        apps.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title?.toString().orEmpty() })
        return apps
    }

    /**
     * Fills icons and titles on placed items from the resolved activity list.
     *
     * An item whose target is gone (uninstalled while the launcher was not running) keeps its
     * stored title and gets no icon; it is flagged disabled rather than deleted, because the app
     * may come back on the next restore or SD-card mount.
     */
    private fun resolveIcons(items: List<ItemInfo>) {
        for (item in items) {
            when (item) {
                is FolderInfo -> resolveIcons(item.contents)
                is ShortcutInfo -> resolveShortcutIcon(item)
                else -> Unit
            }
        }
    }

    private fun resolveShortcutIcon(item: ShortcutInfo) {
        if (item.icon != null) return
        val component = item.targetComponent ?: return
        val activity = activityCache[ComponentKey(component, item.user)]
        if (activity == null) {
            item.disabledFlags = item.disabledFlags or
                app.auriel.edenlauncher.model.DisabledReason.NOT_AVAILABLE
            return
        }
        item.icon = iconCache.getIcon(activity, item.user)
        if (item.title.isNullOrEmpty()) item.title = activity.label
    }

    /**
     * First-run layout: the dock is filled with the handset staples, and the first workspace page
     * is left clear so the wallpaper is visible. Everything else lives in the drawer.
     *
     * AOSP parsed this from `default_workspace_Nx N.xml`; resolving intents at runtime instead
     * means the dock is populated with apps that actually exist on this device.
     */
    private suspend fun writeDefaultLayout(apps: List<AppInfo>) {
        val idp = LauncherAppState.getInstance(context).invariantDeviceProfile
        val slots = idp.numHotseatIcons
        val chosen = pickDockApps(apps, slots)

        for ((rank, app) in chosen.withIndex()) {
            val shortcut = app.toShortcut().apply {
                container = Containers.HOTSEAT
                screenId = -1
                cellX = rank
                cellY = 0
                this.rank = rank
                itemType = ItemType.APPLICATION
            }
            repository.addItem(shortcut)
        }
    }

    /**
     * Picks dock apps by resolving the common handset roles, then tops up from the app list so
     * the dock is never half empty on a device without, say, a dialer.
     */
    private fun pickDockApps(apps: List<AppInfo>, slots: Int): List<AppInfo> {
        val byPackage = HashMap<String, AppInfo>(apps.size)
        for (app in apps) {
            val pkg = app.componentName?.packageName ?: continue
            byPackage.putIfAbsent(pkg, app)
        }

        val chosen = LinkedHashSet<AppInfo>(slots)
        for (role in DOCK_ROLES) {
            if (chosen.size >= slots) break
            val match = apps.firstOrNull { app ->
                app.componentName?.packageName?.contains(role, ignoreCase = true) == true
            }
            if (match != null) chosen.add(match)
        }

        for (app in apps) {
            if (chosen.size >= slots) break
            chosen.add(app)
        }
        return chosen.toList()
    }

    /**
     * Placeholder for widget loading.
     *
     * Widgets need an `AppWidgetHost` owned by the activity, a provider list from
     * `AppWidgetManager`, and a bind-permission flow; the model half of that lands with widget
     * hosting. [app.auriel.edenlauncher.model.LauncherAppWidgetInfo] and the `appWidgetId` /
     * `appWidgetProvider` columns already exist so stored widgets survive until then.
     */
    @Suppress("UnusedParameter")
    fun loadWidgetPlaceholders(): List<ItemInfo> = emptyList()

    /** The activity behind a placed item, or null when it is no longer installed. */
    fun activityFor(component: ComponentName, user: UserHandle): LauncherActivityInfo? =
        activityCache[ComponentKey(component, user)]

    private companion object {
        const val TAG = "LauncherModel"

        /** Package-name fragments for the usual dock line-up, in priority order. */
        val DOCK_ROLES = arrayOf("dialer", "contacts", "messaging", "mms", "browser", "chrome", "camera")
    }
}
