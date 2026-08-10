package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import app.auriel.edenlauncher.wallpaper.floatBuffer
import java.util.Calendar
import kotlin.math.sin
import kotlin.random.Random

/**
 * Grass: a silhouetted meadow under a sky that tracks the actual time of day.
 *
 * A rewrite of AOSP's `packages/wallpapers/Basic` Grass (Apache 2.0), the Android 2.x classic.
 *
 * The thing that made the original memorable was not the grass, it was that the sky was **your**
 * sky: dark at midnight, pink at dawn, blue at noon, with stars that only came out when it was
 * actually night where you were. It is the only wallpaper of the four that knows anything about
 * the world outside the phone, and it is the reason people still ask for it.
 *
 * The clock is read once a minute, not once a frame. Nobody notices the sky changing at 30 Hz,
 * and `Calendar` allocation in a render loop is exactly the kind of thing that shows up as jank
 * on a two-core phone.
 */
class GrassRenderer : WallpaperRenderer {

    private val sky = GradientBackground()
    private val stars = SoftPointField(STAR_COUNT)
    private val sun = SoftPointField(1)
    private var bladeProgram: GLProgram? = null

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    private val bladeBaseX = FloatArray(BLADE_COUNT)
    private val bladeHeight = FloatArray(BLADE_COUNT)
    private val bladeWidth = FloatArray(BLADE_COUNT)
    private val bladeLean = FloatArray(BLADE_COUNT)
    private val bladePhase = FloatArray(BLADE_COUNT)
    /** 0 is the far layer, 1 the near one; drives both colour and sway amplitude. */
    private val bladeDepth = FloatArray(BLADE_COUNT)

    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)
    private val starPhase = FloatArray(STAR_COUNT)

    private val bladeVertices = FloatArray(BLADE_COUNT * VERTICES_PER_BLADE * FLOATS_PER_VERTEX)
    private val bladeBuffer = floatBuffer(bladeVertices.size)

    private val random = Random(PARTICLE_SEED)

    /** Fraction of the day elapsed, 0 at midnight. Refreshed on [CLOCK_INTERVAL_SECONDS]. */
    private var dayFraction = 0.5f
    private var lastClockRead = -CLOCK_INTERVAL_SECONDS

    override fun onSurfaceCreated() {
        sky.onSurfaceCreated()
        stars.onSurfaceCreated()
        sun.onSurfaceCreated()
        bladeProgram = GLProgram(BLADE_VERTEX_SHADER, BLADE_FRAGMENT_SHADER)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until BLADE_COUNT) {
            val depth = i.toFloat() / BLADE_COUNT
            bladeDepth[i] = depth
            // Spread across rather more than the screen, so parallax never reveals an edge.
            bladeBaseX[i] = (random.nextFloat() * 1.4f - 0.2f) * this.width
            bladeHeight[i] = this.height * (0.10f + depth * 0.22f + random.nextFloat() * 0.10f)
            bladeWidth[i] = this.width * (0.006f + random.nextFloat() * 0.012f)
            bladeLean[i] = (random.nextFloat() - 0.5f) * 0.5f
            bladePhase[i] = random.nextFloat() * TWO_PI
        }

        for (i in 0 until STAR_COUNT) {
            starX[i] = random.nextFloat() * this.width
            // Stars only in the upper two thirds; the horizon is where the grass is.
            starY[i] = random.nextFloat() * this.height * 0.62f
            starSize[i] = 1.5f + random.nextFloat() * 3f
            starPhase[i] = random.nextFloat() * TWO_PI
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        if (timeSeconds - lastClockRead >= CLOCK_INTERVAL_SECONDS) {
            lastClockRead = timeSeconds
            dayFraction = readDayFraction()
        }

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        drawSky()
        drawStars(timeSeconds)
        drawSun(shift)
        drawBlades(timeSeconds, shift)
    }

    private fun readDayFraction(): Float {
        val calendar = Calendar.getInstance()
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minutes / (24f * 60f)
    }

    // ---- sky ------------------------------------------------------------------------------------

    /**
     * Sky colour keyframes through the day: night, dawn, morning, noon, afternoon, dusk, night.
     *
     * Each entry is the hour it applies at, then top and bottom RGB. Interpolating between two
     * stops is what gives the long slow slide through sunrise rather than a jump between presets.
     */
    private fun skyStop(index: Int): FloatArray = SKY_STOPS[index]

    private fun drawSky() {
        val hour = dayFraction * 24f

        var next = 0
        while (next < SKY_STOPS.size && skyStop(next)[0] <= hour) next++
        val upper = if (next >= SKY_STOPS.size) 0 else next
        val lower = if (next == 0) SKY_STOPS.size - 1 else next - 1

        val lowerStop = skyStop(lower)
        val upperStop = skyStop(upper)

        // Wrap the span across midnight so the last stop blends into the first.
        var span = upperStop[0] - lowerStop[0]
        if (span <= 0f) span += 24f
        var into = hour - lowerStop[0]
        if (into < 0f) into += 24f
        val t = (into / span).coerceIn(0f, 1f)

        sky.draw(
            mix(lowerStop[1], upperStop[1], t),
            mix(lowerStop[2], upperStop[2], t),
            mix(lowerStop[3], upperStop[3], t),
            mix(lowerStop[4], upperStop[4], t),
            mix(lowerStop[5], upperStop[5], t),
            mix(lowerStop[6], upperStop[6], t),
        )
    }

    /** 1 in the dead of night, 0 in daylight. Drives whether stars are visible at all. */
    private fun nightAmount(): Float {
        val hour = dayFraction * 24f
        return when {
            hour < NIGHT_END - 1f || hour > NIGHT_START + 1f -> 1f
            hour < NIGHT_END + 1f -> ((NIGHT_END + 1f - hour) / 2f).coerceIn(0f, 1f)
            hour > NIGHT_START - 1f -> ((hour - (NIGHT_START - 1f)) / 2f).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private fun drawStars(time: Float) {
        val night = nightAmount()
        if (night <= 0.01f) return

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        stars.begin()
        for (i in 0 until STAR_COUNT) {
            val twinkle = 0.6f + 0.4f * sin(time * 1.3f + starPhase[i])
            stars.add(starX[i], starY[i], starSize[i], 0.9f, 0.94f, 1f, night * twinkle * 0.7f)
        }
        stars.draw(width, height, sharpness = 3f)
    }

    /** The sun or the moon, whichever is up, arcing across the sky over the course of its half. */
    private fun drawSun(shift: Float) {
        val hour = dayFraction * 24f
        val isDay = hour in NIGHT_END..NIGHT_START

        val progress = if (isDay) {
            (hour - NIGHT_END) / (NIGHT_START - NIGHT_END)
        } else {
            val nightHour = if (hour > NIGHT_START) hour - NIGHT_START else hour + (24f - NIGHT_START)
            nightHour / (24f - NIGHT_START + NIGHT_END)
        }

        val x = width * (0.1f + progress * 0.8f) + shift * 0.25f
        // A shallow arc: highest at the middle of its half of the day.
        val arc = sin(progress * PI_F)
        val y = height * (0.62f - arc * 0.46f)

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        sun.begin()
        if (isDay) {
            sun.add(x, y, width * 0.22f, 1f, 0.93f, 0.72f, 0.55f)
        } else {
            sun.add(x, y, width * 0.13f, 0.88f, 0.92f, 1f, 0.42f)
        }
        sun.draw(width, height, sharpness = 2.5f)
    }

    // ---- grass ----------------------------------------------------------------------------------

    private fun drawBlades(time: Float, shift: Float) {
        val program = bladeProgram ?: return

        // Wind gusts: a slow envelope over a faster oscillation, so the meadow breathes instead of
        // ticking like a metronome.
        val gust = 0.55f + 0.45f * sin(time * GUST_RATE)

        var v = 0
        for (i in 0 until BLADE_COUNT) {
            val depth = bladeDepth[i]
            val baseY = height + BASE_SINK_PX
            val baseX = bladeBaseX[i] + shift * (0.25f + depth * 0.9f)
            val half = bladeWidth[i] * 0.5f

            val sway = sin(time * SWAY_RATE + bladePhase[i]) *
                gust * width * SWAY_FRACTION * (0.3f + depth)
            val tipX = baseX + bladeLean[i] * bladeHeight[i] * 0.35f + sway
            val tipY = baseY - bladeHeight[i]

            // Far blades are darker and bluer, which is all the aerial perspective a silhouette
            // needs to read as having depth.
            val shade = 0.35f + depth * 0.65f
            val night = nightAmount()
            val red = (0.05f + shade * 0.20f) * (1f - night * 0.75f)
            val green = (0.18f + shade * 0.45f) * (1f - night * 0.72f)
            val blue = (0.10f + shade * 0.18f) * (1f - night * 0.60f)

            v = putBladeVertex(v, baseX - half, baseY, red, green, blue)
            v = putBladeVertex(v, baseX + half, baseY, red, green, blue)
            v = putBladeVertex(v, tipX, tipY, red, green, blue)
        }

        bladeBuffer.position(0)
        bladeBuffer.put(bladeVertices)
        bladeBuffer.position(0)

        // Blades occlude what is behind them, so this is the one pass that is not additive.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)

        val position = program.attrib("aPosition")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        bladeBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, bladeBuffer)
        GLES20.glEnableVertexAttribArray(position)

        bladeBuffer.position(2)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, stride, bladeBuffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, BLADE_COUNT * VERTICES_PER_BLADE)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun putBladeVertex(index: Int, x: Float, y: Float, r: Float, g: Float, b: Float): Int {
        bladeVertices[index] = x
        bladeVertices[index + 1] = y
        bladeVertices[index + 2] = r
        bladeVertices[index + 3] = g
        bladeVertices[index + 4] = b
        return index + FLOATS_PER_VERTEX
    }

    override fun onSurfaceDestroyed() {
        sky.release()
        stars.release()
        sun.release()
        bladeProgram?.release()
        bladeProgram = null
    }

    /**
     * The scene is driven by the wall clock rather than elapsed time, so it looks like itself
     * immediately - no warm-up needed.
     */
    override val previewTimeSeconds: Float get() = 0.5f

    private companion object {
        const val BLADE_COUNT = 150
        const val STAR_COUNT = 70

        const val VERTICES_PER_BLADE = 3
        const val FLOATS_PER_VERTEX = 5

        const val PI_F = 3.14159265f
        const val TWO_PI = PI_F * 2f

        const val SWAY_RATE = 1.15f

        /** Sway amplitude as a fraction of the surface width, so it scales with the screen. */
        const val SWAY_FRACTION = 0.024f
        const val GUST_RATE = 0.21f

        /** Blade bases sit below the bottom edge so no blade shows a cut-off root. */
        const val BASE_SINK_PX = 12f

        const val PARALLAX_TRAVEL_FRACTION = 0.11f
        const val CLOCK_INTERVAL_SECONDS = 60f

        /** Sunrise and sunset, in hours. Fixed rather than computed: no location permission. */
        const val NIGHT_END = 6f
        const val NIGHT_START = 19f

        const val PARTICLE_SEED = 0x64A55

        /** hour, topR, topG, topB, bottomR, bottomG, bottomB */
        val SKY_STOPS = arrayOf(
            floatArrayOf(0f, 0.016f, 0.024f, 0.075f, 0.043f, 0.055f, 0.110f),
            floatArrayOf(5f, 0.055f, 0.055f, 0.145f, 0.204f, 0.129f, 0.169f),
            floatArrayOf(7f, 0.239f, 0.361f, 0.612f, 0.929f, 0.616f, 0.416f),
            floatArrayOf(12f, 0.243f, 0.514f, 0.816f, 0.647f, 0.831f, 0.925f),
            floatArrayOf(17f, 0.271f, 0.478f, 0.749f, 0.914f, 0.741f, 0.494f),
            floatArrayOf(19f, 0.157f, 0.153f, 0.353f, 0.851f, 0.404f, 0.278f),
            floatArrayOf(21f, 0.031f, 0.043f, 0.114f, 0.114f, 0.086f, 0.153f),
        )

        fun mix(from: Float, to: Float, t: Float): Float = from + (to - from) * t

        /** Blades are given in pixels with y down, the same convention as the rest of the app. */
        const val BLADE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec3 aColor;
            uniform vec2 uResolution;
            varying vec3 vColor;
            void main() {
                vColor = aColor;
                vec2 clip = aPosition / uResolution * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
            }
        """

        const val BLADE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """
    }
}
