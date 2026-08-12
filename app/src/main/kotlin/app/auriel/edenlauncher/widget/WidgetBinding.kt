package app.auriel.edenlauncher.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.auriel.edenlauncher.util.EdenLog

private const val TAG = "WidgetBinding"

/**
 * The three-step dance between picking a widget and having one on the screen.
 *
 * There is no single call for "add this widget". A launcher has to:
 *
 *  1. allocate an id from its own host, which is just a number until something is bound to it;
 *  2. bind that id to a provider - which succeeds silently for a provider in the launcher's own
 *     package or where the user has already granted binding, and otherwise needs the system's
 *     permission prompt;
 *  3. run the provider's configuration activity, if it declared one, which is where a clock widget
 *     asks which timezone and a weather widget asks which city.
 *
 * Any of the three can fail or be cancelled, and every failure has to end with the id released -
 * an id allocated and then abandoned is never reclaimed, and the provider goes on being told a
 * widget exists that nobody can see. That is why every path through [Launcher] that leaves this
 * flow early calls [LauncherAppWidgetHost.releaseWidgetId].
 */
object WidgetBinding {

    /**
     * Providers the user could actually add, sorted by app name then widget name.
     *
     * Only the current profile: a work-profile widget binds through a different call and belongs
     * with proper multi-profile support rather than smuggled in here.
     */
    fun installedProviders(context: Context): List<AppWidgetProviderInfo> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        return runCatching { manager.installedProviders }
            .onFailure { EdenLog.w(TAG, "could not list providers", it) }
            .getOrDefault(emptyList())
    }

    /** The provider bound to [appWidgetId], or null if nothing is bound to it. */
    fun providerFor(context: Context, appWidgetId: Int): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context)?.getAppWidgetInfo(appWidgetId)

    /**
     * Binds without asking, and reports whether that was allowed.
     *
     * False here is not an error - it is the ordinary answer for a third-party provider the user
     * has not approved yet, and the caller's next move is [bindPermissionIntent].
     */
    fun bindIfAllowed(context: Context, appWidgetId: Int, provider: ComponentName): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return runCatching { manager.bindAppWidgetIdIfAllowed(appWidgetId, provider) }
            .onFailure { EdenLog.w(TAG, "bindAppWidgetIdIfAllowed failed for $provider", it) }
            .getOrDefault(false)
    }

    /** The system's "allow this launcher to add widgets" prompt, started for a result. */
    fun bindPermissionIntent(appWidgetId: Int, provider: ComponentName): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)

    /**
     * Runs the provider's own configuration screen if it declared one.
     *
     * Returns false when there is nothing to configure, which means the widget is ready to place
     * immediately. Started through the host rather than as a plain intent because a configuration
     * activity is usually not exported, and only the host is permitted to launch it.
     */
    fun startConfigureIfNeeded(
        activity: Activity,
        host: LauncherAppWidgetHost,
        appWidgetId: Int,
        info: AppWidgetProviderInfo?,
        requestCode: Int,
    ): Boolean {
        if (info?.configure == null) return false
        return runCatching {
            host.startAppWidgetConfigureActivityForResult(
                activity,
                appWidgetId,
                0,
                requestCode,
                null,
            )
            true
        }.onFailure {
            // A provider can declare a configuration activity and then not ship it. Treating that
            // as "nothing to configure" places an unconfigured widget, which is what the user
            // asked for and is recoverable; refusing to place it is not.
            EdenLog.w(TAG, "configure activity for $appWidgetId would not start", it)
        }.getOrDefault(false)
    }

    /** The label an app's widget should be listed under, falling back to the package name. */
    fun appLabelFor(context: Context, info: AppWidgetProviderInfo): String {
        val packageManager = context.packageManager
        val packageName = info.provider.packageName
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrDefault(packageName)
    }

    /** The widget's own name, as the provider declared it. */
    fun widgetLabelFor(context: Context, info: AppWidgetProviderInfo): String =
        info.loadLabel(context.packageManager).orEmpty().ifEmpty { info.provider.shortClassName }
}
