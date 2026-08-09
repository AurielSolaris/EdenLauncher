package app.auriel.edenlauncher.model

/**
 * A folder holding [ShortcutInfo]s. Ported from `FolderInfo` (AOSP 7).
 *
 * Listeners are kept in a plain array list and iterated by index: folder mutations happen during
 * drag gestures, and an iterator allocation per notification is measurable on 2-core devices.
 */
class FolderInfo : ItemInfo() {

    init {
        itemType = ItemType.FOLDER
    }

    /** Bitmask of [FolderOption]. */
    var options: Int = 0

    var isOpen: Boolean = false

    val contents: MutableList<ShortcutInfo> = ArrayList()

    private val listeners = ArrayList<Listener>(2)

    fun add(item: ShortcutInfo) {
        contents.add(item)
        for (i in listeners.indices) listeners[i].onAdd(item)
        notifyItemsChanged()
    }

    /** Removes [item] from the in-memory model only; the caller persists the change. */
    fun remove(item: ShortcutInfo) {
        if (!contents.remove(item)) return
        for (i in listeners.indices) listeners[i].onRemove(item)
        notifyItemsChanged()
    }

    /** Renames the folder and tells any open folder view. */
    fun rename(newTitle: CharSequence?) {
        title = newTitle
        for (i in listeners.indices) listeners[i].onTitleChanged(newTitle)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyItemsChanged() {
        for (i in listeners.indices) listeners[i].onItemsChanged()
    }

    fun hasOption(option: Int): Boolean = (options and option) != 0

    fun setOption(option: Int, enabled: Boolean) {
        options = if (enabled) options or option else options and option.inv()
    }

    /** Folder views register here; [unbind] drops them so the model cannot leak the activity. */
    override fun unbind() {
        listeners.clear()
    }

    interface Listener {
        fun onAdd(item: ShortcutInfo)
        fun onRemove(item: ShortcutInfo)
        fun onTitleChanged(title: CharSequence?)
        fun onItemsChanged()
    }
}

/** Flags for [FolderInfo.options]. */
object FolderOption {
    const val NONE = 0
    const val ITEMS_SORTED = 1
    const val WORK_FOLDER = 1 shl 1
    const val MULTI_PAGE_ANIMATION = 1 shl 2
}
