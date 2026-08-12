package app.auriel.edenlauncher.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import app.auriel.edenlauncher.R
import kotlin.math.abs

/**
 * The view a hosted widget draws into.
 *
 * Ported from `LauncherAppWidgetHostView` (AOSP 7), reduced to the one problem that actually has
 * to be solved: a widget is a foreign view hierarchy that wants every touch it can get, and the
 * launcher needs a long press on it to start a drag. Without the intercept below, a widget with a
 * button in it can never be moved, because the button swallows the press.
 *
 * So the long press is detected here, above the widget's own children. A press that becomes a long
 * press is claimed - the widget never sees the rest of it - and anything shorter, or anything that
 * moves past the touch slop, is handed straight through and the widget behaves normally.
 */
class LauncherAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** True once [longPressTrigger] has fired, so the rest of the gesture stays claimed. */
    private var claimed = false

    // Allocated once. This runs on every press on a widget, and the touch path must not allocate.
    private val longPressTrigger = Runnable {
        claimed = true
        // Cancel the widget's own view of the gesture, or a button under the finger stays visibly
        // pressed for as long as the drag lasts.
        cancelChildTouches()
        performLongClick()
    }

    init {
        // A widget's internal focus order is its own business, and letting it into the launcher's
        // focus traversal makes the workspace unnavigable with a keyboard.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isLongClickable = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPressTrigger, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    cancelLongPress()
                }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
        return claimed
    }

    /**
     * Once the intercept has claimed the gesture the events arrive here instead. They belong to the
     * drag controller, which reads them off the drag layer, so nothing further is done with them -
     * but they must be consumed, or the gesture is handed back to the widget mid-drag.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = claimed

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressTrigger)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressTrigger)
        super.onDetachedFromWindow()
    }

    private fun cancelChildTouches() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            for (i in 0 until childCount) getChildAt(i).dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /**
     * Shown when the provider's RemoteViews fail to inflate - a broken update, or a provider
     * upgraded underneath us. The platform default is a bare grey box with no explanation, which on
     * a dark wallpaper reads as a rendering bug in the launcher rather than one in the widget.
     */
    override fun getErrorView(): View = ErrorView(context)

    private class ErrorView(context: Context) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.widget_placeholder_fill)
        }

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.eden_moss)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }

        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.settings_text_secondary)
            textAlign = Paint.Align.CENTER
            textSize = 12f * resources.displayMetrics.scaledDensity
        }

        private val bounds = RectF()
        private val radius = 12f * resources.displayMetrics.density
        private val label = context.getString(R.string.widget_error)

        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth / 2f
            bounds.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(bounds, radius, radius, fill)
            canvas.drawRoundRect(bounds, radius, radius, stroke)
            val baseline = height / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, width / 2f, baseline, text)
        }
    }
}
