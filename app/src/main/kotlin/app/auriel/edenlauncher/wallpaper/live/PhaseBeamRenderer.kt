package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import app.auriel.edenlauncher.wallpaper.floatBuffer
import kotlin.random.Random

/**
 * PhaseBeam: drifting dots and elongated light beams over a deep blue gradient.
 *
 * A rewrite of AOSP's `packages/wallpapers/PhaseBeam` (Apache 2.0), which was the Android 4.x
 * default. The original is RenderScript - `phasebeam.rs` plus a `ScriptC` wrapper - and
 * RenderScript was deprecated in Android 12 and removed outright from the Android Gradle plugin
 * in 9.0, so none of it can be built today. What carries over is the composition: the palette, the
 * two-population particle system, and the slow upward drift. The drawing is GL ES 2.0.
 *
 * Both populations are drawn additively, which is what makes overlapping beams brighten rather
 * than occlude - the effect the original is named for.
 */
class PhaseBeamRenderer : WallpaperRenderer {

    private var backgroundProgram: GLProgram? = null
    private var beamProgram: GLProgram? = null
    private var dotProgram: GLProgram? = null

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f
    private var lastTime = 0f

    // Particle state as parallel primitive arrays rather than objects: this is stepped 30 times a
    // second forever, and a per-particle object would be 64 allocations a frame for no benefit.
    private val beamX = FloatArray(BEAM_COUNT)
    private val beamY = FloatArray(BEAM_COUNT)
    private val beamVelocityX = FloatArray(BEAM_COUNT)
    private val beamVelocityY = FloatArray(BEAM_COUNT)
    private val beamLength = FloatArray(BEAM_COUNT)
    private val beamWidth = FloatArray(BEAM_COUNT)
    private val beamAlpha = FloatArray(BEAM_COUNT)

    private val dotX = FloatArray(DOT_COUNT)
    private val dotY = FloatArray(DOT_COUNT)
    private val dotVelocityY = FloatArray(DOT_COUNT)
    private val dotSize = FloatArray(DOT_COUNT)
    private val dotAlpha = FloatArray(DOT_COUNT)

    private val beamVertices = FloatArray(BEAM_COUNT * VERTICES_PER_BEAM * FLOATS_PER_BEAM_VERTEX)
    private val beamBuffer = floatBuffer(beamVertices.size)

    private val dotVertices = FloatArray(DOT_COUNT * FLOATS_PER_DOT_VERTEX)
    private val dotBuffer = floatBuffer(dotVertices.size)

    private val quadBuffer = floatBuffer(8).apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    // Seeded so a preview thumbnail and the wallpaper it previews are the same scene.
    private val random = Random(PARTICLE_SEED)

