package app.auriel.edenlauncher.data

import android.util.LongSparseArray
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.NO_ID
import app.auriel.edenlauncher.model.ShortcutInfo
import app.auriel.edenlauncher.util.UserCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything the launcher persists, in one place.
 *
 * All work runs on [io]; callers stay on the main thread. The loader in Phase 2 consumes
 * [loadWorkspace] once at startup and then applies incremental writes as the user rearranges.
 */
class LauncherRepository(
    private val dao: LauncherDao,
    private val users: UserCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * One pass over both tables, with folder children already attached to their folders.
     *
     * @param screenIds page ids in left-to-right order.
     * @param workspaceItems items on a workspace page, in row-major order.
     * @param hotseatItems items in the dock, ordered by rank.
     */
    class WorkspaceData(
        val screenIds: List<Long>,
        val workspaceItems: List<ItemInfo>,
        val hotseatItems: List<ItemInfo>,
    )

    suspend fun loadWorkspace(): WorkspaceData = withContext(io) {
        val screens = dao.loadScreens().map { it.id }
        val rows = dao.loadFavorites()

        // Rows are converted once. Folder children are held aside because a child can be read
        // before its folder; they are attached in a second pass over that much smaller list.
        val folders = LongSparseArray<FolderInfo>()
        val workspace = ArrayList<ItemInfo>(rows.size)
        val hotseat = ArrayList<ItemInfo>(8)
        val pendingFolderChildren = ArrayList<ShortcutInfo>()

        for (row in rows) {
            val item = row.toItemInfo(users) ?: continue
            if (item is FolderInfo) folders.put(item.id, item)
            when (item.container) {
                Containers.DESKTOP -> workspace.add(item)
                Containers.HOTSEAT -> hotseat.add(item)
                else -> if (item is ShortcutInfo) pendingFolderChildren.add(item)
            }
        }

        for (child in pendingFolderChildren) {
            folders.get(child.container)?.contents?.add(child)
        }
        for (i in 0 until folders.size()) {
            folders.valueAt(i).contents.sortBy { it.rank }
        }

        hotseat.sortBy { it.rank }
        WorkspaceData(screens, workspace, hotseat)
    }

    /** Inserts [item], assigning its database id in place. */
    suspend fun addItem(item: ItemInfo): Long = withContext(io) {
        val id = dao.insertFavorite(item.toEntity(users, now()))
        item.id = id
        id
    }

    suspend fun updateItem(item: ItemInfo) = withContext(io) {
        check(item.id != NO_ID) { "updateItem called on an unsaved item: $item" }
        dao.updateFavorite(item.toEntity(users, now()))
    }

    /** Batched position write used after a drag settles or a page is reordered. */
    suspend fun updateItems(items: List<ItemInfo>) = withContext(io) {
        val stamp = now()
        dao.updateFavorites(items.map { it.toEntity(users, stamp) })
    }

    suspend fun deleteItem(item: ItemInfo) = withContext(io) {
        if (item is FolderInfo) dao.deleteFolderAndContents(item.id) else dao.deleteFavoriteById(item.id)
    }

    // ---- screens ---------------------------------------------------------------------------

    suspend fun loadScreenIds(): List<Long> = withContext(io) { dao.loadScreens().map { it.id } }

    /** Appends a new empty page and returns its id. */
    suspend fun addScreen(): Long = withContext(io) {
        val rank = dao.loadScreens().size
        dao.insertScreen(WorkspaceScreenEntity(screenRank = rank, modified = now()))
    }

    suspend fun removeScreen(screenId: Long) = withContext(io) {
        dao.deleteScreenWithContents(screenId, Containers.DESKTOP)
    }

    /** Persists a new page order; [screenIds] is the full list, left to right. */
    suspend fun reorderScreens(screenIds: List<Long>) = withContext(io) {
        val stamp = now()
        dao.replaceScreens(
            screenIds.mapIndexed { rank, id ->
                WorkspaceScreenEntity(id = id, screenRank = rank, modified = stamp)
            },
        )
    }

    /**
     * Guarantees at least one page exists. Called before the first bind so an empty database
     * still produces a usable home screen.
     */
    suspend fun ensureDefaultScreen(): List<Long> = withContext(io) {
        val existing = dao.loadScreens()
        if (existing.isNotEmpty()) return@withContext existing.map { it.id }
        listOf(dao.insertScreen(WorkspaceScreenEntity(screenRank = 0, modified = now())))
    }

    suspend fun isEmpty(): Boolean = withContext(io) { dao.favoriteCount() == 0 }

    private fun now(): Long = System.currentTimeMillis()
}
