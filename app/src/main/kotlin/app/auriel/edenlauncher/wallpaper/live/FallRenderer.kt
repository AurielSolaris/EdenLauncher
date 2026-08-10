package app.auriel.edenlauncher.wallpaper.live

import android.opengl.GLES20
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.GradientBackground
import app.auriel.edenlauncher.wallpaper.SoftPointField
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer
import app.auriel.edenlauncher.wallpaper.floatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fall: leaves drifting down onto water, and the ripples where they land.
 *
 * A rewrite of the `fall` wallpaper from AOSP's `packages/wallpapers/Basic` (Apache 2.0).
 *
 * The ripples are the point of it. In the original a leaf touching the water started an expanding
 * ring, and that cause-and-effect is what made an otherwise ordinary particle wallpaper feel like
 * a place. So a ripple here is spawned by a leaf reaching the waterline, not on a timer - the
 * rings you see are the leaves you watched fall.
 */
class FallRenderer : WallpaperRenderer {

    private val sky = GradientBackground()
    private val ripplePoints = SoftPointField(RIPPLE_COUNT * RIPPLE_SEGMENTS)
    private var leafProgram: GLProgram? = null

    private var width = 1f
    private var height = 1f
    private var xOffset = 0.5f
    private var lastTime = 0f

    private val leafX = FloatArray(LEAF_COUNT)
    private val leafY = FloatArray(LEAF_COUNT)
    private val leafDriftPhase = FloatArray(LEAF_COUNT)
    private val leafDriftRate = FloatArray(LEAF_COUNT)
    private val leafFallSpeed = FloatArray(LEAF_COUNT)
    private val leafSize = FloatArray(LEAF_COUNT)
    private val leafSpin = FloatArray(LEAF_COUNT)
    private val leafSpinRate = FloatArray(LEAF_COUNT)
    private val leafTint = FloatArray(LEAF_COUNT)

    /** Ripples: age counts up from 0; a negative age means the slot is free. */
    private val rippleX = FloatArray(RIPPLE_COUNT)
    private val rippleY = FloatArray(RIPPLE_COUNT)
    private val rippleAge = FloatArray(RIPPLE_COUNT) { -1f }
    private var nextRipple = 0

    private val leafVertices = FloatArray(LEAF_COUNT * VERTICES_PER_LEAF * FLOATS_PER_VERTEX)
    private val leafBuffer = floatBuffer(leafVertices.size)

    /** Scratch for one leaf's four rotated corners, as x,y pairs. */
    private val corner = FloatArray(8)

    private val random = Random(PARTICLE_SEED)

    /** Where the water starts, as a fraction of the height. */
    private val waterLine get() = height * WATER_LINE_FRACTION

    override fun onSurfaceCreated() {
        sky.onSurfaceCreated()
        ripplePoints.onSurfaceCreated()
        leafProgram = GLProgram(LEAF_VERTEX_SHADER, LEAF_FRAGMENT_SHADER)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        this.width = width.toFloat()
        this.height = height.toFloat()

        for (i in 0 until LEAF_COUNT) {
            leafX[i] = random.nextFloat() * this.width
            leafY[i] = random.nextFloat() * waterLine
            leafDriftPhase[i] = random.nextFloat() * TWO_PI
            leafDriftRate[i] = 0.4f + random.nextFloat() * 1.1f
            leafFallSpeed[i] = this.height * (0.02f + random.nextFloat() * 0.045f)
            leafSize[i] = this.width * (0.018f + random.nextFloat() * 0.028f)
            leafSpin[i] = random.nextFloat() * TWO_PI
            leafSpinRate[i] = (random.nextFloat() - 0.5f) * 2.2f
            leafTint[i] = random.nextFloat()
        }
    }

    override fun onOffsetsChanged(xOffset: Float) {
        this.xOffset = xOffset
    }

    /** Touching the water rings it, the same as a leaf landing. It is water; it should answer. */
    override fun onTouch(x: Float, y: Float) {
        if (y < waterLine) return
        spawnRipple(x, y)
    }

    override fun onDrawFrame(timeSeconds: Float) {
        val delta = (timeSeconds - lastTime).coerceIn(0f, MAX_STEP_SECONDS)
        lastTime = timeSeconds

        drawBackground()
        stepLeaves(delta, timeSeconds)
        drawRipples(delta)
        drawLeaves()
    }

    private fun drawBackground() {
        // Autumn sky above, and the water below picks it up darker and greener.
        sky.draw(0.235f, 0.302f, 0.376f, 0.075f, 0.129f, 0.118f)
    }

    private fun stepLeaves(delta: Float, time: Float) {
        for (i in 0 until LEAF_COUNT) {
            // Leaves do not fall straight: they scull side to side as they go.
            leafX[i] += sin(time * leafDriftRate[i] + leafDriftPhase[i]) * width * DRIFT_FRACTION * delta
            leafY[i] += leafFallSpeed[i] * delta
            leafSpin[i] += leafSpinRate[i] * delta

            if (leafY[i] >= waterLine) {
                spawnRipple(leafX[i], waterLine)
                leafY[i] = -leafSize[i]
                leafX[i] = random.nextFloat() * width
                leafFallSpeed[i] = height * (0.02f + random.nextFloat() * 0.045f)
            }
            if (leafX[i] < -leafSize[i]) leafX[i] = width + leafSize[i]
            if (leafX[i] > width + leafSize[i]) leafX[i] = -leafSize[i]
        }
    }

