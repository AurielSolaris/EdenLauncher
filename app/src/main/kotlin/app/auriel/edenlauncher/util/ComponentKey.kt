package app.auriel.edenlauncher.util

import android.content.ComponentName
import android.os.UserHandle

/**
 * Identity of a launchable activity: a component plus the profile it belongs to.
 *
 * Ported from `ComponentKey` (AOSP 7). The same component in a work profile is a different app to
 * the launcher, so the user handle is part of the key, not metadata hanging off it.
 */
data class ComponentKey(val componentName: ComponentName, val user: UserHandle)
