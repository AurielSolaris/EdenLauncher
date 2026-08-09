package app.auriel.edenlauncher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.model.LauncherAppWidgetInfo

/**
 * Stands in for a widget that the launcher stores but cannot yet render.
 *
 * Widget hosting needs an `AppWidgetHost` bound to the activity lifecycle, a provider list from
 * `AppWidgetManager`, the `BIND_APPWIDGET` permission flow, and resize frames - a phase of its own.
 * Until then, a stored widget row would simply vanish from the screen, and the user would have no
 * way to tell whether it was lost or merely not drawn. This tile says which.
 *
 * The row, its `appWidgetId`, and its provider stay in the database throughout, so the widget
 * reappears intact once hosting lands. See [app.auriel.edenlauncher.model.LauncherAppWidgetInfo].
 */
class WidgetPlaceholderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.widget_placeholder_fill)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.eden_moss)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text_secondary)
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.scaledDensity
    }

    private val bounds = RectF()
    private val cornerRadius = 12f * resources.displayMetrics.density

    private var label: String = context.getString(R.string.widget_placeholder)

    fun applyFrom(info: LauncherAppWidgetInfo) {
        label = info.providerName?.packageName ?: context.getString(R.string.widget_placeholder)
        contentDescription = context.getString(R.string.widget_placeholder)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = strokePaint.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, strokePaint)

        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }
}
