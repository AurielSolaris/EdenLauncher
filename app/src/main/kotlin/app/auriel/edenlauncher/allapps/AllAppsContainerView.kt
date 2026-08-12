package app.auriel.edenlauncher.allapps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.settings.AppDrawerMode
import app.auriel.edenlauncher.views.Insettable
import app.auriel.edenlauncher.views.PageIndicatorDots
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

    /**
     * Page dots for the horizontal drawer.
     *
     * Paging without them is the one place the drawer was worse than the workspace: no way to tell
     * how many pages there are, or which one you are on, until you hit the end. Gone entirely in
     * vertical mode, where there is nothing to count.
     */
    private val pageDots: PageIndicatorDots

    private var drawerView: AppDrawerView? = null
    private var allApps: List<AppInfo> = emptyList()
    private var query: String = ""

    var onAppClick: ((AppInfo) -> Unit)? = null
    var onAppLongClick: ((AppInfo, View) -> Unit)? = null

    /** The drawer was pulled far enough down to mean "put this away". */
    var onDismissRequested: (() -> Unit)? = null

    var isOpen: Boolean = false
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** True once a downward drag at the top of the list has been taken over from the list. */
    private var pulling = false
    private var pullStartY = 0f

    /** Obtained from the platform pool on the gesture that might become a pull, and given back. */
    private var velocityTracker: VelocityTracker? = null

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

        pageDots = PageIndicatorDots(context).apply { visibility = GONE }
        addView(
            pageDots,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.all_apps_page_dots_margin)
                bottomMargin = topMargin
            },
        )
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
        // Only the paged drawer has pages to count. PageIndicatorDots hides itself when there is
        // one page or none, so a short filtered list drops the dots on its own.
        if (view is AppDrawerHorizontal) {
            view.pageIndicator = pageDots
            pageDots.onMarkerSelected = { index -> view.snapToPage(index) }
            pageDots.visibility = VISIBLE
        } else {
            pageDots.onMarkerSelected = null
            pageDots.visibility = GONE
        }

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

    // ---- pull to close ---------------------------------------------------------------------------

    /**
     * Drag the drawer down from the top of the list to put it away.
     *
     * The gesture every modern launcher has, and the reason is that the drawer was opened with a
     * swipe: reaching for Back to undo a swipe is a change of tool halfway through a thought.
     *
     * The handover has to happen at *true* scroll position zero and nowhere else. Taking the
     * gesture any earlier means a fast fling up the list, which ends with the finger travelling
     * downwards, dismisses the drawer by accident - which is the failure mode that makes this
     * gesture hated when it is done badly.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pulling = false
                pullStartY = ev.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.y - pullStartY
                if (!pulling && dy > touchSlop && drawerView?.isScrolledToTop == true) {
                    pulling = true
                    // Measured from here, so the drawer does not jump by a slop's worth the moment
                    // it is picked up.
                    pullStartY = ev.y
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseTracker()
        }
        return false
    }

    // A drag surface, not a button. There is no click to route an accessibility action through:
    // the only gesture here is the pull, and a service that wants the drawer closed uses Back.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pulling) {
            // The drawer is a full-screen opaque panel; swallowing stray touches keeps them off
            // the workspace behind it, which is what isClickable already promises.
            return super.onTouchEvent(event)
        }

        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationY = resisted(event.y - pullStartY)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pulling = false
                val travelled = translationY
                val velocity = velocityTracker?.let {
                    it.computeCurrentVelocity(VELOCITY_UNITS)
                    it.yVelocity
                } ?: 0f
                releaseTracker()

                // Distance or speed, either alone. A short flick down is as clear an instruction as
                // a long slow drag, and requiring both would make the gesture feel unresponsive.
                val far = travelled > height * DISMISS_TRAVEL_RATIO
                val fast = velocity > DISMISS_VELOCITY_DP * resources.displayMetrics.density
                if (far || fast) onDismissRequested?.invoke() else settleBack()
                return true
            }
        }
        return true
    }

    /**
     * The drawer follows the finger at a decreasing rate, so the panel feels attached to something
     * rather than loose. Without it the drawer outruns the gesture and a small movement looks like
     * a decision the user has not made yet.
     */
    private fun resisted(dy: Float): Float {
        if (dy <= 0f) return 0f
        return dy * PULL_RESISTANCE
    }

    private fun settleBack() {
        animate().translationY(0f).setDuration(SETTLE_DURATION_MS).start()
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---- open / close ---------------------------------------------------------------------------

    fun open() {
        if (isOpen) return
        isOpen = true
        pulling = false
        releaseTracker()
        visibility = VISIBLE
        alpha = 0f
        translationY = height * OPEN_TRANSLATION_RATIO
        animate().alpha(1f).translationY(0f).setDuration(OPEN_DURATION_MS).start()
        drawerView?.resetScroll()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        pulling = false
        releaseTracker()
        hideKeyboard()
        searchField.setText("")
        // Never less than where the drawer already is: closing after a pull that went further than
        // the closing offset would otherwise animate the panel back up before it disappeared.
        val target = maxOf(translationY, height * OPEN_TRANSLATION_RATIO)
        animate()
            .alpha(0f)
            .translationY(target)
            .setDuration(CLOSE_DURATION_MS)
            .withEndAction {
                visibility = GONE
                // Put back for the next open, which sets its own start position.
                translationY = 0f
            }
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

        /** How much of the finger's travel the drawer follows. Below 1 it feels held. */
        const val PULL_RESISTANCE = 0.55f

        /** Pulled past this fraction of the drawer's height, releasing dismisses it. */
        const val DISMISS_TRAVEL_RATIO = 0.18f

        /** Or flicked down faster than this, in dp per second, at any distance. */
        const val DISMISS_VELOCITY_DP = 900f

        const val VELOCITY_UNITS = 1000
        const val SETTLE_DURATION_MS = 160L

        /**
         * Drawer scrim: the Eden surface colour at the requested opacity, so wallpaper showing
         * through at low opacity still leaves labels readable.
         */
        fun backgroundColorFor(percent: Int): Int =
            Color.argb((percent * 255) / 100, 0x0E, 0x16, 0x11)
    }
}