    override fun onSurfaceCreated() {
        backgroundProgram = GLProgram(FULLSCREEN_VERTEX_SHADER, GRADIENT_FRAGMENT_SHADER)
        beamProgram = GLProgram(BEAM_VERTEX_SHADER, BEAM_FRAGMENT_SHADER)
        dotProgram = GLProgram(DOT_VERTEX_SHADER, DOT_FRAGMENT_SHADER)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()
        seedParticles()
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    /**
     * Every size and speed here is a fraction of the surface, never a pixel count.
     *
     * That matters twice over. It keeps the wallpaper looking the same on a 720p phone and a 1440p
     * one, and it means the still frame the picker renders into a small tile is an honest preview
     * of the full-screen result rather than the same scene with everything four times too thin.
     */
    private fun seedParticles() {
        for (i in 0 until BEAM_COUNT) {
            beamX[i] = random.nextFloat() * width
            beamY[i] = random.nextFloat() * height
            // Mostly upward, with a slight lean, as in the original.
            beamVelocityX[i] = (random.nextFloat() - 0.5f) * width * 0.02f
            beamVelocityY[i] = -height * (0.025f + random.nextFloat() * 0.055f)
            beamLength[i] = height * (0.06f + random.nextFloat() * 0.16f)
            beamWidth[i] = width * (0.013f + random.nextFloat() * 0.035f)
            beamAlpha[i] = 0.16f + random.nextFloat() * 0.30f
        }
        for (i in 0 until DOT_COUNT) {
            dotX[i] = random.nextFloat() * width
            dotY[i] = random.nextFloat() * height
            dotVelocityY[i] = -height * (0.008f + random.nextFloat() * 0.022f)
            dotSize[i] = width * (0.005f + random.nextFloat() * 0.011f)
            dotAlpha[i] = 0.35f + random.nextFloat() * 0.5f
        }
    }

    override fun onDrawFrame(timeSeconds: Float) {
        // Clamped so a wallpaper resumed after hours does not teleport every particle at once.
        val delta = (timeSeconds - lastTime).coerceIn(0f, MAX_STEP_SECONDS)
        lastTime = timeSeconds

        step(delta)
        drawBackground()
        drawBeams()
        drawDots()
    }

    private fun step(delta: Float) {
        for (i in 0 until BEAM_COUNT) {
            beamX[i] += beamVelocityX[i] * delta
            beamY[i] += beamVelocityY[i] * delta
            if (beamY[i] + beamLength[i] < 0f) {
                beamY[i] = height + beamLength[i]
                beamX[i] = random.nextFloat() * width
            }
            if (beamX[i] < -beamWidth[i]) beamX[i] = width + beamWidth[i]
            if (beamX[i] > width + beamWidth[i]) beamX[i] = -beamWidth[i]
        }
        for (i in 0 until DOT_COUNT) {
            dotY[i] += dotVelocityY[i] * delta
            if (dotY[i] < -dotSize[i]) {
                dotY[i] = height + dotSize[i]
                dotX[i] = random.nextFloat() * width
            }
        }
    }

    /** Parallax: the field slides against the pages, less than one page wide so it stays subtle. */
    private fun parallaxPixels(): Float = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION

    private fun drawBackground() {
        val program = backgroundProgram ?: return
        program.use()

        val position = program.attrib("aPosition")
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
        GLES20.glEnableVertexAttribArray(position)

        GLES20.glUniform4f(program.uniform("uTopColor"), 0.031f, 0.075f, 0.153f, 1f)
        GLES20.glUniform4f(program.uniform("uBottomColor"), 0.075f, 0.216f, 0.345f, 1f)

        // The gradient is the only opaque thing drawn; everything after it is additive.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glDisableVertexAttribArray(position)
    }

    private fun drawBeams() {
        val program = beamProgram ?: return
        val shift = parallaxPixels()

        var v = 0
        for (i in 0 until BEAM_COUNT) {
            val centreX = beamX[i] + shift
            val halfWidth = beamWidth[i]
            val top = beamY[i]
            val bottom = beamY[i] + beamLength[i]
            val alpha = beamAlpha[i]

            // Two triangles, corner UVs spanning -1..1 so the fragment shader can shape a capsule.
            v = putBeamVertex(v, centreX - halfWidth, top, -1f, -1f, alpha)
            v = putBeamVertex(v, centreX + halfWidth, top, 1f, -1f, alpha)
            v = putBeamVertex(v, centreX - halfWidth, bottom, -1f, 1f, alpha)

            v = putBeamVertex(v, centreX + halfWidth, top, 1f, -1f, alpha)
            v = putBeamVertex(v, centreX + halfWidth, bottom, 1f, 1f, alpha)
            v = putBeamVertex(v, centreX - halfWidth, bottom, -1f, 1f, alpha)
        }

        beamBuffer.position(0)
        beamBuffer.put(beamVertices)
        beamBuffer.position(0)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)
        GLES20.glUniform3f(program.uniform("uColor"), 0.35f, 0.65f, 1.0f)

        val position = program.attrib("aPosition")
        val texture = program.attrib("aShape")
        val alpha = program.attrib("aAlpha")
        val stride = FLOATS_PER_BEAM_VERTEX * Float.SIZE_BYTES

        beamBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, beamBuffer)
        GLES20.glEnableVertexAttribArray(position)

        beamBuffer.position(2)
        GLES20.glVertexAttribPointer(texture, 2, GLES20.GL_FLOAT, false, stride, beamBuffer)
        GLES20.glEnableVertexAttribArray(texture)

