package app.auriel.edenlauncher.wallpaper.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import app.auriel.edenlauncher.R
import kotlin.math.max
import kotlin.math.min

/**
 * Frames a picture against the shape of the screen.
 *
 * Android's own answer to "set this photo as wallpaper" is to hand the bitmap to the system and let
 * it decide what to keep. On a tall phone with a landscape photo that decision is usually wrong,
 * and there is no way to argue with it after the fact - you re-crop in a gallery app and try again.
 *
 * So the crop happens here, before anything is set, and the frame is the real shape of the screen:
 * the bright rectangle is exactly what will be visible, the dimmed surround is what gets cut. Drag
 * to move, pinch to zoom, and the image can never be pulled far enough to leave a blank edge.
 *
 * Nothing allocates in [onDraw] or [onTouchEvent]; the matrix and its scratch array are fields.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var source: Bitmap? = null

    /** Width divided by height of the region that will actually become the wallpaper. */
    private var targetAspect = 0.5f

    private val imageMatrix = Matrix()
    private val values = FloatArray(9)

    /** The keep region, in view coordinates. */
    private val cropRect = RectF()

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint().apply { color = context.getColor(R.color.wallpaper_crop_scrim) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.wallpaper_crop_border)
        strokeWidth = BORDER_DP * resources.displayMetrics.density
    }

    private var minScale = 1f

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                imageMatrix.postScale(
                    detector.scaleFactor,
                    detector.scaleFactor,
                    detector.focusX,
                    detector.focusY,
                )
                constrain()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        },
    )

    /**
     * Sets the picture and the shape to frame it in.
     *
     * @param aspect width over height of the wallpaper, so the frame is the screen and not a guess.
     */
    fun setImage(bitmap: Bitmap, aspect: Float) {
        source = bitmap
        targetAspect = aspect
        if (width > 0 && height > 0) layoutFrame()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutFrame()
    }

    /**
     * Places the frame and fits the picture to it.
     *
     * The frame is inset rather than filling the view, because a crop you cannot see the outside of
     * is a crop you cannot judge. The margin is what makes "this bit is being cut" legible.
     */
    private fun layoutFrame() {
        val bitmap = source ?: return
        if (width <= 0 || height <= 0) return

        val margin = FRAME_MARGIN_DP * resources.displayMetrics.density
        val availableWidth = width - margin * 2
        val availableHeight = height - margin * 2

        var frameWidth = availableWidth
        var frameHeight = frameWidth / targetAspect
        if (frameHeight > availableHeight) {
            frameHeight = availableHeight
            frameWidth = frameHeight * targetAspect
        }

        val cx = width / 2f
        val cy = height / 2f
        cropRect.set(
            cx - frameWidth / 2f,
            cy - frameHeight / 2f,
            cx + frameWidth / 2f,
            cy + frameHeight / 2f,
        )

        // Start at the smallest scale that still covers the frame, centred. That is the crop most
        // people want, and every gesture from here is a deliberate change to it.
        minScale = max(
            cropRect.width() / bitmap.width,
            cropRect.height() / bitmap.height,
        )
        imageMatrix.reset()
        imageMatrix.postScale(minScale, minScale)
        imageMatrix.postTranslate(
            cropRect.centerX() - bitmap.width * minScale / 2f,
            cropRect.centerY() - bitmap.height * minScale / 2f,
        )
        constrain()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                // Recomputed whenever the pointer set changes, so lifting one finger mid-pinch
                // does not throw the image across the screen.
                updateFocus(event)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> if (!isScaling) {
                val previousX = lastFocusX
                val previousY = lastFocusY
                updateFocus(event)
                imageMatrix.postTranslate(lastFocusX - previousX, lastFocusY - previousY)
                constrain()
                invalidate()
            } else {
                updateFocus(event)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    /** Average of every pointer down, which makes pan and pinch-drag the same gesture. */
    private fun updateFocus(event: MotionEvent) {
        var sumX = 0f
        var sumY = 0f
        var counted = 0
        for (i in 0 until event.pointerCount) {
            // A pointer that is going up is still in the event but no longer part of the gesture.
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) {
                continue
            }
            sumX += event.getX(i)
            sumY += event.getY(i)
            counted++
        }
        if (counted == 0) return
        lastFocusX = sumX / counted
        lastFocusY = sumY / counted
    }

    /** Keeps the zoom sane and the frame fully covered. */
    private fun constrain() {
        val bitmap = source ?: return

        imageMatrix.getValues(values)
        var scale = values[Matrix.MSCALE_X]
        val maxScale = minScale * MAX_ZOOM
        if (scale < minScale || scale > maxScale) {
            val corrected = min(max(scale, minScale), maxScale)
            imageMatrix.postScale(
                corrected / scale,
                corrected / scale,
                cropRect.centerX(),
                cropRect.centerY(),
            )
            imageMatrix.getValues(values)
            scale = corrected
        }

        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val left = values[Matrix.MTRANS_X]
        val top = values[Matrix.MTRANS_Y]

        val dx = when {
            left > cropRect.left -> cropRect.left - left
            left + drawnWidth < cropRect.right -> cropRect.right - (left + drawnWidth)
            else -> 0f
        }
        val dy = when {
            top > cropRect.top -> cropRect.top - top
            top + drawnHeight < cropRect.bottom -> cropRect.bottom - (top + drawnHeight)
            else -> 0f
        }
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = source ?: return
        canvas.drawBitmap(bitmap, imageMatrix, imagePaint)

        // Dim everything outside the frame in four bands rather than with a clip-difference, which
        // would cost a saveLayer on every frame of a drag.
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

        canvas.drawRect(cropRect, borderPaint)
    }

    /**
     * The framed region as fractions of the picture, so the caller can apply it to the original
     * file at full resolution rather than to whatever downsampled copy is on screen.
     *
     * Returns null before an image has been laid out.
     */
    fun normalisedCrop(): RectF? {
        val bitmap = source ?: return null
        if (cropRect.isEmpty) return null

        imageMatrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        if (scale <= 0f) return null
        val left = values[Matrix.MTRANS_X]
        val top = values[Matrix.MTRANS_Y]

        return RectF(
            ((cropRect.left - left) / scale / bitmap.width).coerceIn(0f, 1f),
            ((cropRect.top - top) / scale / bitmap.height).coerceIn(0f, 1f),
            ((cropRect.right - left) / scale / bitmap.width).coerceIn(0f, 1f),
            ((cropRect.bottom - top) / scale / bitmap.height).coerceIn(0f, 1f),
        )
    }

    private companion object {
        const val FRAME_MARGIN_DP = 24f
        const val BORDER_DP = 2f

        /** How far past "just covers the frame" a picture can be zoomed before it is only mush. */
        const val MAX_ZOOM = 6f
    }
}
