package app.auriel.edenlauncher.allapps

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.settings.AppDrawerMode
import app.auriel.edenlauncher.views.Insettable
import android.graphics.Rect

/**
 * The app drawer.
 *
 * Ported from `AllAppsContainerView` (AOSP 7), restructured around the plan's requirement that
 * the user picks the navigation style. This view owns the search field, the filter, and the
 * background; the actual list is whichever [AppDrawerView] the current [AppDrawerMode] selects,
 * and switching modes swaps that one child.
 */
class AllAppsContainerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), Insettable {

    private val searchField: EditText
    private val listContainer: FrameLayout

    private var drawerView: AppDrawerView? = null
    private var allApps: List<AppInfo> = emptyList()
    private var query: String = ""

    var onAppClick: ((AppInfo) -> Unit)? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    var isOpen: Boolean = false
        private set

    init {
        orientation = VERTICAL
        isClickable = true
        visibility = GONE
        setBackgroundColor(backgroundColorFor(DEFAULT_OPACITY))

        val padding = resources.getDimensionPixelSize(R.dimen.all_apps_search_padding)

        searchField = EditText(context).apply {
            hint = context.getString(R.string.all_apps_search_hint)
            setHintTextColor(context.getColor(R.color.all_apps_hint))
            setTextColor(context.getColor(R.color.settings_text))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundResource(0)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString().orEmpty()
                        applyFilter()
                    }
                },
            )
        }
        addView(searchField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        listContainer = FrameLayout(context)
        addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * The drawer covers the whole screen, so it takes the window insets as padding: the search
     * field clears the status bar and the last row clears the gesture bar.
     */
    override fun setInsets(insets: Rect) {
        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /** Swaps the list implementation. Safe to call with the same mode; it then does nothing. */
    fun setMode(mode: AppDrawerMode) {
        if (drawerView != null && modeOf(drawerView) == mode) return

        listContainer.removeAllViews()
        val view = when (mode) {
            AppDrawerMode.VERTICAL -> AppDrawerVertical(context)
            AppDrawerMode.HORIZONTAL -> AppDrawerHorizontal(context)
        }
        view.onAppClick = { onAppClick?.invoke(it) }
        view.onAppLongClick = { app, source -> onAppLongClick?.invoke(app, source) }
        listContainer.addView(
            view.asView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        drawerView = view
        applyFilter()
    }

    private fun modeOf(view: AppDrawerView?): AppDrawerMode? = when (view) {
        is AppDrawerVertical -> AppDrawerMode.VERTICAL
        is AppDrawerHorizontal -> AppDrawerMode.HORIZONTAL
        else -> null
    }

    /**
     * Sets background opacity, 0 (wallpaper fully visible through the drawer) to 100 (opaque).
     */
    fun setBackgroundOpacity(percent: Int) {
        setBackgroundColor(backgroundColorFor(percent.coerceIn(0, 100)))
    }

    fun setApps(apps: List<AppInfo>) {
        allApps = apps
        applyFilter()
    }

    /**
     * Filters by label. Substring rather than prefix matching, because "tube" should find
     * "YouTube" - the behaviour every OEM drawer has and stock AOSP did not.
     *
     * A renamed app matches on either name. Searching only the name you chose would mean that
     * renaming an app quietly makes it unfindable under the name it actually has, which is a trap
     * that only springs months later.
     */
    private fun applyFilter() {
        val view = drawerView ?: return
        if (query.isBlank()) {
            view.submitApps(allApps)
            return
        }
        val needle = query.trim()
        view.submitApps(
            allApps.filter { app ->
                app.title?.contains(needle, ignoreCase = true) == true ||
                    app.originalTitle?.contains(needle, ignoreCase = true) == true
            },
        )
    }

    // ---- open / close ---------------------------------------------------------------------------

    fun open() {
        if (isOpen) return
        isOpen = true
        visibility = VISIBLE
        alpha = 0f
        translationY = height * OPEN_TRANSLATION_RATIO
        animate().alpha(1f).translationY(0f).setDuration(OPEN_DURATION_MS).start()
        drawerView?.resetScroll()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        searchField.setText("")
        animate()
            .alpha(0f)
            .translationY(height * OPEN_TRANSLATION_RATIO)
            .setDuration(CLOSE_DURATION_MS)
            .withEndAction { visibility = GONE }
            .start()
    }

    private fun hideKeyboard() {
        searchField.clearFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    private companion object {
        const val DEFAULT_OPACITY = 92
        const val OPEN_DURATION_MS = 180L
        const val CLOSE_DURATION_MS = 140L
        const val OPEN_TRANSLATION_RATIO = 0.12f

        /**
         * Drawer scrim: the Eden surface colour at the requested opacity, so wallpaper showing
         * through at low opacity still leaves labels readable.
         */
        fun backgroundColorFor(percent: Int): Int =
            Color.argb((percent * 255) / 100, 0x0E, 0x16, 0x11)
    }
}
