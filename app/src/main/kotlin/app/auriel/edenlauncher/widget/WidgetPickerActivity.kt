package app.auriel.edenlauncher.widget

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.device.DeviceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every widget the phone has, grouped by the app it came from.
 *
 * Two things this screen does that a plain list would not. It says how many cells each widget will
 * take on *this* grid, computed against the user's own column and row counts rather than a stock
 * assumption - the same widget is 4x1 on one grid and 3x1 on another. And it greys out anything
 * that cannot fit at all, because the alternative is letting a user walk through an id allocation
 * and a permission prompt only to be told at the end that there is nowhere to put it.
 *
 * Previews are drawn lazily as rows scroll into view, the same discipline the wallpaper picker and
 * the icon browser use: a phone with sixty widgets installed must not decode sixty preview images
 * to show the first six.
 */
class WidgetPickerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loadDispatcher = Dispatchers.IO.limitedParallelism(2)

    private lateinit var profile: DeviceProfile
    private lateinit var adapter: RowAdapter

    private var previewWidth = 0
    private var previewHeight = 0

    private val previews = object : LruCache<ComponentName, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: ComponentName, value: Bitmap): Int = value.allocationByteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.widget_picker_title)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        profile = LauncherAppState.getInstance(this).invariantDeviceProfile.profileFor(landscape)
        previewWidth = profile.cellWidthPx * PREVIEW_CELLS
        previewHeight = profile.cellHeightPx * PREVIEW_CELLS

        setContentView(buildContentView())
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        previews.evictAll()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)

        val heading = TextView(this).apply {
            text = getString(R.string.widget_picker_title)
            setTextColor(getColor(R.color.settings_text))
            textSize = HEADING_SP
            setPadding(padding, padding, padding, padding / 2)
        }

        val note = TextView(this).apply {
            text = getString(R.string.widget_picker_summary)
            setTextColor(getColor(R.color.settings_text_secondary))
            textSize = SUMMARY_SP
            setPadding(padding, 0, padding, padding / 2)
        }

        adapter = RowAdapter()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WidgetPickerActivity)
            adapter = this@WidgetPickerActivity.adapter
            clipToPadding = false
            setPadding(padding, 0, padding, padding)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.settings_background))
            fitsSystemWindows = true
            addView(heading, matchWidth())
            addView(note, matchWidth())
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun load() {
        scope.launch {
            val rows = withContext(Dispatchers.IO) { buildRows() }
            adapter.submit(rows)
        }
    }

    /**
     * Flattens providers into headers and entries in one pass, so the list has no nesting to
     * navigate and the adapter stays a plain index lookup.
     */
    private fun buildRows(): List<Row> {
        val providers = WidgetBinding.installedProviders(this)
        if (providers.isEmpty()) return emptyList()

        val byApp = providers
            .map { Entry(it, WidgetBinding.appLabelFor(this, it), WidgetBinding.widgetLabelFor(this, it)) }
            .groupBy { it.appLabel }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        val rows = ArrayList<Row>(providers.size + byApp.size)
        for ((appLabel, entries) in byApp) {
            rows.add(Row.Header(appLabel))
            entries.sortedBy { it.widgetLabel.lowercase() }.forEach { rows.add(Row.Widget(it)) }
        }
        return rows
    }

    private fun choose(entry: Entry) {
        if (!WidgetSizes.fitsGrid(entry.info, profile)) return
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_PROVIDER, entry.info.provider),
        )
        finish()
    }

    /**
     * Renders a provider's preview into a bitmap no larger than a couple of cells.
     *
     * A preview image is authored for a big screen and can be several megabytes decoded. Drawing
     * it once at the size it will be shown costs one small bitmap and keeps a list of sixty
     * widgets inside a few megabytes rather than a few hundred.
     */
    private fun renderPreview(info: AppWidgetProviderInfo): Bitmap? {
        val density = resources.displayMetrics.densityDpi
        val drawable: Drawable = runCatching { info.loadPreviewImage(this, density) }.getOrNull()
            ?: runCatching { info.loadIcon(this, density) }.getOrNull()
            ?: return null

        val srcWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: previewWidth
        val srcHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: previewHeight
        val scale = min(
            previewWidth.toFloat() / srcWidth,
            previewHeight.toFloat() / srcHeight,
        ).coerceAtMost(1f)

        val width = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (srcHeight * scale).roundToInt().coerceAtLeast(1)

        // An already-decoded bitmap at the right size needs no second copy.
        val bitmapDrawable = drawable as? BitmapDrawable
        if (bitmapDrawable?.bitmap != null && width == srcWidth && height == srcHeight) {
            return bitmapDrawable.bitmap
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    // ---- rows ------------------------------------------------------------------------------------

    private class Entry(
        val info: AppWidgetProviderInfo,
        val appLabel: String,
        val widgetLabel: String,
    )

    private sealed class Row {
        class Header(val label: String) : Row()
        class Widget(val entry: Entry) : Row()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_WIDGET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val padding = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
            if (viewType == TYPE_HEADER) {
                val header = TextView(parent.context).apply {
                    setTextColor(getColor(R.color.accent))
                    textSize = HEADER_SP
                    setPadding(0, padding * 2, 0, padding / 2)
                }
                return HeaderHolder(header)
            }
            return WidgetHolder(WidgetRowView(parent.context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.label)
                is Row.Widget -> (holder as WidgetHolder).bind(row.entry)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? WidgetHolder)?.recycle()
        }
    }

    private class HeaderHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(label: String) {
            text.text = label
        }
    }

    private inner class WidgetHolder(private val row: WidgetRowView) : RecyclerView.ViewHolder(row) {

        private var job: Job? = null

        fun bind(entry: Entry) {
            val span = WidgetSizes.defaultSpan(entry.info, profile)
            val fits = WidgetSizes.fitsGrid(entry.info, profile)

            row.setLabel(entry.widgetLabel)
            row.setDetail(
                if (fits) {
                    getString(R.string.widget_picker_size, span[0], span[1])
                } else {
                    getString(R.string.widget_picker_too_big)
                },
            )
            row.setEnabledLook(fits)
            row.setOnClickListener(if (fits) View.OnClickListener { choose(entry) } else null)
            row.isClickable = fits

            val cached = previews.get(entry.info.provider)
            if (cached != null) {
                row.setPreview(cached)
                return
            }

            row.setPreview(null)
            job?.cancel()
            job = scope.launch {
                val bitmap = withContext(loadDispatcher) { renderPreview(entry.info) }
                if (bitmap != null) {
                    previews.put(entry.info.provider, bitmap)
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION) row.setPreview(bitmap)
                }
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
        }
    }

    /** One widget: its preview on the left, its name and size on the right. */
    private class WidgetRowView(context: Context) : LinearLayout(context) {

        private val preview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }

        private val label = TextView(context).apply {
            setTextColor(context.getColor(R.color.settings_text))
            textSize = LABEL_SP
        }

        private val detail = TextView(context).apply {
            setTextColor(context.getColor(R.color.settings_text_secondary))
            textSize = DETAIL_SP
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
            setPadding(spacing, spacing, spacing, spacing)
            setBackgroundResource(R.drawable.widget_picker_row_background)

            val thumb = resources.getDimensionPixelSize(R.dimen.widget_picker_preview_size)
            addView(preview, LayoutParams(thumb, thumb))

            val text = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(spacing, 0, 0, 0)
                addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = spacing / 2 }
        }

        fun setLabel(text: String) {
            label.text = text
        }

        fun setDetail(text: String) {
            detail.text = text
        }

        fun setPreview(bitmap: Bitmap?) = preview.setImageBitmap(bitmap)

        /** A widget that cannot fit stays visible and readable, but plainly not offered. */
        fun setEnabledLook(enabled: Boolean) {
            alpha = if (enabled) 1f else DISABLED_ALPHA
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_WIDGET = 1
        private const val PREVIEW_CELLS = 2
        private const val CACHE_BYTES = 6 * 1024 * 1024
        private const val DISABLED_ALPHA = 0.4f
        private const val HEADING_SP = 22f
        private const val SUMMARY_SP = 13f
        private const val HEADER_SP = 15f
        private const val LABEL_SP = 16f
        private const val DETAIL_SP = 13f

        /** The chosen provider, as a [ComponentName], on a RESULT_OK. */
        const val EXTRA_PROVIDER = "app.auriel.edenlauncher.WIDGET_PROVIDER"

        fun intentFor(context: Context): Intent =
            Intent(context, WidgetPickerActivity::class.java)
    }
}
