package app.auriel.edenlauncher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import app.auriel.edenlauncher.util.EdenLog

private const val TAG = "WidgetHost"

/**
 * Eden's [AppWidgetHost].
 *
 * The host is the launcher's end of the widget contract: it allocates ids, it is what the system
 * pushes RemoteViews updates to, and it is what has to be told to stop listening when the launcher
 * is not on screen. A widget whose host never stops listening keeps its provider being woken for
 * updates nobody can see, which on a 4 GB phone is exactly the kind of background cost this
 * project is trying not to have.
 *
 * [hostId] is namespaced per package, so the value only has to be stable across launches of Eden -
 * change it and every allocated id is orphaned and every placed widget comes back blank.
 */
class LauncherAppWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {

    /** Called when providers are installed, removed, or updated. */
    var onProvidersChanged: (() -> Unit)? = null

    private var listening = false

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = LauncherAppWidgetHostView(context)

    override fun onProvidersChanged() {
        super.onProvidersChanged()
        onProvidersChanged?.invoke()
    }

    /**
     * Both of these talk to the system server, and both are documented by AOSP as able to throw
     * when it is restarting. A launcher that crashes because the system happened to be busy is
     * worse than one that misses a widget update, so they are guarded and logged.
     */
    fun startListeningSafely() {
        if (listening) return
        runCatching { startListening() }
            .onSuccess { listening = true }
            .onFailure { EdenLog.w(TAG, "startListening failed", it) }
    }

    fun stopListeningSafely() {
        if (!listening) return
        listening = false
        runCatching { stopListening() }
            .onFailure { EdenLog.w(TAG, "stopListening failed", it) }
    }

    /**
     * Releases an id back to the system.
     *
     * Has to be called whenever a widget leaves the workspace for good. Ids that are allocated and
     * then forgotten are never reclaimed, and the provider keeps being told the widget exists.
     */
    fun releaseWidgetId(appWidgetId: Int) {
        if (appWidgetId < 0) return
        runCatching { deleteAppWidgetId(appWidgetId) }
            .onFailure { EdenLog.w(TAG, "deleteAppWidgetId($appWidgetId) failed", it) }
    }

    private companion object {
        /**
         * Kept at AOSP Launcher3's value. Host ids are scoped to the package, so this collides with
         * nothing; matching the original just makes the two readable side by side.
         */
        const val HOST_ID = 1024
    }
}
