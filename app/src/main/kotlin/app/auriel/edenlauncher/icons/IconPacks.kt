package app.auriel.edenlauncher.icons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Finds the icon packs installed on the device.
 *
 * There is no Android API for this and no single intent everyone agreed on, so a pack advertises
 * itself with whichever action its author's launcher of choice used. Querying all of the common
 * ones and merging by package is what makes the list match what other launchers show; checking only
 * one action silently loses a third of the packs someone has actually installed.
 */
object IconPacks {

    /** An installed pack, as the chooser needs to present it. */
    class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private val THEME_ACTIONS = arrayOf(
        "org.adw.launcher.THEMES",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "ch.deletescape.lawnchair.ICONPACK",
    )

    /**
     * Every pack on the device, sorted by name.
     *
     * Hits the package manager several times, so call it off the main thread or from a screen that
     * is being built rather than from a draw pass.
     */
    fun installed(context: Context): List<Entry> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, Entry>(8)

        for (action in THEME_ACTIONS) {
            val matches = runCatching {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            }.getOrNull().orEmpty()

            for (match in matches) {
                val packageName = match.activityInfo?.packageName ?: continue
                if (packageName in seen) continue
                seen[packageName] = Entry(
                    packageName = packageName,
                    label = runCatching { match.loadLabel(pm).toString() }.getOrNull()
                        ?: packageName,
                    icon = runCatching { match.loadIcon(pm) }.getOrNull(),
                )
            }
        }

        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** The label to show for a stored package name, or null when it is no longer installed. */
    fun labelFor(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}
