package app.auriel.edenlauncher.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** How the app drawer is navigated. Stored by name so the value survives reordering. */
enum class AppDrawerMode {
    /** Continuous vertical grid, flung up and down. ASUS ZenUI / modern Pixel style. */
    VERTICAL,

    /** Fixed grid pages, swiped left and right. Samsung One UI / classic Launcher3 style. */
    HORIZONTAL,
    ;

    companion object {
        fun fromName(name: String?): AppDrawerMode =
            entries.firstOrNull { it.name == name } ?: VERTICAL
    }
}

/**
 * User settings.
 *
 * Backed by [SharedPreferences] rather than DataStore on purpose: the launcher reads these on the
 * critical path of a cold start, and `SharedPreferences` is already loaded and synchronous, with
 * no extra dependency and no coroutine hop. Observers get a [Flow] anyway.
 */
class LauncherPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var appDrawerMode: AppDrawerMode
        get() = AppDrawerMode.fromName(prefs.getString(KEY_DRAWER_MODE, null))
        set(value) = prefs.edit().putString(KEY_DRAWER_MODE, value.name).apply()

    /**
     * Drawer background opacity, 0 (fully transparent, wallpaper shows through) to 100 (opaque).
     */
    var appDrawerOpacity: Int
        get() = prefs.getInt(KEY_DRAWER_OPACITY, DEFAULT_DRAWER_OPACITY).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_DRAWER_OPACITY, value.coerceIn(0, 100)).apply()

    /**
     * Workspace grid overrides. Zero means "use the size measured for this screen"; anything else
     * wins over the device profile.
     */
    var workspaceColumns: Int
        get() = prefs.getInt(KEY_GRID_COLUMNS, AUTO)
        set(value) = prefs.edit().putInt(KEY_GRID_COLUMNS, clampGrid(value)).apply()

    var workspaceRows: Int
        get() = prefs.getInt(KEY_GRID_ROWS, AUTO)
        set(value) = prefs.edit().putInt(KEY_GRID_ROWS, clampGrid(value)).apply()

    /** Icons in the dock (the bottom row); zero follows the measured profile. */
    var hotseatIcons: Int
        get() = prefs.getInt(KEY_HOTSEAT_ICONS, AUTO)
        set(value) = prefs.edit().putInt(KEY_HOTSEAT_ICONS, clampGrid(value)).apply()

    /** Columns in the app drawer; zero follows the workspace. */
    var drawerColumns: Int
        get() = prefs.getInt(KEY_DRAWER_COLUMNS, AUTO)
        set(value) = prefs.edit().putInt(KEY_DRAWER_COLUMNS, clampGrid(value)).apply()

    /**
     * Screen id the launcher returns to on HOME, or [NO_DEFAULT_SCREEN] for the leftmost page.
     *
     * Stored as an id rather than an index so reordering pages does not silently move which one
     * is home.
     */
    var defaultScreenId: Long
        get() = prefs.getLong(KEY_DEFAULT_SCREEN, NO_DEFAULT_SCREEN)
        set(value) = prefs.edit().putLong(KEY_DEFAULT_SCREEN, value).apply()

    /** Whether workspace icons show their label. */
    var showWorkspaceLabels: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LABELS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LABELS, value).apply()

    /**
     * Icon size as a percentage of the size measured for this screen. Lets a user fit a denser
     * grid without the icons colliding, or make them bigger on a sparse one.
     */
    var iconSizePercent: Int
        get() = prefs.getInt(KEY_ICON_SIZE_PERCENT, DEFAULT_ICON_SIZE_PERCENT)
            .coerceIn(MIN_ICON_PERCENT, MAX_ICON_PERCENT)
        set(value) = prefs.edit()
            .putInt(KEY_ICON_SIZE_PERCENT, value.coerceIn(MIN_ICON_PERCENT, MAX_ICON_PERCENT))
            .apply()

    /** Vertical padding inside each icon cell, in dp: the gap between rows. */
    var iconPaddingVerticalDp: Int
        get() = prefs.getInt(KEY_ICON_PAD_VERTICAL, DEFAULT_ICON_PAD_VERTICAL).coerceIn(0, MAX_ICON_PADDING)
        set(value) = prefs.edit()
            .putInt(KEY_ICON_PAD_VERTICAL, value.coerceIn(0, MAX_ICON_PADDING)).apply()

    /** Horizontal padding inside each icon cell, in dp: the gap between columns. */
    var iconPaddingHorizontalDp: Int
        get() = prefs.getInt(KEY_ICON_PAD_HORIZONTAL, DEFAULT_ICON_PAD_HORIZONTAL).coerceIn(0, MAX_ICON_PADDING)
        set(value) = prefs.edit()
            .putInt(KEY_ICON_PAD_HORIZONTAL, value.coerceIn(0, MAX_ICON_PADDING)).apply()

    /** Gap between an icon and its label, in dp. */
    var iconLabelSpacingDp: Int
        get() = prefs.getInt(KEY_ICON_LABEL_SPACING, DEFAULT_ICON_LABEL_SPACING).coerceIn(0, MAX_ICON_PADDING)
        set(value) = prefs.edit()
            .putInt(KEY_ICON_LABEL_SPACING, value.coerceIn(0, MAX_ICON_PADDING)).apply()

    /**
     * A token that changes whenever something requiring a full rebind changes.
     *
     * Cheaper and less error-prone than wiring a listener per setting: the launcher compares this
     * on resume and recreates itself only when it actually differs.
     */
    val gridSignature: String
        get() = listOf(
            workspaceColumns,
            workspaceRows,
            hotseatIcons,
            drawerColumns,
            showWorkspaceLabels,
            iconSizePercent,
            iconPaddingVerticalDp,
            iconPaddingHorizontalDp,
            iconLabelSpacingDp,
        ).joinToString(":")

    private fun clampGrid(value: Int): Int = if (value <= 0) AUTO else value.coerceIn(MIN_GRID, MAX_GRID)

    /** Emits the current mode immediately, then on every change. */
    fun appDrawerModeFlow(): Flow<AppDrawerMode> = keyFlow(KEY_DRAWER_MODE) { appDrawerMode }

    fun appDrawerOpacityFlow(): Flow<Int> = keyFlow(KEY_DRAWER_OPACITY) { appDrawerOpacity }

    private fun <T> keyFlow(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        /** Grid value meaning "decide from the screen size". */
        const val AUTO = 0

        const val MIN_GRID = 3
        const val MAX_GRID = 8

        /** No page has been chosen as home; the leftmost one is used. */
        const val NO_DEFAULT_SCREEN = -1L

        const val MIN_ICON_PERCENT = 60
        const val MAX_ICON_PERCENT = 140
        const val MAX_ICON_PADDING = 24

        private const val FILE_NAME = "eden_prefs"
        private const val KEY_DRAWER_MODE = "app_drawer_mode"
        private const val KEY_DRAWER_OPACITY = "app_drawer_opacity"
        private const val KEY_GRID_COLUMNS = "workspace_columns"
        private const val KEY_GRID_ROWS = "workspace_rows"
        private const val KEY_DRAWER_COLUMNS = "drawer_columns"
        private const val KEY_HOTSEAT_ICONS = "hotseat_icons"
        private const val KEY_DEFAULT_SCREEN = "default_screen_id"
        private const val KEY_SHOW_LABELS = "show_workspace_labels"
        private const val KEY_ICON_SIZE_PERCENT = "icon_size_percent"
        private const val KEY_ICON_PAD_VERTICAL = "icon_padding_vertical"
        private const val KEY_ICON_PAD_HORIZONTAL = "icon_padding_horizontal"
        private const val KEY_ICON_LABEL_SPACING = "icon_label_spacing"

        private const val DEFAULT_DRAWER_OPACITY = 92
        private const val DEFAULT_ICON_SIZE_PERCENT = 100
        private const val DEFAULT_ICON_PAD_VERTICAL = 10
        private const val DEFAULT_ICON_PAD_HORIZONTAL = 4
        private const val DEFAULT_ICON_LABEL_SPACING = 6
    }
}
