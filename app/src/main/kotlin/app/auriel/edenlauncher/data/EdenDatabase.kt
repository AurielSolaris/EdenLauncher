package app.auriel.edenlauncher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The launcher database. Replaces `LauncherProvider` + `DatabaseHelper` from AOSP Launcher3.
 *
 * A widget host still needs a `ContentProvider` for cross-process access; that will be added in
 * Phase 2 as a thin facade over this database rather than as a second source of truth.
 */
@Database(
    entities = [FavoriteEntity::class, WorkspaceScreenEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EdenDatabase : RoomDatabase() {

    abstract fun launcherDao(): LauncherDao

    companion object {
        private const val NAME = "eden_launcher.db"

        @Volatile
        private var instance: EdenDatabase? = null

        fun getInstance(context: Context): EdenDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): EdenDatabase =
            Room.databaseBuilder(context, EdenDatabase::class.java, NAME)
                // The workspace is the user's own arrangement: never silently discard it.
                // Schema changes ship with an explicit Migration instead.
                .build()
    }
}