    private fun spawnRipple(x: Float, y: Float) {
        // A ring buffer rather than a search for a free slot: at this count the oldest ripple is
        // always the one worth replacing, and it never allocates or scans.
        rippleX[nextRipple] = x
        rippleY[nextRipple] = y
        rippleAge[nextRipple] = 0f
        nextRipple = (nextRipple + 1) % RIPPLE_COUNT
    }

    /** Each ripple is a ring of soft points, expanding and fading. */
    private fun drawRipples(delta: Float) {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        ripplePoints.begin()
        for (i in 0 until RIPPLE_COUNT) {
            if (rippleAge[i] < 0f) continue
            rippleAge[i] += delta
            if (rippleAge[i] > RIPPLE_LIFETIME) {
                rippleAge[i] = -1f
                continue
            }

            val t = rippleAge[i] / RIPPLE_LIFETIME
            val radius = width * RIPPLE_MAX_FRACTION * t
            val alpha = (1f - t) * (1f - t) * 0.5f

            for (s in 0 until RIPPLE_SEGMENTS) {
                val angle = s * TWO_PI / RIPPLE_SEGMENTS
                ripplePoints.add(
                    rippleX[i] + cos(angle) * radius,
                    // Flattened hard: this is a circle on a surface seen at a shallow angle.
                    rippleY[i] + sin(angle) * radius * RIPPLE_FLATTEN,
                    width * 0.012f,
                    0.65f,
                    0.80f,
                    0.85f,
                    alpha,
                )
            }
        }
        ripplePoints.draw(width, height, sharpness = 1.5f)
    }

    private fun drawLeaves() {
        val program = leafProgram ?: return
        val shift = (xOffset - 0.5f) * -width * PARALLAX_TRAVEL_FRACTION

        var v = 0
        for (i in 0 until LEAF_COUNT) {
            val half = leafSize[i] * 0.5f
            val angle = leafSpin[i]
            val cosA = cos(angle)
            val sinA = sin(angle)
            val centreX = leafX[i] + shift
            val centreY = leafY[i]

            // Autumn range: gold through orange to rust.
            val t = leafTint[i]
            val red = 0.85f - t * 0.25f
            val green = 0.55f - t * 0.30f
            val blue = 0.15f + t * 0.05f

            // A leaf as a rotated diamond: four corners, two triangles, no texture. The corners
            // are rotated into a scratch array rather than built as objects - this runs
            // LEAF_COUNT times every frame, forever.
            val tip = half * LEAF_ASPECT
            rotateInto(0, 0f, -tip, centreX, centreY, cosA, sinA)
            rotateInto(1, half, 0f, centreX, centreY, cosA, sinA)
            rotateInto(2, 0f, tip, centreX, centreY, cosA, sinA)
            rotateInto(3, -half, 0f, centreX, centreY, cosA, sinA)

            v = putLeafVertex(v, 0, red, green, blue)
            v = putLeafVertex(v, 1, red, green, blue)
            v = putLeafVertex(v, 2, red, green, blue)
            v = putLeafVertex(v, 0, red, green, blue)
            v = putLeafVertex(v, 2, red, green, blue)
            v = putLeafVertex(v, 3, red, green, blue)
        }

        leafBuffer.position(0)
        leafBuffer.put(leafVertices)
        leafBuffer.position(0)

        // Leaves are solid; they cover the water rather than glowing over it.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)

        val position = program.attrib("aPosition")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        leafBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, leafBuffer)
        GLES20.glEnableVertexAttribArray(position)

        leafBuffer.position(2)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, stride, leafBuffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, LEAF_COUNT * VERTICES_PER_LEAF)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    /** Rotates a corner about the leaf's centre and stores it in [corner]. */
    private fun rotateInto(
        slot: Int,
        localX: Float,
        localY: Float,
        centreX: Float,
        centreY: Float,
        cosA: Float,
        sinA: Float,
    ) {
        corner[slot * 2] = centreX + localX * cosA - localY * sinA
        corner[slot * 2 + 1] = centreY + localX * sinA + localY * cosA
    }

    private fun putLeafVertex(index: Int, slot: Int, r: Float, g: Float, b: Float): Int {
        leafVertices[index] = corner[slot * 2]
        leafVertices[index + 1] = corner[slot * 2 + 1]
        leafVertices[index + 2] = r
        leafVertices[index + 3] = g
        leafVertices[index + 4] = b
        return index + FLOATS_PER_VERTEX
    }

    override fun onSurfaceDestroyed() {
        sky.release()
        ripplePoints.release()
        leafProgram?.release()
        leafProgram = null
    }

    /** Long enough for the first leaves to have reached the water and started ripples. */
    override val previewTimeSeconds: Float get() = 5f

    private companion object {
        const val LEAF_COUNT = 34
        const val RIPPLE_COUNT = 12
        const val RIPPLE_SEGMENTS = 18

        const val VERTICES_PER_LEAF = 6
        const val FLOATS_PER_VERTEX = 5

        const val TWO_PI = 6.2831855f
        const val WATER_LINE_FRACTION = 0.72f
        const val DRIFT_FRACTION = 0.05f
        const val LEAF_ASPECT = 1.7f
        const val RIPPLE_LIFETIME = 2.6f
        const val RIPPLE_MAX_FRACTION = 0.16f
        const val RIPPLE_FLATTEN = 0.32f
        const val PARALLAX_TRAVEL_FRACTION = 0.13f
        const val MAX_STEP_SECONDS = 0.1f
        const val PARTICLE_SEED = 0xFA11

        const val LEAF_VERTEX_SHADER = """
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

        const val LEAF_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """
    }
}
