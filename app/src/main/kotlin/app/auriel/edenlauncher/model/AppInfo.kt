package app.auriel.edenlauncher.model

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.os.UserHandle

/**
 * An installed activity as shown in the app drawer. Unlike [ShortcutInfo] this is never persisted:
 * it is rebuilt from [android.content.pm.LauncherApps] on every load.
 */
class AppInfo() : ItemInfo() {

    init {
        itemType = ItemType.APPLICATION
    }

    var componentName: ComponentName? = null
    var launchIntent: Intent? = null
    var icon: Bitmap? = null
    var disabledFlags: Int = 0

    /**
     * The name the app gives itself, kept even when [title] has been overridden by the user.
     *
     * Search matches against both. Renaming an app in a drawer that lists every app on the phone
     * would otherwise make it unfindable under the only name you might remember - which is a fine
     * way to lose an app you renamed six months ago and have not opened since.
     */
    var originalTitle: CharSequence? = null
        private set

    constructor(activity: LauncherActivityInfo, user: UserHandle) : this() {
        this.user = user
        componentName = activity.componentName
        title = activity.label
        originalTitle = activity.label
        launchIntent = makeLaunchIntent(activity.componentName)
    }

    override val intent: Intent? get() = launchIntent

    override val isDisabled: Boolean get() = disabledFlags != 0

    /** A [ShortcutInfo] copy suitable for dropping onto the workspace. */
    fun toShortcut(): ShortcutInfo = ShortcutInfo(this)

    companion object {
        /**
         * The canonical launcher intent: an explicit MAIN/LAUNCHER intent with
         * `FLAG_ACTIVITY_NEW_TASK` and `RESET_TASK_IF_NEEDED`, matching AOSP Launcher3.
         */
        fun makeLaunchIntent(component: ComponentName): Intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
    }
}
