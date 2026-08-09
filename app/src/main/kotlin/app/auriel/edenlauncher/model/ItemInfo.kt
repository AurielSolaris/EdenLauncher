package app.auriel.edenlauncher.model

import android.content.Intent
import android.os.Process
import android.os.UserHandle

/** Sentinel used for "not persisted yet" / "no parent". Mirrors `ItemInfo.NO_ID`. */
const val NO_ID: Long = -1L

/**
 * Well-known container ids. Positive values are folder row ids.
 *
 * Ported from `LauncherSettings.Favorites` in AOSP Launcher3.
 */
object Containers {
    const val DESKTOP: Long = -100L
    const val HOTSEAT: Long = -101L

    fun toDebugString(container: Long): String = when (container) {
        DESKTOP -> "desktop"
        HOTSEAT -> "hotseat"
        else -> container.toString()
    }
}

/**
 * Item kinds persisted in the `favorites` table. [code] values are wire-compatible with
 * AOSP Launcher3 so an imported database keeps its meaning.
 */
enum class ItemType(val code: Int) {
    APPLICATION(0),
    SHORTCUT(1),
    FOLDER(2),
    APP_WIDGET(4),
    CUSTOM_APP_WIDGET(5),
    DEEP_SHORTCUT(6),
    ;

    companion object {
        /** Allocation-free lookup; compiles to a tableswitch. */
        fun fromCode(code: Int): ItemType? = when (code) {
            0 -> APPLICATION
            1 -> SHORTCUT
            2 -> FOLDER
            4 -> APP_WIDGET
            5 -> CUSTOM_APP_WIDGET
            6 -> DEEP_SHORTCUT
            else -> null
        }
    }
}

/**
 * Base model for anything that can live on the workspace, in the hotseat, or inside a folder.
 *
 * Deliberately a mutable class rather than a `data class`: drag & drop rewrites cell coordinates
 * in place thousands of times per gesture, and copying a new instance per frame is exactly the
 * allocation churn low-end devices cannot afford.
 */
open class ItemInfo {

    /** Row id in the launcher database, or [NO_ID] when not persisted yet. */
    var id: Long = NO_ID

    var itemType: ItemType = ItemType.APPLICATION

    /** [Containers.DESKTOP], [Containers.HOTSEAT], or the row id of the containing folder. */
    var container: Long = NO_ID

    /** Workspace screen id this item appears on; unused for hotseat and folder children. */
    var screenId: Long = NO_ID

    var cellX: Int = -1
    var cellY: Int = -1
    var spanX: Int = 1
    var spanY: Int = 1
    var minSpanX: Int = 1
    var minSpanY: Int = 1

    /** Position within an auto-arranged container (folder / hotseat). */
    var rank: Int = 0

    var title: CharSequence? = null
    var contentDescription: CharSequence? = null

    var user: UserHandle = Process.myUserHandle()

    /** The intent launched when the user taps this item, if it is launchable. */
    open val intent: Intent? get() = null

    /** True when the target is present but currently unusable (safe mode, suspended, ...). */
    open val isDisabled: Boolean get() = false

    open fun copyFrom(other: ItemInfo) {
        id = other.id
        itemType = other.itemType
        container = other.container
        screenId = other.screenId
        cellX = other.cellX
        cellY = other.cellY
        spanX = other.spanX
        spanY = other.spanY
        minSpanX = other.minSpanX
        minSpanY = other.minSpanY
        rank = other.rank
        title = other.title
        contentDescription = other.contentDescription
        user = other.user
    }

    /**
     * Drops references that could pin the activity (views, listeners). Subclasses holding UI
     * references must override this: model objects outlive configuration changes.
     */
    open fun unbind() = Unit

    override fun toString(): String =
        "${javaClass.simpleName}(id=$id type=$itemType container=${Containers.toDebugString(container)} " +
            "screen=$screenId cell=($cellX,$cellY) span=($spanX,$spanY) rank=$rank title=$title)"
}
