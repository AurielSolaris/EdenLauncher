package app.auriel.edenlauncher.model

import android.content.ComponentName

/** A hosted app widget placed on the workspace. Ported from `LauncherAppWidgetInfo` (AOSP 7). */
class LauncherAppWidgetInfo() : ItemInfo() {

    init {
        itemType = ItemType.APP_WIDGET
        spanX = -1
        spanY = -1
    }

    /** Id allocated by [android.appwidget.AppWidgetHost], or [NO_WIDGET_ID]. */
    var appWidgetId: Int = NO_WIDGET_ID

    var providerName: ComponentName? = null

    /** Bitmask of [WidgetRestoreStatus]. */
    var restoreStatus: Int = WidgetRestoreStatus.RESTORE_COMPLETED

    /** Install progress [0, 100] while the provider package is still downloading. */
    var installProgress: Int = -1

    constructor(appWidgetId: Int, provider: ComponentName) : this() {
        this.appWidgetId = appWidgetId
        providerName = provider
    }

    val isRestored: Boolean
        get() = restoreStatus != WidgetRestoreStatus.RESTORE_COMPLETED

    companion object {
        const val NO_WIDGET_ID = -1

        /** Set on [appWidgetId] for a custom widget provided by the launcher itself. */
        const val CUSTOM_WIDGET_ID = -100
    }
}

/** Restore progress flags for [LauncherAppWidgetInfo.restoreStatus]. */
object WidgetRestoreStatus {
    const val RESTORE_COMPLETED = 0

    /** The provider is installed but the widget id has not been bound yet. */
    const val FLAG_ID_NOT_VALID = 1

    /** The provider package is not installed yet. */
    const val FLAG_PROVIDER_NOT_READY = 1 shl 1

    /** The user still has to allow binding (`BIND_APPWIDGET` permission dialog). */
    const val FLAG_UI_NOT_READY = 1 shl 2

    /** The id was allocated by this launcher rather than restored from a backup. */
    const val FLAG_ID_ALLOCATED = 1 shl 3
}
