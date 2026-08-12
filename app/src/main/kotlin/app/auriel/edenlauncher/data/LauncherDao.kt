package app.auriel.edenlauncher.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Replaces AOSP's `LauncherProvider`. Every read the loader performs is a single query; the
 * workspace is small enough that paging would cost more than it saves.
 *
 * An abstract class rather than an interface so `@Transaction` bodies are real methods on the
 * generated implementation.
 */
@Dao
abstract class LauncherDao {

    // ---- favorites -------------------------------------------------------------------------

    @Query("SELECT * FROM favorites ORDER BY container, screen, cellY, cellX")
    abstract suspend fun loadFavorites(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE container = :container AND screen = :screen ORDER BY cellY, cellX")
    abstract suspend fun favoritesOnScreen(container: Long, screen: Long): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE container = :folderId ORDER BY rank")
    abstract suspend fun folderContents(folderId: Long): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE _id = :id")
    abstract suspend fun favoriteById(id: Long): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavorite(item: FavoriteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavorites(items: List<FavoriteEntity>): List<Long>

    @Update
    abstract suspend fun updateFavorite(item: FavoriteEntity)

    @Update
    abstract suspend fun updateFavorites(items: List<FavoriteEntity>)

    @Delete
    abstract suspend fun deleteFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE _id = :id")
    abstract suspend fun deleteFavoriteById(id: Long)

    /** Deletes a folder and everything inside it. */
    @Query("DELETE FROM favorites WHERE _id = :folderId OR container = :folderId")
    abstract suspend fun deleteFolderAndContents(folderId: Long)

    @Query("DELETE FROM favorites WHERE container = :container AND screen = :screen")
    abstract suspend fun deleteScreenContents(container: Long, screen: Long)

    @Query(
        "SELECT _id FROM favorites " +
            "WHERE container = :container AND screen = :screen AND itemType = :folderItemType",
    )
    abstract suspend fun folderIdsOnScreen(
        container: Long,
        screen: Long,
        folderItemType: Int,
    ): List<Long>

    @Query("DELETE FROM favorites WHERE container IN (:containerIds)")
    abstract suspend fun deleteItemsInContainers(containerIds: List<Long>)

    @Query("SELECT COUNT(*) FROM favorites")
    abstract suspend fun favoriteCount(): Int

    // ---- workspace screens -----------------------------------------------------------------

    @Query("SELECT * FROM workspaceScreens ORDER BY screenRank")
    abstract suspend fun loadScreens(): List<WorkspaceScreenEntity>

    @Query("SELECT * FROM workspaceScreens ORDER BY screenRank")
    abstract fun observeScreens(): Flow<List<WorkspaceScreenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertScreen(screen: WorkspaceScreenEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertScreens(screens: List<WorkspaceScreenEntity>): List<Long>

    @Query("DELETE FROM workspaceScreens WHERE _id = :screenId")
    abstract suspend fun deleteScreen(screenId: Long)

    @Query("DELETE FROM workspaceScreens")
    abstract suspend fun clearScreens()

    /** Rewrites the page order atomically so a crash mid-reorder cannot leave gaps. */
    @Transaction
    open suspend fun replaceScreens(screens: List<WorkspaceScreenEntity>) {
        clearScreens()
        insertScreens(screens)
    }

    /**
     * Removes a page and everything on it, folders included.
     *
     * The folder children have to go first and by hand. A folder's own row sits on the page and is
     * swept by [deleteScreenContents], but its children do not - their container is the folder's
     * id, not the desktop, so they survive the page that held them and become rows pointing at a
     * parent that no longer exists. Invisible, permanent, and carried forward forever.
     */
    @Transaction
    open suspend fun deleteScreenWithContents(
        screenId: Long,
        desktopContainer: Long,
        folderItemType: Int,
    ) {
        val folderIds = folderIdsOnScreen(desktopContainer, screenId, folderItemType)
        if (folderIds.isNotEmpty()) deleteItemsInContainers(folderIds)
        deleteScreenContents(desktopContainer, screenId)
        deleteScreen(screenId)
    }

    /**
     * Deletes rows whose container no longer exists.
     *
     * Self-healing rather than a migration: databases in the wild already contain orphans left by
     * the page delete above, and there is no schema change to hang a `Migration` off. Cheap enough
     * to run on every load - one indexed scan - and it is the only thing that will ever clear them.
     */
    @Query(
        """
        DELETE FROM favorites
        WHERE container NOT IN (:rootContainers)
          AND container NOT IN (SELECT _id FROM favorites WHERE itemType = :folderItemType)
        """,
    )
    abstract suspend fun deleteOrphans(rootContainers: List<Long>, folderItemType: Int): Int
}
