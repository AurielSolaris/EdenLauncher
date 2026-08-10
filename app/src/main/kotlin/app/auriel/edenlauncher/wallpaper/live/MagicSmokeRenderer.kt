package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftQuadField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * MagicSmoke: slow coloured smoke, curling.
 *
 * A rewrite of AOSP's `packages/wallpapers/MagicSmoke` (Apache 2.0), the Froyo-era wallpaper.
 *
 * The obvious way to do smoke is a fragment shader running fractal noise per pixel. That is also
 * the wrong way here: four octaves of noise at 1080p, thirty times a second, is a real fraction of
 * a budget phone's GPU, spent on a background. Instead this is a few dozen very large, very soft,
 * very dim blobs drifting on overlapping circular paths and summed additively. Where they overlap
 * the colour builds; where they part it thins. At this blur radius the eye reads the result as
 * smoke and cannot tell the difference, and it costs about forty points a frame.
 */
class MagicSmokeRenderer : WallpaperRenderer {

    private val background = GradientBackground()
    // Quads, not points: a puff is most of the screen wide, and point sprites are capped by the
    // driver at a size far below that.
    private val puffs = SoftQuadField(PUFF_COUNT)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    // Each puff wanders on its own slow ellipse; the parameters are what stop them moving as one.
    private val orbitCentreX = FloatArray(PUFF_COUNT)
    private val orbitCentreY = FloatArray(PUFF_COUNT)
    private val orbitRadiusX = FloatArray(PUFF_COUNT)
    private val orbitRadiusY = FloatArray(PUFF_COUNT)
    private val orbitRate = FloatArray(PUFF_COUNT)
    private val orbitPhase = FloatArray(PUFF_COUNT)
    private val puffSize = FloatArray(PUFF_COUNT)
    private val puffHue = FloatArray(PUFF_COUNT)

    private val random = Random(PARTICLE_SEED)

    override fun onSurfaceCreated() {
        background.onSurfaceCreated()
        puffs.onSurfaceCreated()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until PUFF_COUNT) {
            orbitCentreX[i] = random.nextFloat() * this.width
            orbitCentreY[i] = random.nextFloat() * this.height
            orbitRadiusX[i] = this.width * (0.10f + random.nextFloat() * 0.30f)
            orbitRadiusY[i] = this.height * (0.05f + random.nextFloat() * 0.18f)
            // Slow, and deliberately unrelated to each other so the field never resynchronises.
            orbitRate[i] = 0.03f + random.nextFloat() * 0.09f
            orbitPhase[i] = random.nextFloat() * TWO_PI
            puffSize[i] = this.width * (0.28f + random.nextFloat() * 0.42f)
            puffHue[i] = random.nextFloat()
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        background.draw(0.086f, 0.031f, 0.075f, 0.020f, 0.012f, 0.043f)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION

        puffs.begin()
        for (i in 0 until PUFF_COUNT) {
            val angle = orbitPhase[i] + timeSeconds * orbitRate[i]
            val x = orbitCentreX[i] + cos(angle) * orbitRadiusX[i] + shift
            val y = orbitCentreY[i] + sin(angle * 1.3f) * orbitRadiusY[i]

            // The hue drifts as the puff travels, so the smoke changes colour as it curls rather
            // than each blob being one fixed tint forever. Biased bright: additive blending at low
            // alpha washes saturation out, so the colours have to start over-saturated to survive
            // being stacked forty deep.
            val hue = (puffHue[i] + timeSeconds * HUE_DRIFT) % 1f
            val red = 0.62f + 0.38f * sin(hue * TWO_PI)
            val green = 0.62f + 0.38f * sin(hue * TWO_PI + 2.094f)
            val blue = 0.62f + 0.38f * sin(hue * TWO_PI + 4.189f)

            // Very dim individually. Density comes from overlap, not from any one blob.
            puffs.add(x, y, puffSize[i], red, green, blue, PUFF_ALPHA)
        }
        // Sharpness below 1 flattens the falloff into a broad haze rather than a disc with an
        // edge - without it these read as circles, not smoke.
        puffs.draw(width, height, sharpness = 0.7f)
    }

    override fun onSurfaceDestroyed() {
        background.release()
        puffs.release()
    }

    override val previewTimeSeconds: Float get() = 3f

    private companion object {
        const val PUFF_COUNT = 42
        const val PUFF_ALPHA = 0.30f
        const val HUE_DRIFT = 0.012f
        const val TWO_PI = 6.2831855f
        const val PARALLAX_TRAVEL_FRACTION = 0.16f
        const val PARTICLE_SEED = 0x5304E
    }
}
