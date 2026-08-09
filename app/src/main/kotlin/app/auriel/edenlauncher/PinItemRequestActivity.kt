package app.auriel.edenlauncher

import android.app.Activity
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.auriel.edenlauncher.data.deepShortcutIntent
import app.auriel.edenlauncher.model.Containers
import app.auriel.edenlauncher.model.ItemType
import app.auriel.edenlauncher.model.ShortcutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accepts "add this to your home screen" requests from other apps.
 *
 * This is the modern replacement for AOSP 7's `ACTION_CREATE_SHORTCUT` broadcast dance: since
 * Android 8 an app calls `ShortcutManager.requestPinShortcut`, and the system routes the
 * confirmation to whichever launcher declares this filter. Without it, every "add to home screen"
 * button in every app silently does nothing.
 *
 * The item is placed in the first free cell and the activity finishes immediately - no
 * confirmation dialog, because the requesting app already showed one.
 */
class PinItemRequestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request == null || !request.isValid) {
            finish()
            return
        }

        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> acceptShortcut(request)

            // Widget hosting is not implemented yet. Declining is deliberate: accepting would
            // record a widget id the launcher can never render.
            else -> {
                Toast.makeText(this, R.string.pin_widget_unsupported, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun acceptShortcut(request: LauncherApps.PinItemRequest) {
        val shortcut = request.shortcutInfo
        if (shortcut == null) {
            finish()
            return
        }

        // Accept before persisting: the system only keeps the shortcut alive once pinned.
        if (!request.accept()) {
            finish()
            return
        }

        val appState = LauncherAppState.getInstance(this)
        val idp = appState.invariantDeviceProfile
        val launcherApps = getSystemService(LauncherApps::class.java)

        val info = ShortcutInfo().apply {
            itemType = ItemType.DEEP_SHORTCUT
            title = shortcut.shortLabel ?: shortcut.longLabel
            deepShortcutId = shortcut.id
            deepShortcutPackage = shortcut.`package`
            launchIntent = deepShortcutIntent(shortcut.`package`, shortcut.id)
            user = shortcut.userHandle
            container = Containers.DESKTOP
            icon = rasterise(
                launcherApps.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi),
                idp.iconBitmapSizePx,
            )
            hasCustomIcon = icon != null
        }

        scope.launch {
            val placed = placeOnFirstFreeCell(info, idp.numColumns, idp.numRows)
            Toast.makeText(
                this@PinItemRequestActivity,
                if (placed) R.string.pin_shortcut_added else R.string.pin_no_space,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    /**
     * Finds the first free cell across the persisted pages, adding a page if all are full.
     *
     * Done against the database rather than the live workspace: this activity runs in front of
     * the launcher, whose views may not even exist. The launcher rebinds on its next resume.
     */
    private suspend fun placeOnFirstFreeCell(info: ShortcutInfo, columns: Int, rows: Int): Boolean {
        val repository = LauncherAppState.getInstance(this).repository
        val screens = repository.ensureDefaultScreen()
        val data = repository.loadWorkspace()

        val occupiedByScreen = HashMap<Long, MutableSet<Int>>()
        for (item in data.workspaceItems) {
            occupiedByScreen.getOrPut(item.screenId) { HashSet() }.add(item.cellY * columns + item.cellX)
        }

        for (screenId in screens) {
            val occupied = occupiedByScreen[screenId] ?: emptySet<Int>()
            for (index in 0 until columns * rows) {
                if (index in occupied) continue
                info.screenId = screenId
                info.cellX = index % columns
                info.cellY = index / columns
                repository.addItem(info)
                return true
            }
        }

        // Every page is full: give the shortcut a page of its own rather than dropping it.
        val newScreen = repository.addScreen()
        info.screenId = newScreen
        info.cellX = 0
        info.cellY = 0
        repository.addItem(info)
        return true
    }

    private fun rasterise(drawable: Drawable?, sizePx: Int): Bitmap? {
        if (drawable == null || sizePx <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Could not rasterise pinned shortcut icon", e)
            null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PinItemRequest"
    }
}
