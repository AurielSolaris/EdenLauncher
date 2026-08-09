package app.auriel.edenlauncher.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One placed item: an app, a legacy shortcut, a folder, or a widget.
 *
 * Column names match AOSP Launcher3's `favorites` table so a database lifted from a stock
 * launcher stays readable, and so the eventual backup/restore agent has nothing to translate.
 */
@Entity(
    tableName = "favorites",
    indices = [
        Index(name = "index_favorites_container_screen", value = ["container", "screen"]),
        Index(name = "index_favorites_profileId", value = ["profileId"]),
    ],
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String? = null,

    /** `Intent.toUri(0)` of the launch intent; null for folders. */
    @ColumnInfo(name = "intent")
    val intent: String? = null,

    /** `Containers.DESKTOP`, `Containers.HOTSEAT`, or a parent folder's `_id`. */
    @ColumnInfo(name = "container")
    val container: Long,

    /** Workspace screen id; meaningless when [container] is the hotseat or a folder. */
    @ColumnInfo(name = "screen")
    val screen: Long,

    @ColumnInfo(name = "cellX") val cellX: Int,
    @ColumnInfo(name = "cellY") val cellY: Int,
    @ColumnInfo(name = "spanX") val spanX: Int = 1,
    @ColumnInfo(name = "spanY") val spanY: Int = 1,

    /** `ItemType.code`. */
    @ColumnInfo(name = "itemType")
    val itemType: Int,

    @ColumnInfo(name = "appWidgetId") val appWidgetId: Int = -1,
    @ColumnInfo(name = "appWidgetProvider") val appWidgetProvider: String? = null,

    /** PNG bytes for a custom icon. Null whenever the icon can be re-resolved from the package. */
    @ColumnInfo(name = "icon", typeAffinity = ColumnInfo.BLOB)
    val icon: ByteArray? = null,

    @ColumnInfo(name = "iconPackage") val iconPackage: String? = null,
    @ColumnInfo(name = "iconResource") val iconResource: String? = null,

    /** Serial number of the owning user profile. */
    @ColumnInfo(name = "profileId") val profileId: Long = 0,

    /** Order inside an auto-arranged container (folder / hotseat). */
    @ColumnInfo(name = "rank") val rank: Int = 0,

    /** `ShortcutStatus` / `WidgetRestoreStatus` bitmask; 0 once fully bound. */
    @ColumnInfo(name = "restored") val restored: Int = 0,

    /** `FolderOption` bitmask; unused for non-folders. */
    @ColumnInfo(name = "options") val options: Int = 0,

    @ColumnInfo(name = "modified") val modified: Long = 0,
) {
    // `icon` is a ByteArray, so the generated data-class equals/hashCode would compare identity.
    // Rows are compared by id everywhere in the loader; make that explicit instead of misleading.
    override fun equals(other: Any?): Boolean = this === other || (other is FavoriteEntity && other.id == id)

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Ordering of workspace pages. A screen exists as a row here even when it holds no items, which is
 * what lets the user keep an intentionally empty page.
 */
@Entity(tableName = "workspaceScreens")
data class WorkspaceScreenEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,

    /** Left-to-right position of this screen. */
    @ColumnInfo(name = "screenRank")
    val screenRank: Int,

    @ColumnInfo(name = "modified")
    val modified: Long = 0,
)
