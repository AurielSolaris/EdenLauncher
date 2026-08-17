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

    /**
     * Whether tapping beside an open folder closes it.
     *
     * On, because it is what the panel looks like it should do: it floats over the home screen with
     * the workspace visible around it, and tapping the part you can still see is the obvious way to
     * put it away. Back still closes a folder either way - this adds a second way, it does not take
     * the first one off anyone who prefers it.
     *
     * Offered as a setting rather than assumed because the gesture costs something: while a folder
     * is open, a tap beside it can no longer reach the icon underneath.
     */
    var closeFolderOnTapOutside: Boolean
        get() = prefs.getBoolean(KEY_FOLDER_TAP_OUTSIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLDER_TAP_OUTSIDE, value).apply()

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
     * Absolute path of the video imported for the video wallpaper, or null if there is none.
     *
     * A path rather than the original content URI: the source lives in another app's storage and
     * can be moved, revoked, or deleted, and a wallpaper that stops working because a photo was
     * tidied up is not acceptable. The transcoded copy is Eden's.
     */
    var videoWallpaperPath: String?
        get() = prefs.getString(KEY_VIDEO_PATH, null)
        set(value) = prefs.edit().putString(KEY_VIDEO_PATH, value).apply()

    /**
     * Whether the home screen follows the device's rotation.
     *
     * Off, which is what almost everyone wants from a launcher: the grid reflows, the dock moves to
     * the edge, and it happens because the phone was put down at an angle rather than because
     * anyone asked. Every app you open from here still rotates as it always did - this is the home
     * screen's own behaviour, not a device setting.
     *
     * Locked with `nosensor` rather than `portrait`, which pins the activity to the device's
     * *natural* orientation. That is portrait on a phone and landscape on some tablets and
     * foldables, so this does not force a tablet to be sideways-wrong.
     */
    var allowRotation: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_ROTATION, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_ROTATION, value).apply()

    /**
     * Simple mode: the launcher as it stood at v0.2.0.
     *
     * A grid, a drawer, and the settings for both - with the wallpapers kept, because a launcher
     * that cannot set your wallpaper is not simpler, it is just less useful. What goes is the icon
     * pack machinery, the per-icon menu, and widgets.
     *
     * Everything it hides stays exactly where it was: renames, custom icons, the chosen icon pack
     * and placed widgets are all still in the database and the preferences, and turning this back
     * off brings every one of them back untouched. It changes what the launcher offers, never what
     * it has stored.
     *
     * What it deliberately does *not* undo is bug fixes. The drawer still notices apps being
     * installed and removed, the paged drawer still has its dots, and the log is still reachable -
     * none of those are features anyone chose, and a user who wants a simpler launcher has not
     * asked for a more broken one or for one they cannot file a bug against.
     */
    var simpleMode: Boolean
        get() = prefs.getBoolean(KEY_SIMPLE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SIMPLE_MODE, value).apply()

    /**
     * Package name of the icon pack in use, or null for the apps' own icons.
     *
     * The package rather than a copy of its contents: an icon pack updates like any other app, and
     * a launcher holding a stale snapshot of one is how themed icons end up not matching the pack
     * the user can see installed.
     */
    var iconPackPackage: String?
        get() = prefs.getString(KEY_ICON_PACK, null)
        set(value) = prefs.edit().putString(KEY_ICON_PACK, value).apply()

    /**
     * Eden's copy of the picture behind the static wallpaper, at full resolution.
     *
     * Kept for the same reason as the video: a content URI belongs to whichever app the picture
     * came from, and it can be revoked, moved, or deleted. Holding the original means the crop can
     * be adjusted next week without going back to the gallery to find the photo again.
     */
    var stillWallpaperSourcePath: String?
        get() = prefs.getString(KEY_STILL_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_STILL_SOURCE, value).apply()

    /**
     * A tile-sized copy of the crop that was actually applied, so the picker can show a still of
     * the static wallpaper next to the still of every live one. Reading the wallpaper back from
     * the system needs a storage permission that a launcher has no business asking for.
     */
    var stillWallpaperThumbPath: String?
        get() = prefs.getString(KEY_STILL_THUMB, null)
        set(value) = prefs.edit().putString(KEY_STILL_THUMB, value).apply()

    /**
     * Whether the video wallpaper plays its sound.
     *
     * Off by default. The audio track is kept in the file either way, so this is a toggle rather
     * than a reason to import the video again.
     */
    var videoWallpaperAudio: Boolean
        get() = prefs.getBoolean(KEY_VIDEO_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_VIDEO_AUDIO, value).apply()

    /**
     * How fast a bundled live wallpaper moves, as a multiplier.
     *
     * Stored as a percentage because that is what the slider deals in. 25% is a barely-moving
     * scene, 200% is brisk. Slower is also cheaper: the frame rate does not change, but a scene
     * you have slowed down is one you are less likely to turn off.
     */
    var liveWallpaperSpeed: Float
        get() = prefs.getInt(KEY_WALLPAPER_SPEED, DEFAULT_WALLPAPER_SPEED)
            .coerceIn(MIN_WALLPAPER_SPEED, MAX_WALLPAPER_SPEED) / 100f
        set(value) = prefs.edit()
            .putInt(
                KEY_WALLPAPER_SPEED,
                (value * 100f).toInt().coerceIn(MIN_WALLPAPER_SPEED, MAX_WALLPAPER_SPEED),
            )
            .apply()

    /** The same value as the percentage the settings slider shows. */
    var liveWallpaperSpeedPercent: Int
        get() = prefs.getInt(KEY_WALLPAPER_SPEED, DEFAULT_WALLPAPER_SPEED)
            .coerceIn(MIN_WALLPAPER_SPEED, MAX_WALLPAPER_SPEED)
        set(value) = prefs.edit()
            .putInt(KEY_WALLPAPER_SPEED, value.coerceIn(MIN_WALLPAPER_SPEED, MAX_WALLPAPER_SPEED))
            .apply()

    /**
     * Whether the music visualiser reads the device's real audio output.
     *
     * Off by default, and that default is the point. On it needs `RECORD_AUDIO`, which lights the
     * microphone indicator and reads, in the permission dialog, exactly like an app asking to
     * listen to you - a lot to ask for a background. Off, the visualiser runs on an invented
     * rhythm that needs no permission at all. Both are offered because which trade is acceptable
     * is not Eden's call to make.
     */
    var visualizerUsesRealAudio: Boolean
        get() = prefs.getBoolean(KEY_VISUALIZER_REAL_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_VISUALIZER_REAL_AUDIO, value).apply()

    /**
     * A name the user gave an app in the drawer, or null if they have not renamed it.
     *
     * Kept in preferences rather than the workspace database on purpose: a drawer entry is not a
     * placed item and has no row of its own. It is rebuilt from `LauncherApps` on every load, so
     * the override has to live somewhere that survives independently of the layout.
     *
     * @param component a flattened [android.content.ComponentName].
     */
    fun appTitleOverride(component: String): String? =
        prefs.getString(KEY_TITLE_OVERRIDE_PREFIX + component, null)

    /** Pass null to drop the override and go back to the app's own name. */
    fun setAppTitleOverride(component: String, title: String?) {
        val key = KEY_TITLE_OVERRIDE_PREFIX + component
        prefs.edit().apply {
            if (title.isNullOrBlank()) remove(key) else putString(key, title)
        }.apply()
    }

    /** True when at least one app has been renamed, so the loader can skip the lookup entirely. */
    fun hasAnyTitleOverride(): Boolean =
        prefs.all.keys.any { it.startsWith(KEY_TITLE_OVERRIDE_PREFIX) }

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

        const val MIN_WALLPAPER_SPEED = 25
        const val MAX_WALLPAPER_SPEED = 200
        const val DEFAULT_WALLPAPER_SPEED = 100

        private const val FILE_NAME = "eden_prefs"
        private const val KEY_DRAWER_MODE = "app_drawer_mode"
        private const val KEY_DRAWER_OPACITY = "app_drawer_opacity"
        private const val KEY_GRID_COLUMNS = "workspace_columns"
        private const val KEY_GRID_ROWS = "workspace_rows"
        private const val KEY_DRAWER_COLUMNS = "drawer_columns"
        private const val KEY_HOTSEAT_ICONS = "hotseat_icons"
        private const val KEY_DEFAULT_SCREEN = "default_screen_id"
        private const val KEY_FOLDER_TAP_OUTSIDE = "folder_close_on_tap_outside"
        private const val KEY_SHOW_LABELS = "show_workspace_labels"
        private const val KEY_ICON_SIZE_PERCENT = "icon_size_percent"
        private const val KEY_ICON_PAD_VERTICAL = "icon_padding_vertical"
        private const val KEY_ICON_PAD_HORIZONTAL = "icon_padding_horizontal"
        private const val KEY_ICON_LABEL_SPACING = "icon_label_spacing"
        private const val KEY_VIDEO_PATH = "video_wallpaper_path"
        private const val KEY_ICON_PACK = "icon_pack_package"
        private const val KEY_SIMPLE_MODE = "simple_mode"
        private const val KEY_ALLOW_ROTATION = "allow_rotation"
        private const val KEY_STILL_SOURCE = "still_wallpaper_source"
        private const val KEY_STILL_THUMB = "still_wallpaper_thumb"
        private const val KEY_VIDEO_AUDIO = "video_wallpaper_audio"
        private const val KEY_WALLPAPER_SPEED = "live_wallpaper_speed"
        private const val KEY_VISUALIZER_REAL_AUDIO = "visualizer_real_audio"
        private const val KEY_TITLE_OVERRIDE_PREFIX = "app_title."

        private const val DEFAULT_DRAWER_OPACITY = 92
        private const val DEFAULT_ICON_SIZE_PERCENT = 100
        private const val DEFAULT_ICON_PAD_VERTICAL = 10
        private const val DEFAULT_ICON_PAD_HORIZONTAL = 4
        private const val DEFAULT_ICON_LABEL_SPACING = 6
    }
}
