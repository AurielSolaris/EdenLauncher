package app.auriel.edenlauncher

import android.app.Application
import android.content.res.Configuration
import app.auriel.edenlauncher.util.EdenLog

/**
 * Application entry point.
 *
 * Intentionally does almost nothing: the launcher process is started by the system at boot and
 * kept resident, so work done here is work charged against every cold boot. State is built
 * lazily by [LauncherAppState] the first time it is actually needed.
 */
class EdenApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The one exception to the rule above, and it earns it: what runs on this thread is
        // installing an exception handler and opening a channel. Reading, pruning, and writing
        // the file all happen on a background dispatcher, and the crash handler has to be in
        // place before anything else has had the chance to throw.
        EdenLog.install(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LauncherAppState.getInstanceOrNull()?.onConfigurationChanged()
    }
}
