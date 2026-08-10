package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * HoloSpiral: a tunnel of light spiralling away into the distance.
 *
 * A rewrite of AOSP's `packages/wallpapers/HoloSpiral` (Apache 2.0), the Honeycomb-era wallpaper
 * that gave Android 3.0 its holographic look. RenderScript in the original, and unbuildable now
 * that the Android Gradle plugin has dropped it.
 *
 * There is no random state here at all: every point is a pure function of its index and the
 * clock. That makes the wallpaper perfectly reproducible - the picker's still frame and the live
 * wallpaper draw the identical scene - and it means nothing needs seeding, storing, or reseeding
 * when the surface changes.
 */
class HoloSpiralRenderer : WallpaperRenderer {

    private val background = GradientBackground()
    private val points = SoftPointField(POINT_COUNT)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

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
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        background.draw(0.020f, 0.055f, 0.086f, 0.008f, 0.020f, 0.039f)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        val centreX = width * 0.5f + shift
        val centreY = height * 0.5f
        val maxRadius = minOf(width, height) * 0.75f

        points.begin()
        for (i in 0 until POINT_COUNT) {
            // Depth runs 0 (far, at the vanishing point) to 1 (near, at the rim), and cycles, so
            // the tunnel appears to move towards the viewer forever without any particle needing
            // to be recycled.
            val depth = ((i.toFloat() / POINT_COUNT) + timeSeconds * TRAVEL_RATE) % 1f

            // Slightly super-linear, so points bunch towards the vanishing point without piling
            // into a single blob in the middle - squaring it, which is what perspective would
            // literally give, collapses the whole spiral into the centre.
            val radius = depth * depth.pow(0.35f) * maxRadius

            val angle = i * ANGLE_STEP + timeSeconds * SPIN_RATE
            val x = centreX + cos(angle) * radius
            val y = centreY + sin(angle) * radius * VERTICAL_SQUASH

            // Near points are larger and brighter; the far ones fade into the vanishing point.
            val size = width * (0.008f + depth * 0.032f)

            // Fades in from the centre and back out at the rim, so nothing pops into or out of
            // existence at either end of the cycle.
            val fade = if (depth < 0.15f) depth / 0.15f else (1f - depth) / 0.85f

            // Cyan through to violet along the tunnel: the Honeycomb palette.
            val red = 0.25f + depth * 0.45f
            val green = 0.80f - depth * 0.35f
            val blue = 1.0f

            points.add(x, y, size, red, green, blue, fade * 0.95f)
        }
        points.draw(width, height, sharpness = 1.3f)
    }

    override fun onSurfaceDestroyed() {
        background.release()
        points.release()
    }

    override val previewTimeSeconds: Float get() = 1f

    private companion object {
        const val POINT_COUNT = 320

        /** Golden-angle-ish step, so successive points never line up into visible spokes. */
        const val ANGLE_STEP = 2.3999632f

        const val SPIN_RATE = 0.35f
        const val TRAVEL_RATE = 0.06f
        const val VERTICAL_SQUASH = 1.0f
        const val PARALLAX_TRAVEL_FRACTION = 0.12f
    }
}
