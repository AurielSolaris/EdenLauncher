package app.auriel.edenlauncher.icons

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every icon in the chosen pack, to be picked one at a time.
 *
 * An icon pack covers the apps its author had heard of. Everything else - a niche app, a fork, a
 * thing built last week - keeps its own icon and stands out on a themed screen. Every launcher that
 * takes icon packs seriously answers this the same way: let the user go and find an icon in the
 * pack that will do. This is that screen.
 *
 * Drawables are decoded lazily as rows scroll into view and held in a small cache. A pack with
 * three thousand icons cannot be decoded up front on a two-core phone, and does not need to be -
 * only the twenty on screen matter.
 */
class IconPickerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Decoding one pack drawable at a time; the grid scrolls faster than the decoder can keep up. */
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(2)

    private lateinit var pack: IconPack
    private lateinit var adapter: IconAdapter

    private var allNames: List<String> = emptyList()
    private var iconSizePx = 0

    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.icon_picker_title)

        val packageName = intent.getStringExtra(EXTRA_PACK)
        if (packageName == null) {
            finish()
            return
        }
        pack = IconPack(this, packageName)
        iconSizePx = LauncherAppState.getInstance(this).invariantDeviceProfile.iconBitmapSizePx

        setContentView(buildContentView())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        bitmaps.evictAll()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)

        val search = EditText(this).apply {
            hint = getString(R.string.icon_picker_search)
            setHintTextColor(getColor(R.color.all_apps_hint))
            setTextColor(getColor(R.color.settings_text))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(padding, padding, padding, padding)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
                },
            )
        }

        adapter = IconAdapter()
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@IconPickerActivity, COLUMNS)
            adapter = this@IconPickerActivity.adapter
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(padding, 0, padding, padding)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.settings_background))
            fitsSystemWindows = true
            addView(
                search,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun load() {
        scope.launch {
            allNames = withContext(Dispatchers.IO) { pack.browsableDrawables() }
            adapter.submit(allNames)
        }
    }

    /**
     * Matches the drawable's own name, which is the only text a pack gives us. Underscores are
     * treated as spaces so "google maps" finds `google_maps`, which is how the names are written.
     */
    private fun filter(query: String) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            adapter.submit(allNames)
            return
        }
        adapter.submit(
            allNames.filter { it.replace('_', ' ').contains(needle, ignoreCase = true) },
        )
    }

    private fun choose(name: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DRAWABLE, name))
        finish()
    }

    // ---- grid ------------------------------------------------------------------------------------

    private inner class IconAdapter : RecyclerView.Adapter<IconHolder>() {

        private var names: List<String> = emptyList()

        fun submit(list: List<String>) {
            names = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = names.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconHolder {
            val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
            val cell = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(spacing / 2, spacing / 2, spacing / 2, spacing / 2) }
                setPadding(spacing, spacing, spacing, spacing)
                adjustViewBounds = false
                minimumHeight = iconSizePx + spacing * 2
                setBackgroundColor(getColor(R.color.icon_picker_tile))
            }
            return IconHolder(cell)
        }

        override fun onBindViewHolder(holder: IconHolder, position: Int) {
            holder.bind(names[position])
        }

        override fun onViewRecycled(holder: IconHolder) {
            holder.recycle()
        }
    }

    private inner class IconHolder(private val image: ImageView) : RecyclerView.ViewHolder(image) {

        private var job: Job? = null

        fun bind(name: String) {
            image.setOnClickListener { choose(name) }

            val cached = bitmaps.get(name)
            if (cached != null) {
                image.setImageBitmap(cached)
                return
            }

            // Cleared first: this holder is being reused and would otherwise show the previous
            // row's icon until the decode finishes.
            image.setImageDrawable(null)
            job?.cancel()
            job = scope.launch {
                val bitmap = withContext(decodeDispatcher) { pack.bitmapFor(name, iconSizePx) }
                if (bitmap != null) {
                    bitmaps.put(name, bitmap)
                    // The holder may have been rebound to a different icon while we decoded.
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        image.setImageBitmap(bitmap)
                    }
                }
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
        }
    }

    companion object {
        private const val COLUMNS = 5
        private const val CACHE_BYTES = 4 * 1024 * 1024
        private const val EXTRA_PACK = "app.auriel.edenlauncher.ICON_PACK"

        /** Name of the drawable the user chose, on a RESULT_OK. */
        const val EXTRA_DRAWABLE = "app.auriel.edenlauncher.ICON_DRAWABLE"

        fun intentFor(context: Context, packPackage: String): Intent =
            Intent(context, IconPickerActivity::class.java).putExtra(EXTRA_PACK, packPackage)
    }
}
