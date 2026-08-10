package app.auriel.edenlauncher.wallpaper.live

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import app.auriel.edenlauncher.settings.LauncherPrefs
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import app.auriel.edenlauncher.wallpaper.floatBuffer

/**
 * MusicVisualization: a spectrum that rises and falls across the bottom of the screen.
 *
 * A rewrite of AOSP's `packages/wallpapers/MusicVisualization` (Apache 2.0).
 *
 * Where it deliberately differs from the original: the source of the levels is a **choice**. The
 * original simply took the audio, because in 2011 nobody thought twice. Today "this wallpaper
 * would like to record audio" is a reasonable thing to refuse, and refusing it should not mean
 * losing the wallpaper. So Eden defaults to an invented rhythm that needs no permission at all,
 * and reads real audio only if you ask it to. See [AudioLevels].
 */
class MusicVisualizationRenderer(context: Context) : WallpaperRenderer {

    private val appContext = context.applicationContext
    private val prefs = LauncherPrefs(appContext)

    private val background = GradientBackground()
    private val caps = SoftPointField(BAND_COUNT)
    private var barProgram: GLProgram? = null

    private var levels: AudioLevels = AmbientAudioLevels()
    private val bands = FloatArray(BAND_COUNT)

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f

    private val barVertices = FloatArray(BAND_COUNT * VERTICES_PER_BAR * FLOATS_PER_VERTEX)
    private val barBuffer = floatBuffer(barVertices.size)

    override fun onSurfaceCreated() {
        background.onSurfaceCreated()
        caps.onSurfaceCreated()
        barProgram = GLProgram(BAR_VERTEX_SHADER, BAR_FRAGMENT_SHADER)

        levels = resolveSource()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    /**
     * Picks the level source, and falls back rather than failing.
     *
     * Real capture is used only when the user asked for it *and* the permission is actually held.
     * A permission can be revoked after the wallpaper is set, so the held check is not redundant
     * with the setting - without it, revoking would leave a dead wallpaper rather than a
     * gracefully degraded one.
     */
    private fun resolveSource(): AudioLevels {
        if (!prefs.visualizerUsesRealAudio) return AmbientAudioLevels()

        val granted = appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return AmbientAudioLevels()

        val real = RealAudioLevels()
        return if (real.start()) real else AmbientAudioLevels()
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    override fun onDrawFrame(timeSeconds: Float) {
        background.draw(0.055f, 0.031f, 0.086f, 0.012f, 0.012f, 0.031f)
        levels.read(bands, timeSeconds)

        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION
        val barWidth = width / BAND_COUNT
        val baseline = height * BASELINE_FRACTION
        val maxBarHeight = height * MAX_BAR_FRACTION

        var v = 0
        caps.begin()
        for (i in 0 until BAND_COUNT) {
            val level = bands[i]
            val barHeight = level * maxBarHeight
            val left = i * barWidth + barWidth * BAR_GAP + shift
            val right = (i + 1) * barWidth - barWidth * BAR_GAP + shift
            val top = baseline - barHeight

            // Hue runs across the spectrum, so which end is loud is readable at a glance.
            val across = i.toFloat() / BAND_COUNT
            val red = 0.35f + across * 0.60f
            val green = 0.85f - across * 0.45f
            val blue = 0.95f - across * 0.30f

            v = putBarVertex(v, left, baseline, red * 0.35f, green * 0.35f, blue * 0.35f)
            v = putBarVertex(v, right, baseline, red * 0.35f, green * 0.35f, blue * 0.35f)
            v = putBarVertex(v, left, top, red, green, blue)

            v = putBarVertex(v, right, baseline, red * 0.35f, green * 0.35f, blue * 0.35f)
            v = putBarVertex(v, right, top, red, green, blue)
            v = putBarVertex(v, left, top, red, green, blue)

            // A glow riding the top of each bar. Without it the bars read as a bar chart.
            caps.add(
                (left + right) * 0.5f,
                top,
                barWidth * 2.4f,
                red,
                green,
                blue,
                0.25f + level * 0.45f,
            )
        }

        drawBars(v)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        caps.draw(width, height, sharpness = 1.5f)
    }

    private fun drawBars(vertexFloats: Int) {
        val program = barProgram ?: return

        barBuffer.position(0)
        barBuffer.put(barVertices, 0, vertexFloats)
        barBuffer.position(0)

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)

        val position = program.attrib("aPosition")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        barBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, barBuffer)
        GLES20.glEnableVertexAttribArray(position)

        barBuffer.position(2)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, stride, barBuffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexFloats / FLOATS_PER_VERTEX)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun putBarVertex(index: Int, x: Float, y: Float, r: Float, g: Float, b: Float): Int {
        barVertices[index] = x
        barVertices[index + 1] = y
        barVertices[index + 2] = r
        barVertices[index + 3] = g
        barVertices[index + 4] = b
        return index + FLOATS_PER_VERTEX
    }

    /**
     * Lets go of the audio capture while nothing is watching.
     *
     * This is the renderer that most needs the hook. A [Visualizer] holds a real audio effect on
     * the output session and keeps the microphone indicator lit, and neither is acceptable while
     * the wallpaper is sitting behind an open app or an opaque app drawer.
     */
    override fun onPaused() {
        levels.release()
        levels = AmbientAudioLevels()
    }

    override fun onResumed() {
        levels.release()
        levels = resolveSource()
    }

    override fun onSurfaceDestroyed() {
        levels.release()
        background.release()
        caps.release()
        barProgram?.release()
        barProgram = null
    }

    override val previewTimeSeconds: Float get() = 2.5f

    private companion object {
        const val BAND_COUNT = 40
        const val VERTICES_PER_BAR = 6
        const val FLOATS_PER_VERTEX = 5

        const val BASELINE_FRACTION = 0.78f
        const val MAX_BAR_FRACTION = 0.55f
        const val BAR_GAP = 0.16f
        const val PARALLAX_TRAVEL_FRACTION = 0.08f

        const val BAR_VERTEX_SHADER = """
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

        const val BAR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """
    }
}
