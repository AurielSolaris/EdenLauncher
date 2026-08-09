package app.auriel.edenlauncher.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The icon that follows the finger during a drag.
 *
 * Ported from `DragView` (AOSP 7). It is a bare [View] drawing one bitmap: it is added to the
 * drag layer, moved by [move] on every touch event, and removed when the gesture ends. Position
 * is set with [setTranslationX]/[setTranslationY] rather than layout params so no layout pass
 * runs while the finger is moving.
 */
class DragView(
    context: Context,
    private val bitmap: Bitmap,
    /** Offset from the touch point to the bitmap's top-left, keeping the icon under the finger. */
    private val registrationX: Int,
    private val registrationY: Int,
    initialScale: Float,
) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var scaleAnimator: ValueAnimator? = null

    /** Where the drag layer should place this view for a given touch point. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        scaleX = initialScale
        scaleY = initialScale
        // The lifted icon is drawn slightly transparent so the drop target underneath stays legible.
        alpha = DRAG_ALPHA
        pivotX = bitmap.width / 2f
        pivotY = bitmap.height / 2f
    }

    val dragWidth: Int get() = bitmap.width
    val dragHeight: Int get() = bitmap.height

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(bitmap.width, bitmap.height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    /** Grows the icon as it lifts off, so the drag reads as picking something up. */
    fun animateLift(targetScale: Float) {
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(scaleX, targetScale).apply {
            duration = LIFT_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val value = it.animatedValue as Float
                scaleX = value
                scaleY = value
            }
            start()
        }
    }

    /** Moves the icon so it sits under the touch point at ([touchX], [touchY]). */
    fun move(touchX: Int, touchY: Int) {
        lastTouchX = touchX.toFloat()
        lastTouchY = touchY.toFloat()
        translationX = touchX - registrationX.toFloat()
        translationY = touchY - registrationY.toFloat()
    }

    /** Removes the view from its parent; safe to call more than once. */
    fun remove() {
        scaleAnimator?.cancel()
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    companion object {
        const val DRAG_ALPHA = 0.85f
        const val LIFT_SCALE = 1.15f
        private const val LIFT_DURATION_MS = 150L
    }
}
