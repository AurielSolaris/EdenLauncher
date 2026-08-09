package app.auriel.edenlauncher.dragndrop

import android.view.View
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ShortcutInfo

/**
 * How a drop turns into a change the launcher persists.
 *
 * AOSP wired this straight into `Launcher` through static `LauncherModel` calls. Routing it
 * through an interface keeps the views free of any knowledge of the database, which is what lets
 * the workspace, the dock, and a folder share the same drop code.
 */
interface ItemPlacementHandler {

    /** [info] now lives at the given position; move the view and write the change. */
    fun onItemPlaced(
        info: ItemInfo,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
        rank: Int,
    )

    /** [info] was dropped on the delete target. */
    fun onItemRemoved(info: ItemInfo)

    /** Two icons were stacked: replace [targetView] with a new folder holding both. */
    fun onFolderCreationRequested(
        target: ShortcutInfo,
        dropped: ShortcutInfo,
        targetView: View?,
        container: Long,
        screenId: Long,
        cellX: Int,
        cellY: Int,
    )

    /** An icon was dropped onto an existing folder. */
    fun onItemAddedToFolder(folder: FolderInfo, item: ShortcutInfo)

    /** Page order changed; persist the new ordering. */
    fun onScreenOrderChanged(screenIds: List<Long>)
}
