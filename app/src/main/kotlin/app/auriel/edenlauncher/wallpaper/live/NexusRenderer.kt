package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import kotlin.random.Random

/**
 * Nexus: coloured light pulses racing along a grid, on black.
 *
 * A rewrite of the `nexus` wallpaper from AOSP's `packages/wallpapers/Basic` (Apache 2.0) - the
 * Nexus One's wallpaper, and the one most people picture when they hear "Android live wallpaper".
 *
 * The original drew four hues of pulse travelling along fixed horizontal and vertical tracks, each
 * a bright head with a fading tail. That structure is kept exactly, because it is the whole
 * identity of the thing: the tail is drawn as a short run of points behind the head, thinning as
 * it goes, which is both how it looked and cheaper than any line-with-gradient approach.
 *
 * Black background, not a gradient - the pulses are the entire image and anything behind them
 * dulls the effect.
 */
class NexusRenderer : WallpaperRenderer {

    private val pulses = SoftPointField(PULSE_COUNT * TAIL_LENGTH)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    /** Position along the track, 0..1, wrapping. */
    private val progress = FloatArray(PULSE_COUNT)
    private val speed = FloatArray(PULSE_COUNT)
    /** Where the track sits on the perpendicular axis, 0..1. */
    private val track = FloatArray(PULSE_COUNT)
    private val vertical = BooleanArray(PULSE_COUNT)
    private val backwards = BooleanArray(PULSE_COUNT)
    private val hue = IntArray(PULSE_COUNT)
    private val thickness = FloatArray(PULSE_COUNT)

    private var lastTime = 0f
    private val random = Random(PARTICLE_SEED)

    override fun onSurfaceCreated() {
        pulses.onSurfaceCreated()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until PULSE_COUNT) {
            progress[i] = random.nextFloat()
            speed[i] = 0.06f + random.nextFloat() * 0.20f
            // Snapped to a coarse grid: the original ran on tracks, not anywhere it liked.
            track[i] = (random.nextInt(TRACK_SLOTS) + 0.5f) / TRACK_SLOTS
            vertical[i] = random.nextBoolean()
            backwards[i] = random.nextBoolean()
            hue[i] = random.nextInt(HUES.size)
            thickness[i] = width * (0.014f + random.nextFloat() * 0.016f)
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        val delta = (timeSeconds - lastTime).coerceIn(0f, MAX_STEP_SECONDS)
        lastTime = timeSeconds

        // Black, drawn as a clear: there is no gradient behind a Nexus wallpaper.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION

        pulses.begin()
        for (i in 0 until PULSE_COUNT) {
            progress[i] += speed[i] * delta
            if (progress[i] > 1f) {
                progress[i] -= 1f
                // Re-roll on wrap, so the same pulse does not retrace the same track forever.
                track[i] = (random.nextInt(TRACK_SLOTS) + 0.5f) / TRACK_SLOTS
                hue[i] = random.nextInt(HUES.size)
                speed[i] = 0.06f + random.nextFloat() * 0.20f
            }

            val colour = HUES[hue[i]]
            for (t in 0 until TAIL_LENGTH) {
                // The head is t = 0; each step back along the tail is dimmer and smaller.
                val behind = t * TAIL_SPACING
                var along = progress[i] - behind
                if (along < 0f) continue
                if (backwards[i]) along = 1f - along

                val x: Float
                val y: Float
                if (vertical[i]) {
                    x = track[i] * width + shift
                    y = along * height
                } else {
                    x = along * width + shift
                    y = track[i] * height
                }

                val fade = 1f - t.toFloat() / TAIL_LENGTH
                pulses.add(
                    x,
                    y,
                    thickness[i] * (0.4f + fade * 0.6f),
                    colour[0],
                    colour[1],
                    colour[2],
                    // Squared falloff: a bright head and a tail that drops away fast, rather than
                    // an even streak.
                    fade * fade,
                )
            }
        }
        pulses.draw(width, height, sharpness = 1.2f)
    }

    override fun onSurfaceDestroyed() {
        pulses.release()
    }

    override val previewTimeSeconds: Float get() = 3f

    private companion object {
        const val PULSE_COUNT = 26
        const val TAIL_LENGTH = 22

        /**
         * Gap between tail samples, as a fraction of the track.
         *
         * Has to be small enough that consecutive points overlap, or the tail reads as a dotted
         * line instead of a streak. It is deliberately tied to nothing else: widen the points and
         * this must come down with them.
         */
        const val TAIL_SPACING = 0.005f
        const val TRACK_SLOTS = 9
        const val MAX_STEP_SECONDS = 0.1f
        const val PARALLAX_TRAVEL_FRACTION = 0.10f
        const val PARTICLE_SEED = 0x4E3405

        /** The four Nexus hues: red, green, blue, yellow. */
        val HUES = arrayOf(
            floatArrayOf(1.0f, 0.20f, 0.20f),
            floatArrayOf(0.35f, 1.0f, 0.35f),
            floatArrayOf(0.30f, 0.55f, 1.0f),
            floatArrayOf(1.0f, 0.85f, 0.25f),
        )
    }
}
