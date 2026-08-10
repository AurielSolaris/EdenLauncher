package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * PolarClock: the time as a set of concentric arcs.
 *
 * A rewrite of the `polarclock` wallpaper from AOSP's `packages/wallpapers/Basic` (Apache 2.0).
 * Unlike the rest of the set this one is not decoration - it tells you the time, and it is the
 * only wallpaper here where being wrong is a bug rather than a matter of taste.
 *
 * Six rings from the inside out: seconds, minutes, hours, day of week, day of month, month. Each
 * arc runs from the top and fills clockwise in proportion to how far through its own unit it is.
 * The seconds ring sweeps smoothly rather than ticking, using the millisecond field - a ticking
 * ring at 30 fps looks broken rather than deliberate.
 */
class PolarClockRenderer : WallpaperRenderer {

    private val background = GradientBackground()
    private val arcPoints = SoftPointField(RING_COUNT * SEGMENTS_PER_RING)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    private val calendar: Calendar = Calendar.getInstance()

    /** Fill fraction per ring, 0..1. A field so reading the clock allocates nothing. */
    private val fill = FloatArray(RING_COUNT)

    override fun onSurfaceCreated() {
        background.onSurfaceCreated()
        arcPoints.onSurfaceCreated()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        background.draw(0.043f, 0.047f, 0.063f, 0.012f, 0.016f, 0.024f)
        readClock()

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        val centreX = width * 0.5f + shift
        val centreY = height * 0.45f
        val innerRadius = minOf(width, height) * 0.14f
        val ringGap = minOf(width, height) * 0.078f

        arcPoints.begin()
        for (ring in 0 until RING_COUNT) {
            val radius = innerRadius + ring * ringGap
            val colour = RING_COLOURS[ring]
            val filled = fill[ring]

            for (s in 0 until SEGMENTS_PER_RING) {
                val along = s.toFloat() / (SEGMENTS_PER_RING - 1)

                // Start at twelve o'clock and run clockwise, which is the only orientation anyone
                // reads a clock in.
                val angle = -HALF_PI + along * TWO_PI

                // The unfilled remainder of each ring stays visible but very dim, so you can see
                // how far through the unit you are rather than just where the head is.
                val isFilled = along <= filled
                val alpha = if (isFilled) 1f else 0.16f

                arcPoints.add(
                    centreX + cos(angle) * radius,
                    centreY + sin(angle) * radius,
                    width * if (isFilled) 0.022f else 0.013f,
                    colour[0],
                    colour[1],
                    colour[2],
                    alpha,
                )
            }
        }
        arcPoints.draw(width, height, sharpness = 1.2f)
    }

    /**
     * Fills [fill] from the wall clock.
     *
     * Read every frame rather than on an interval, unlike the sky in Grass: the seconds ring is
     * the whole point, and a clock that updates once a minute is not a clock.
     */
    private fun readClock() {
        calendar.timeInMillis = System.currentTimeMillis()

        val millis = calendar.get(Calendar.MILLISECOND)
        val second = calendar.get(Calendar.SECOND)
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        // Each ring includes the fraction of the ring inside it, so nothing ever jumps: the
        // minutes arc creeps forward continuously instead of stepping once every sixty seconds.
        val secondFill = (second + millis / 1000f) / 60f
        val minuteFill = (minute + secondFill) / 60f
        val hourFill = (hour + minuteFill) / 24f

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - calendar.firstDayOfWeek
        val normalisedDayOfWeek = ((dayOfWeek % 7) + 7) % 7

        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH) - 1
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        fill[0] = secondFill
        fill[1] = minuteFill
        fill[2] = hourFill
        fill[3] = (normalisedDayOfWeek + hourFill) / 7f
        fill[4] = (dayOfMonth + hourFill) / daysInMonth
        fill[5] = (calendar.get(Calendar.MONTH) + fill[4]) / 12f
    }

    override fun onSurfaceDestroyed() {
        background.release()
        arcPoints.release()
    }

    /** Driven by the wall clock, so it looks like itself on the very first frame. */
    override val previewTimeSeconds: Float get() = 0.5f

    private companion object {
        const val RING_COUNT = 6
        const val SEGMENTS_PER_RING = 90

        const val TWO_PI = 6.2831855f
        const val HALF_PI = 1.5707963f
        const val PARALLAX_TRAVEL_FRACTION = 0.10f

        /** Inside out: seconds, minutes, hours, weekday, day, month. */
        val RING_COLOURS = arrayOf(
            floatArrayOf(1.00f, 0.42f, 0.42f),
            floatArrayOf(1.00f, 0.71f, 0.35f),
            floatArrayOf(1.00f, 0.94f, 0.45f),
            floatArrayOf(0.52f, 0.93f, 0.60f),
            floatArrayOf(0.42f, 0.76f, 1.00f),
            floatArrayOf(0.76f, 0.58f, 1.00f),
        )
    }
}
