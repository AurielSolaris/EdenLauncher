package app.auriel.edenlauncher.data

import android.content.ComponentName
import android.content.Intent
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ItemType
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo
import app.auriel.edenlauncher.model.NO_ID
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.util.EdenLog
import app.auriel.edenlauncher.util.UserCache
import app.auriel.edenlauncher.util.flatten
import app.auriel.edenlauncher.util.toIconBitmap

private const val TAG = "ItemMapping"

/**
 * Rehydrates a database row into a model object.
 *
 * Returns null for rows that can no longer be honoured - an unknown item type, an unparseable
 * intent, or a profile that has been removed. AOSP silently dropped these during load; doing the
 * same keeps one bad row from taking down the whole workspace.
 */
fun FavoriteEntity.toItemInfo(users: UserCache): ItemInfo? {
    val type = ItemType.fromCode(itemType) ?: run {
        EdenLog.w(TAG, "Dropping row $id: unknown itemType $itemType")
        return null
    }
    val owner = users.userFor(profileId) ?: run {
        EdenLog.w(TAG, "Dropping row $id: profile $profileId is gone")
        return null
    }

    val info: ItemInfo = when (type) {
        ItemType.FOLDER -> FolderInfo().also { it.options = options }

        ItemType.APP_WIDGET, ItemType.CUSTOM_APP_WIDGET -> LauncherAppWidgetInfo().also {
            it.appWidgetId = appWidgetId
            it.providerName = appWidgetProvider?.let(ComponentName::unflattenFromString)
            it.restoreStatus = restored
        }

        ItemType.APPLICATION, ItemType.SHORTCUT, ItemType.DEEP_SHORTCUT -> {
            val parsed = intent?.let(::parseIntentOrNull) ?: run {
                EdenLog.w(TAG, "Dropping row $id: unparseable intent")
                return null
            }
            ShortcutInfo().also {
                it.launchIntent = parsed
                if (type == ItemType.DEEP_SHORTCUT) {
                    // Deep shortcuts have no launchable intent; the id and package travel inside
                    // the stored intent so the schema stays the same as AOSP's.
                    it.deepShortcutId = parsed.getStringExtra(EXTRA_DEEP_SHORTCUT_ID)
                    it.deepShortcutPackage = parsed.`package`
                }
                it.status = restored
                it.iconResource = iconResource?.let { resName ->
                    Intent.ShortcutIconResource().apply {
                        packageName = iconPackage.orEmpty()
                        resourceName = resName
                    }
                }
                it.icon = icon?.toIconBitmap()
                it.hasCustomIcon = icon != null && iconResource == null
            }
        }
    }

    info.id = id
    info.itemType = type
    info.container = container
    info.screenId = screen
    info.cellX = cellX
    info.cellY = cellY
    info.spanX = spanX
    info.spanY = spanY
    info.rank = rank
    info.title = title
    info.user = owner
    return info
}

/** Serialises a model object for storage. [modifiedAt] is stamped by the repository. */
fun ItemInfo.toEntity(users: UserCache, modifiedAt: Long): FavoriteEntity {
    require(screenId != EXTRA_EMPTY_SCREEN_ID) {
        "Refusing to persist an item on the transient empty screen"
    }

    val shortcut = this as? ShortcutInfo
    val widget = this as? LauncherAppWidgetInfo
    val folder = this as? FolderInfo

    return FavoriteEntity(
        id = if (id == NO_ID) 0 else id,
        title = title?.toString(),
        intent = (shortcut?.promisedIntent ?: shortcut?.launchIntent)?.toUri(0),
        container = container,
        screen = screenId,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        itemType = itemType.code,
        appWidgetId = widget?.appWidgetId ?: LauncherAppWidgetInfo.NO_WIDGET_ID,
        appWidgetProvider = widget?.providerName?.flattenToString(),
        // Only custom bitmaps are stored; anything re-resolvable from the package is not, so the
        // database stays small and icons follow theme/pack changes.
        icon = shortcut?.takeIf { it.hasCustomIcon && !it.usingFallbackIcon }?.icon?.flatten(),
        iconPackage = shortcut?.iconResource?.packageName,
        iconResource = shortcut?.iconResource?.resourceName,
        profileId = users.serialFor(user),
        rank = rank,
        restored = shortcut?.status ?: widget?.restoreStatus ?: 0,
        options = folder?.options ?: 0,
        modified = modifiedAt,
    )
}

/** Screen id used for the trailing "drop here to create a page" screen, never written to disk. */
const val EXTRA_EMPTY_SCREEN_ID: Long = -201L

/** Intent extra carrying a deep shortcut's id, mirroring AOSP's `ShortcutKey`. */
const val EXTRA_DEEP_SHORTCUT_ID = "shortcut_id"

/** Builds the intent a deep shortcut is stored as. */
fun deepShortcutIntent(packageName: String, shortcutId: String): Intent =
    Intent(Intent.ACTION_MAIN)
        .setPackage(packageName)
        .putExtra(EXTRA_DEEP_SHORTCUT_ID, shortcutId)

/** Container ids that hold their children in a grid rather than by rank. */
fun ItemInfo.isOnGrid(): Boolean =
    container == Containers.DESKTOP || container == Containers.HOTSEAT

private fun parseIntentOrNull(uri: String): Intent? = try {
    Intent.parseUri(uri, 0)
} catch (e: java.net.URISyntaxException) {
    EdenLog.w(TAG, "Malformed intent uri", e)
    null
}
