package app.auriel.edenlauncher.model

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap

/**
 * A launchable icon on the workspace, in the hotseat, or inside a folder.
 *
 * Ported from `ShortcutInfo` (AOSP 7); AOSP 10 renamed the same class to `WorkspaceItemInfo`.
 */
class ShortcutInfo() : ItemInfo() {

    init {
        itemType = ItemType.SHORTCUT
    }

    /** Intent launched on tap. Null only while the item is being constructed by the loader. */
    var launchIntent: Intent? = null

    /**
     * When this item is a promise (restored or auto-install placeholder), [launchIntent] points at
     * the market entry and this holds the intent recorded in the database.
     */
    var promisedIntent: Intent? = null

    /** Legacy `ACTION_CREATE_SHORTCUT` icon reference, when the icon is not a bitmap. */
    var iconResource: Intent.ShortcutIconResource? = null

    /** Decoded icon. Null until the icon cache fills it in. */
    var icon: Bitmap? = null

    /** True when [icon] is a user/app supplied bitmap rather than one resolved from the package. */
    var hasCustomIcon: Boolean = false

    /** True while showing the generic fallback icon, so it is never written back to the database. */
    var usingFallbackIcon: Boolean = false

    /** Bitmask of [ShortcutStatus] flags describing restore/install state. */
    var status: Int = 0

    /** Bitmask of [DisabledReason] flags; non-zero means the icon is drawn dimmed and inert. */
    var disabledFlags: Int = 0

    /**
     * Id of the deep shortcut this item pins, when [itemType] is [ItemType.DEEP_SHORTCUT].
     *
     * Deep shortcuts cannot be launched by intent: they go through
     * [android.content.pm.LauncherApps.startShortcut] with the owning package and this id.
     */
    var deepShortcutId: String? = null

    /** Package that publishes [deepShortcutId]. */
    var deepShortcutPackage: String? = null

    val isDeepShortcut: Boolean
        get() = itemType == ItemType.DEEP_SHORTCUT && deepShortcutId != null

    /** Install progress [0, 100] while [ShortcutStatus.INSTALL_SESSION_ACTIVE] is set. */
    var installProgress: Int = 0
        private set

    constructor(other: ShortcutInfo) : this() {
        copyFrom(other)
    }

    constructor(app: AppInfo) : this() {
        copyFrom(app)
        // A drawer entry copied onto the workspace is a new row: it must not inherit the id of
        // anything, and AppInfo has none anyway.
        id = NO_ID
        itemType = ItemType.APPLICATION
        launchIntent = app.launchIntent?.let(::Intent)
        icon = app.icon
        disabledFlags = app.disabledFlags
    }

    override val intent: Intent? get() = launchIntent

    override val isDisabled: Boolean get() = disabledFlags != 0

    /** The component actually launched, resolving promise indirection. */
    val targetComponent: ComponentName?
        get() = (promisedIntent ?: launchIntent)?.component

    fun hasStatus(flag: Int): Boolean = (status and flag) != 0

    /** A placeholder icon waiting on a restore or an auto-install to complete. */
    val isPromise: Boolean
        get() = hasStatus(ShortcutStatus.RESTORED_ICON or ShortcutStatus.AUTO_INSTALL_ICON)

    fun setInstallProgress(progress: Int) {
        installProgress = progress
        status = status or ShortcutStatus.INSTALL_SESSION_ACTIVE
    }

    override fun copyFrom(other: ItemInfo) {
        super.copyFrom(other)
        if (other is ShortcutInfo) {
            launchIntent = other.launchIntent?.let(::Intent)
            promisedIntent = other.promisedIntent?.let(::Intent)
            iconResource = other.iconResource?.let { source ->
                Intent.ShortcutIconResource().apply {
                    packageName = source.packageName
                    resourceName = source.resourceName
                }
            }
            icon = other.icon
            hasCustomIcon = other.hasCustomIcon
            usingFallbackIcon = other.usingFallbackIcon
            status = other.status
            disabledFlags = other.disabledFlags
            deepShortcutId = other.deepShortcutId
            deepShortcutPackage = other.deepShortcutPackage
        }
    }
}

/** Restore / install lifecycle flags for [ShortcutInfo.status]. */
object ShortcutStatus {
    /** Restored from a backup and not yet resolvable. */
    const val RESTORED_ICON = 1

    /** Added by default-layout parsing for an app that is still being auto-installed. */
    const val AUTO_INSTALL_ICON = 1 shl 1

    /** A package installer session is currently running for this item. */
    const val INSTALL_SESSION_ACTIVE = 1 shl 2

    /** Widget restore has begun. */
    const val RESTORE_STARTED = 1 shl 3
}

/** Reasons an icon is present but not launchable, for [ShortcutInfo.disabledFlags]. */
object DisabledReason {
    const val SAFE_MODE = 1
    const val NOT_AVAILABLE = 1 shl 1
    const val SUSPENDED = 1 shl 2
    const val QUIET_USER = 1 shl 3
}
