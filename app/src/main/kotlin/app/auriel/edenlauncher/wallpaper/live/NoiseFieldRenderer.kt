package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * NoiseField: particles carried by a slowly turning flow field.
 *
 * A rewrite of AOSP's `packages/wallpapers/NoiseField` (Apache 2.0), the Android 4.2 wallpaper.
 * The original ran a RenderScript kernel over a noise texture; RenderScript is gone from the
 * Android Gradle plugin as of 9.0, so the field is evaluated on the CPU here instead. At a few
 * hundred particles that is nothing - the whole step is a couple of sine calls each, and it stays
 * off the GPU where this phone actually needs the headroom.
 *
 * The flow field is a sum of two rotating sinusoids rather than true Perlin noise. It is
 * indistinguishable at this scale, has no lookup table, and never needs a seed to stay stable
 * across a preview and the live wallpaper.
 */
class NoiseFieldRenderer : WallpaperRenderer {

    private val background = GradientBackground()
    private val points = SoftPointField(PARTICLE_COUNT)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f
    private var lastTime = 0f

    private val positionX = FloatArray(PARTICLE_COUNT)
    private val positionY = FloatArray(PARTICLE_COUNT)
    private val size = FloatArray(PARTICLE_COUNT)
    private val brightness = FloatArray(PARTICLE_COUNT)
    private val tint = FloatArray(PARTICLE_COUNT)

    private val random = Random(PARTICLE_SEED)

    override fun onSurfaceCreated() {
        background.onSurfaceCreated()
        points.onSurfaceCreated()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
        for (i in 0 until PARTICLE_COUNT) {
            positionX[i] = random.nextFloat() * this.width
            positionY[i] = random.nextFloat() * this.height
            // Relative to the surface, so the picker's small still is a truthful preview of the
            // full-screen wallpaper rather than the same scene at a quarter of the scale.
            size[i] = this.width * (0.012f + random.nextFloat() * 0.030f)
            brightness[i] = 0.30f + random.nextFloat() * 0.5f
            tint[i] = random.nextFloat()
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        val delta = (timeSeconds - lastTime).coerceIn(0f, MAX_STEP_SECONDS)
        lastTime = timeSeconds

        background.draw(0.075f, 0.129f, 0.145f, 0.031f, 0.071f, 0.086f)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION

        points.begin()
        for (i in 0 until PARTICLE_COUNT) {
            val angle = fieldAngle(positionX[i], positionY[i], timeSeconds)
            val speed = width * FLOW_SPEED_FRACTION
            positionX[i] += cos(angle) * speed * delta
            positionY[i] += sin(angle) * speed * delta
            wrap(i)

            // Two-tone: most particles are pale green, a few are warmer, which is what stops the
            // field reading as a single flat colour.
            val warm = tint[i] * tint[i]
            points.add(
                positionX[i] + shift,
                positionY[i],
                size[i],
                0.42f + warm * 0.45f,
                0.85f,
                0.68f - warm * 0.25f,
                brightness[i],
            )
        }
        // A low sharpness spreads each particle into a soft halo. Additive blending over a dark
        // background needs the extra area to read as anything at all - a tight core just vanishes.
        points.draw(width, height, sharpness = 1.4f)
    }

    /**
     * Direction of flow at a point, in radians.
     *
     * Two sinusoids at different spatial frequencies, each drifting in time at a different rate.
     * The mismatch is deliberate: matched rates would make the field repeat visibly.
     */
    private fun fieldAngle(x: Float, y: Float, time: Float): Float {
        val coarse = sin(x * 0.0032f + time * 0.11f) + cos(y * 0.0027f - time * 0.09f)
        val fine = sin((x + y) * 0.0071f + time * 0.23f)
        return (coarse + fine * 0.45f) * 1.9f
    }

    private fun wrap(i: Int) {
        val margin = size[i]
        if (positionX[i] < -margin) positionX[i] = width + margin
        if (positionX[i] > width + margin) positionX[i] = -margin
        if (positionY[i] < -margin) positionY[i] = height + margin
        if (positionY[i] > height + margin) positionY[i] = -margin
    }

    override fun onSurfaceDestroyed() {
        background.release()
        points.release()
    }

    override val previewTimeSeconds: Float get() = 3f

    private companion object {
        const val PARTICLE_COUNT = 220
        /** Surface widths per second. Relative, so the drift reads the same on any screen. */
        const val FLOW_SPEED_FRACTION = 0.045f
        const val PARALLAX_TRAVEL_FRACTION = 0.17f
        const val MAX_STEP_SECONDS = 0.1f
        const val PARTICLE_SEED = 0x0F1E1D
    }
}
