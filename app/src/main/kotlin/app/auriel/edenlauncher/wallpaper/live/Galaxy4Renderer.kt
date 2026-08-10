package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Galaxy4: a spiral galaxy turning over a starfield.
 *
 * A rewrite of AOSP's `packages/wallpapers/Galaxy4` (Apache 2.0). The original is RenderScript
 * over a particle texture and cannot be built since the Android Gradle plugin dropped RenderScript
 * in 9.0.
 *
 * The one piece of physics worth keeping is **differential rotation**: the inner disc turns faster
 * than the rim, so the arms wind up over time instead of spinning as a rigid pinwheel. That is
 * what makes it read as a galaxy rather than a decal, and it is the detail most reimplementations
 * drop.
 *
 * Of the four bundled wallpapers this is the heaviest, so it is also the one with the tightest
 * budget: the disc is stepped as an angle per particle rather than an integrated velocity, which
 * means no accumulation error, no reseeding, and one sine and one cosine per particle per frame.
 */
class Galaxy4Renderer : WallpaperRenderer {

    private val background = GradientBackground()
    private val stars = SoftPointField(STAR_COUNT)
    private val disc = SoftPointField(DISC_COUNT)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    // Starfield: fixed positions, each twinkling on its own clock.
    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)
    private val starDepth = FloatArray(STAR_COUNT)

    // Disc particles in polar coordinates about the galactic centre.
    private val discRadius = FloatArray(DISC_COUNT)
    private val discAngle = FloatArray(DISC_COUNT)
    private val discSize = FloatArray(DISC_COUNT)
    private val discBrightness = FloatArray(DISC_COUNT)
    /** Vertical squash, so the disc reads as a plane seen at an angle rather than a flat circle. */
    private val discLift = FloatArray(DISC_COUNT)

    private val random = Random(PARTICLE_SEED)

    override fun onSurfaceCreated() {
        background.onSurfaceCreated()
        stars.onSurfaceCreated()
        disc.onSurfaceCreated()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until STAR_COUNT) {
            starX[i] = random.nextFloat() * this.width
            starY[i] = random.nextFloat() * this.height
            starSize[i] = this.width * (0.002f + random.nextFloat() * 0.005f)
            starPhase[i] = random.nextFloat() * TWO_PI
            // Depth drives both brightness and how far a star shifts under parallax.
            starDepth[i] = random.nextFloat()
        }

        val maxRadius = minOf(this.width, this.height) * 0.62f
        for (i in 0 until DISC_COUNT) {
            // sqrt of a uniform sample gives an even areal density; a plain uniform radius would
            // pile every particle into the centre.
            val t = sqrt(random.nextFloat())
            discRadius[i] = CORE_RADIUS_FRACTION * maxRadius + t * maxRadius

            // Two arms: the offset is what separates them, the log term is what curves them.
            val arm = if (random.nextBoolean()) 0f else PI_F
            discAngle[i] = arm + t * ARM_WINDING + (random.nextFloat() - 0.5f) * ARM_SCATTER
            // Sized against the disc, not the pixel grid, so the galaxy is the same galaxy at any
            // resolution - and the picker's small still shows the wallpaper you will actually get.
            discSize[i] = maxRadius * (0.030f + random.nextFloat() * 0.075f)
            discBrightness[i] = 0.30f + random.nextFloat() * 0.5f
            discLift[i] = (random.nextFloat() - 0.5f) * 0.18f
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        background.draw(0.063f, 0.043f, 0.110f, 0.016f, 0.016f, 0.043f)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        drawStars(timeSeconds, shift)
        drawDisc(timeSeconds, shift)
    }

    private fun drawStars(time: Float, shift: Float) {
        stars.begin()
        for (i in 0 until STAR_COUNT) {
            // Nearer stars are brighter and travel further with the pages.
            val depth = starDepth[i]
            val twinkle = 0.65f + 0.35f * sin(time * TWINKLE_RATE + starPhase[i])
            stars.add(
                starX[i] + shift * (0.35f + depth * 0.65f),
                starY[i],
                starSize[i],
                0.85f,
                0.88f,
                1.0f,
                (0.35f + depth * 0.5f) * twinkle,
            )
        }
        stars.draw(width, height, sharpness = 2f)
    }

    private fun drawDisc(time: Float, shift: Float) {
        val centreX = width * 0.5f + shift * 1.35f
        val centreY = height * 0.46f
        val maxRadius = minOf(width, height) * 0.62f

        disc.begin()
        for (i in 0 until DISC_COUNT) {
            val radius = discRadius[i]

            // Differential rotation: angular speed falls off with radius, so the arms wind up.
            val angularSpeed = BASE_ANGULAR_SPEED / (0.35f + radius / maxRadius)
            val angle = discAngle[i] + time * angularSpeed

            val x = centreX + cos(angle) * radius
            val y = centreY + sin(angle) * radius * DISC_FLATTEN + discLift[i] * radius

            // Warm at the core, blue at the rim - the colour gradient of an actual spiral.
            val t = (radius / maxRadius).coerceIn(0f, 1f)
            val red = 1.0f - t * 0.55f
            val green = 0.80f - t * 0.20f
            val blue = 0.55f + t * 0.45f

            // The core glows: brightness falls off exponentially with radius.
            val glow = exp(-t * 1.4f)
            disc.add(x, y, discSize[i], red, green, blue, discBrightness[i] * (0.55f + glow))
        }
        disc.draw(width, height, sharpness = 1.4f)
    }

    override fun onSurfaceDestroyed() {
        background.release()
        stars.release()
        disc.release()
    }

    override val previewTimeSeconds: Float get() = 2f

    private companion object {
        const val STAR_COUNT = 140
        const val DISC_COUNT = 300

        const val PI_F = 3.14159265f
        const val TWO_PI = PI_F * 2f

        /** Radians per second at the core; the rim turns proportionally slower. */
        const val BASE_ANGULAR_SPEED = 0.085f

        /** How far the arms sweep from core to rim, in radians. */
        const val ARM_WINDING = 2.9f

        /** Angular spread within an arm, so it is a band rather than a wire. */
        const val ARM_SCATTER = 0.85f

        const val CORE_RADIUS_FRACTION = 0.04f
        const val DISC_FLATTEN = 0.55f
        const val TWINKLE_RATE = 1.7f
        const val PARALLAX_TRAVEL_FRACTION = 0.14f
        const val PARTICLE_SEED = 0x6A1A34
    }
}