        beamBuffer.position(4)
        GLES20.glVertexAttribPointer(alpha, 1, GLES20.GL_FLOAT, false, stride, beamBuffer)
        GLES20.glEnableVertexAttribArray(alpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, BEAM_COUNT * VERTICES_PER_BEAM)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texture)
        GLES20.glDisableVertexAttribArray(alpha)
    }

    private fun putBeamVertex(index: Int, x: Float, y: Float, u: Float, v: Float, alpha: Float): Int {
        beamVertices[index] = x
        beamVertices[index + 1] = y
        beamVertices[index + 2] = u
        beamVertices[index + 3] = v
        beamVertices[index + 4] = alpha
        return index + FLOATS_PER_BEAM_VERTEX
    }

    private fun drawDots() {
        val program = dotProgram ?: return
        val shift = parallaxPixels()

        var v = 0
        for (i in 0 until DOT_COUNT) {
            dotVertices[v] = dotX[i] + shift
            dotVertices[v + 1] = dotY[i]
            dotVertices[v + 2] = dotSize[i]
            dotVertices[v + 3] = dotAlpha[i]
            v += FLOATS_PER_DOT_VERTEX
        }

        dotBuffer.position(0)
        dotBuffer.put(dotVertices)
        dotBuffer.position(0)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)
        GLES20.glUniform3f(program.uniform("uColor"), 0.72f, 0.88f, 1.0f)

        val position = program.attrib("aPosition")
        val size = program.attrib("aSize")
        val alpha = program.attrib("aAlpha")
        val stride = FLOATS_PER_DOT_VERTEX * Float.SIZE_BYTES

        dotBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, dotBuffer)
        GLES20.glEnableVertexAttribArray(position)

        dotBuffer.position(2)
        GLES20.glVertexAttribPointer(size, 1, GLES20.GL_FLOAT, false, stride, dotBuffer)
        GLES20.glEnableVertexAttribArray(size)

        dotBuffer.position(3)
        GLES20.glVertexAttribPointer(alpha, 1, GLES20.GL_FLOAT, false, stride, dotBuffer)
        GLES20.glEnableVertexAttribArray(alpha)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DOT_COUNT)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(size)
        GLES20.glDisableVertexAttribArray(alpha)
    }

    override fun onSurfaceDestroyed() {
        backgroundProgram?.release()
        beamProgram?.release()
        dotProgram?.release()
        backgroundProgram = null
        beamProgram = null
        dotProgram = null
    }

    override val previewTimeSeconds: Float get() = 4f

    private companion object {
        const val BEAM_COUNT = 26
        const val DOT_COUNT = 44

        const val VERTICES_PER_BEAM = 6
        const val FLOATS_PER_BEAM_VERTEX = 5
        const val FLOATS_PER_DOT_VERTEX = 4

        /** Parallax travel, as a fraction of the surface width. */
        const val PARALLAX_TRAVEL_FRACTION = 0.2f
        const val MAX_STEP_SECONDS = 0.1f

        /** Fixed so the thumbnail in the picker is the same scene the wallpaper draws. */
        const val PARTICLE_SEED = 0x0BEA3

        const val FULLSCREEN_VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUnit;
            void main() {
                vUnit = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val GRADIENT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uTopColor;
            uniform vec4 uBottomColor;
            varying vec2 vUnit;
            void main() {
                gl_FragColor = mix(uBottomColor, uTopColor, vUnit.y);
            }
        """

        /** Pixel coordinates in, clip space out, with y measured downward as the app thinks of it. */
        const val BEAM_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aShape;
            attribute float aAlpha;
            uniform vec2 uResolution;
            varying vec2 vShape;
            varying float vAlpha;
            void main() {
                vShape = aShape;
                vAlpha = aAlpha;
                vec2 clip = aPosition / uResolution * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
            }
        """

        /**
         * A soft capsule: falls off to nothing at the edges in both axes, so a beam has no seam
         * against the gradient and overlapping beams sum into a brighter core.
         */
        const val BEAM_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            varying vec2 vShape;
            varying float vAlpha;
            void main() {
                float across = 1.0 - vShape.x * vShape.x;
                float along = 1.0 - vShape.y * vShape.y;
                float intensity = across * across * along;
                gl_FragColor = vec4(uColor * intensity * vAlpha, intensity * vAlpha);
            }
        """

        const val DOT_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute float aSize;
            attribute float aAlpha;
            uniform vec2 uResolution;
            varying float vAlpha;
            void main() {
                vAlpha = aAlpha;
                gl_PointSize = aSize;
                vec2 clip = aPosition / uResolution * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
            }
        """

        const val DOT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            varying float vAlpha;
            void main() {
                float distance = length(gl_PointCoord - vec2(0.5));
                float intensity = max(0.0, 1.0 - distance * 2.0);
                intensity = intensity * intensity;
                gl_FragColor = vec4(uColor * intensity * vAlpha, intensity * vAlpha);
            }
        """
    }
}
