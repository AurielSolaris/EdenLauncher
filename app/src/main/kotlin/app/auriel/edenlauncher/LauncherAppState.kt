package app.auriel.edenlauncher

import android.content.Context
import app.auriel.edenlauncher.data.EdenDatabase
import app.auriel.edenlauncher.data.LauncherRepository
import app.auriel.edenlauncher.device.InvariantDeviceProfile
import app.auriel.edenlauncher.settings.LauncherPrefs
import app.auriel.edenlauncher.util.IconCache
import app.auriel.edenlauncher.util.UserCache
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide launcher state: the device grid, the database, and the wallpaper-changed flag.
 *
 * Ported from `LauncherAppState` (AOSP 7), with two deliberate changes:
 *  - it holds the application context explicitly instead of a static that outlives the process
 *    contract, so an activity leak is impossible by construction;
 *  - everything expensive is created lazily, because a launcher's cold start is the one number
 *    a user on a 2-core device actually feels.
 */
class LauncherAppState private constructor(private val context: Context) {

    val userCache: UserCache by lazy { UserCache(context) }

    val repository: LauncherRepository by lazy {
        LauncherRepository(EdenDatabase.getInstance(context).launcherDao(), userCache)
    }

    val iconCache: IconCache by lazy {
        IconCache(context, invariantDeviceProfile.iconBitmapSizePx)
    }

    val model: LauncherModel by lazy { LauncherModel(context, repository, iconCache) }

    val preferences: LauncherPrefs by lazy { LauncherPrefs(context) }

    /** Grid metrics. Replaced wholesale when the configuration changes. */
    @Volatile
    var invariantDeviceProfile: InvariantDeviceProfile = InvariantDeviceProfile.create(context)
        private set

    private val wallpaperChanged = AtomicBoolean(false)

    /** Called by `WallpaperChangedReceiver` in Phase 3. */
    fun onWallpaperChanged() {
        wallpaperChanged.set(true)
    }

    /** Consumes the flag: true exactly once per wallpaper change. */
    fun consumeWallpaperChanged(): Boolean = wallpaperChanged.getAndSet(false)

    /** Recomputes grid metrics after a density, size, or orientation change. */
    fun onConfigurationChanged() {
        invariantDeviceProfile = InvariantDeviceProfile.create(context)
        // Cached icons were rasterised for the old icon size.
        iconCache.clear()
    }

    companion object {
        @Volatile
        private var instance: LauncherAppState? = null

        fun getInstance(context: Context): LauncherAppState =
            instance ?: synchronized(this) {
                instance ?: LauncherAppState(context.applicationContext).also { instance = it }
            }

        /** Null when nothing has touched the state yet; used to avoid forcing initialisation. */
        fun getInstanceOrNull(): LauncherAppState? = instance
    }
}
