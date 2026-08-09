package app.auriel.edenlauncher

import android.app.Application
import android.content.res.Configuration

/**
 * Application entry point.
 *
 * Intentionally does almost nothing: the launcher process is started by the system at boot and
 * kept resident, so work done here is work charged against every cold boot. State is built
 * lazily by [LauncherAppState] the first time it is actually needed.
 */
class EdenApplication : Application() {

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LauncherAppState.getInstanceOrNull()?.onConfigurationChanged()
    }
}
