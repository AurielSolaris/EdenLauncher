package app.auriel.edenlauncher.wallpaper.live

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import app.auriel.edenlauncher.wallpaper.floatBuffer
import kotlin.random.Random

/**
 * Walkaround: a landscape you look around by tilting the phone.
 *
 * A rewrite of the `walkaround` wallpaper from AOSP's `packages/wallpapers/Basic` (Apache 2.0).
 * It is the only one of the set driven by the physical world rather than a clock, and the only one
 * that responds to you rather than merely being looked at.
 *
 * Layers move by different amounts for the same tilt, which is the whole illusion: the near ridge
 * swings a long way, the far ones barely shift, and the stars do not move at all. Get the ratios
 * wrong and it reads as a picture sliding about behind glass.
 *
 * The accelerometer is registered at the slowest rate the platform offers and dropped entirely
 * while the wallpaper is not being drawn - a sensor left running behind a full-screen app is a
 * battery drain nobody can see the cause of.
 */
class WalkaroundRenderer(context: Context) : WallpaperRenderer, SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val sky = GradientBackground()
    private val stars = SoftPointField(STAR_COUNT)
    private var ridgeProgram: GLProgram? = null

    private val ridgeVertices = FloatArray(
        RIDGE_LAYERS * (RIDGE_POINTS - 1) * VERTICES_PER_SEGMENT * FLOATS_PER_RIDGE_VERTEX,
    )
    private val ridgeBuffer = floatBuffer(ridgeVertices.size)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    /** Tilt in device units, smoothed. Written on the sensor thread, read on the GL thread. */
    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f

    private val starX = FloatArray(STAR_COUNT)
    private val starY = FloatArray(STAR_COUNT)
    private val starSize = FloatArray(STAR_COUNT)

    /** Ridge profile per layer: a height per sample point, in fractions of the screen. */
    private val ridgeHeight = Array(RIDGE_LAYERS) { FloatArray(RIDGE_POINTS) }

    private val random = Random(PARTICLE_SEED)
    private var listening = false

    override fun onSurfaceCreated() {
        sky.onSurfaceCreated()
        stars.onSurfaceCreated()
        ridgeProgram = GLProgram(RIDGE_VERTEX_SHADER, RIDGE_FRAGMENT_SHADER)
        startListening()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until STAR_COUNT) {
            starX[i] = random.nextFloat() * this.width
            starY[i] = random.nextFloat() * this.height * 0.55f
            starSize[i] = this.width * (0.002f + random.nextFloat() * 0.004f)
        }

        // Each ridge is a random walk rather than independent samples: independent ones give a
        // noisy comb, a walk gives a skyline.
        for (layer in 0 until RIDGE_LAYERS) {
            var current = 0.5f
            for (i in 0 until RIDGE_POINTS) {
                current += (random.nextFloat() - 0.5f) * ROUGHNESS
                current = current.coerceIn(0.25f, 0.75f)
                ridgeHeight[layer][i] = current
            }
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        sky.draw(0.055f, 0.086f, 0.180f, 0.404f, 0.278f, 0.271f)

        val pageShift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        // Tilt is in m/s^2 across roughly -9.8..9.8; scaled to a fraction of the screen.
        val lookX = (tiltX / GRAVITY).coerceIn(-1f, 1f) * width * LOOK_TRAVEL_FRACTION
        val lookY = (tiltY / GRAVITY).coerceIn(-1f, 1f) * height * LOOK_LIFT_FRACTION

        drawStars(pageShift)
        drawRidges(pageShift, lookX, lookY)
    }

    private fun drawStars(pageShift: Float) {
        stars.begin()
        for (i in 0 until STAR_COUNT) {
            // Stars sit at infinity: the page offset moves them a little, tilt not at all.
            stars.add(
                starX[i] + pageShift * 0.15f,
                starY[i],
                starSize[i],
                0.85f,
                0.90f,
                1f,
                0.45f,
            )
        }
        stars.draw(width, height, sharpness = 2.5f)
    }

    /**
     * Draws each ridge as a solid band from its skyline down to the bottom of the screen.
     *
     * Triangles, not point sprites: a ridge is a hard-edged silhouette against the sky, and the
     * edge is the only part anyone looks at. Two triangles per segment, back layer first so the
     * nearer ones paint over it.
     */
    private fun drawRidges(pageShift: Float, lookX: Float, lookY: Float) {
        val program = ridgeProgram ?: return
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        var v = 0
        for (layer in 0 until RIDGE_LAYERS) {
            // Layer 0 is farthest. Depth drives how much this layer answers to a tilt, and how
            // dark it is - near ridges are almost black, far ones lift towards the sky.
            val depth = (layer + 1).toFloat() / RIDGE_LAYERS
            val response = depth * depth
            // Far ridges are pale and hazy, near ones nearly black. The spread has to be wide or
            // the layers merge into one silhouette and the parallax has nothing to separate.
            val shade = 0.34f - depth * 0.29f

            for (i in 0 until RIDGE_POINTS - 1) {
                val leftX = ridgeX(i, response, pageShift, lookX)
                val rightX = ridgeX(i + 1, response, pageShift, lookX)
                val leftY = ridgeY(layer, i, depth, response, lookY)
                val rightY = ridgeY(layer, i + 1, depth, response, lookY)
                val bottom = height

                v = putRidgeVertex(v, leftX, leftY, shade, depth)
                v = putRidgeVertex(v, rightX, rightY, shade, depth)
                v = putRidgeVertex(v, leftX, bottom, shade, depth)

                v = putRidgeVertex(v, rightX, rightY, shade, depth)
                v = putRidgeVertex(v, rightX, bottom, shade, depth)
                v = putRidgeVertex(v, leftX, bottom, shade, depth)
            }
        }

        ridgeBuffer.position(0)
        ridgeBuffer.put(ridgeVertices, 0, v)
        ridgeBuffer.position(0)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)

        val position = program.attrib("aPosition")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_RIDGE_VERTEX * Float.SIZE_BYTES

        ridgeBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, ridgeBuffer)
        GLES20.glEnableVertexAttribArray(position)

        ridgeBuffer.position(2)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, stride, ridgeBuffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, v / FLOATS_PER_RIDGE_VERTEX)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun ridgeX(index: Int, response: Float, pageShift: Float, lookX: Float): Float {
        val along = index.toFloat() / (RIDGE_POINTS - 1)
        return along * width * RIDGE_OVERDRAW - width * (RIDGE_OVERDRAW - 1f) * 0.5f +
            pageShift * (0.2f + response) + lookX * response
    }

    private fun ridgeY(
        layer: Int,
        index: Int,
        depth: Float,
        response: Float,
        lookY: Float,
    ): Float = height * (1f - ridgeHeight[layer][index] * (0.34f + depth * 0.30f)) + lookY * response

    private fun putRidgeVertex(
        index: Int,
        x: Float,
        y: Float,
        shade: Float,
        depth: Float,
    ): Int {
        ridgeVertices[index] = x
        ridgeVertices[index + 1] = y
        // A touch of blue in the distant layers: aerial perspective, which is what sells depth in
        // a silhouette with no other cue.
        ridgeVertices[index + 2] = shade * 0.85f
        ridgeVertices[index + 3] = shade * 0.90f
        ridgeVertices[index + 4] = shade * (1.30f - depth * 0.25f)
        return index + FLOATS_PER_RIDGE_VERTEX
    }

    // ---- sensor ---------------------------------------------------------------------------------

    private fun startListening() {
        val sensor = accelerometer ?: return
        if (listening) return
        // SENSOR_DELAY_UI, not GAME: this drives a slow look-around, and a faster stream would be
        // more wakeups for motion no one can see.
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Low-pass filtered: raw accelerometer output is noisy enough that the scene would
        // visibly jitter while the phone sat still on a table.
        tiltX += (event.values[0] - tiltX) * SMOOTHING
        tiltY += (event.values[1] - tiltY) * SMOOTHING
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPaused() = stopListening()

    override fun onResumed() = startListening()

    override fun onSurfaceDestroyed() {
        stopListening()
        sky.release()
        stars.release()
        ridgeProgram?.release()
        ridgeProgram = null
    }

    /** Nothing here evolves on its own, so one frame is already representative. */
    override val previewTimeSeconds: Float get() = 0.5f

    private companion object {
        const val STAR_COUNT = 90
        const val RIDGE_LAYERS = 4
        const val RIDGE_POINTS = 26

        const val ROUGHNESS = 0.16f
        const val GRAVITY = 9.81f
        const val SMOOTHING = 0.12f

        const val LOOK_TRAVEL_FRACTION = 0.13f
        const val LOOK_LIFT_FRACTION = 0.05f
        const val PARALLAX_TRAVEL_FRACTION = 0.10f

        /** Ridges are drawn wider than the screen so looking around never reveals an end. */
        const val RIDGE_OVERDRAW = 1.6f

        const val PARTICLE_SEED = 0x3A1C04

        const val VERTICES_PER_SEGMENT = 6
        const val FLOATS_PER_RIDGE_VERTEX = 5

        const val RIDGE_VERTEX_SHADER = """
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

        const val RIDGE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """
    }
}
