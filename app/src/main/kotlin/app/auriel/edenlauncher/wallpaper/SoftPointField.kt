package app.auriel.edenlauncher.wallpaper

import android.opengl.GLES20

/**
 * A batch of soft round points, each with its own size and colour, drawn in one call.
 *
 * Shared by the particle wallpapers. Points are procedural - the fragment shader shapes a radial
 * falloff from `gl_PointCoord` - rather than sampling a sprite texture. That keeps a glow bitmap
 * out of the APK and out of memory, and on these GPUs the arithmetic is cheaper than the texture
 * fetch it replaces.
 *
 * @param capacity the most points that will ever be drawn. Buffers are sized once here; nothing
 *   allocates per frame.
 */
class SoftPointField(private val capacity: Int) {

    private val vertices = FloatArray(capacity * FLOATS_PER_VERTEX)
    private val buffer = floatBuffer(vertices.size)
    private var program: GLProgram? = null
    private var count = 0

    /**
     * Largest point sprite this driver will actually draw.
     *
     * Asking for more is not an error - the driver silently clamps, so a sprite sized past the
     * limit comes out wrong on one phone and right on another, with nothing in the log to say so.
     * Queried once and clamped against, so at least the behaviour is the same everywhere. Anything
     * genuinely large belongs in [SoftQuadField], which has no such ceiling.
     */
    private var maxPointSize = DEFAULT_MAX_POINT_SIZE

    fun onSurfaceCreated() {
        program = GLProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        if (range[1] > 1f) maxPointSize = range[1]
    }

    /** Starts a new batch. Call, then [add] up to `capacity` points, then [draw]. */
    fun begin() {
        count = 0
    }

    fun add(x: Float, y: Float, size: Float, red: Float, green: Float, blue: Float, alpha: Float) {
        if (count >= capacity) return
        var i = count * FLOATS_PER_VERTEX
        vertices[i] = x
        vertices[i + 1] = y
        vertices[i + 2] = size.coerceAtMost(maxPointSize)
        vertices[i + 3] = red
        vertices[i + 4] = green
        vertices[i + 5] = blue
        vertices[i + 6] = alpha
        count++
    }

    /** @param sharpness 1 is a soft blob, higher is a tighter core with a wider halo. */
    fun draw(width: Float, height: Float, sharpness: Float = 2f) {
        val program = program ?: return
        if (count == 0) return

        buffer.position(0)
        buffer.put(vertices, 0, count * FLOATS_PER_VERTEX)
        buffer.position(0)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)
        GLES20.glUniform1f(program.uniform("uSharpness"), sharpness)

        val position = program.attrib("aPosition")
        val size = program.attrib("aSize")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(position)

        buffer.position(2)
        GLES20.glVertexAttribPointer(size, 1, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(size)

        buffer.position(3)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(size)
        GLES20.glDisableVertexAttribArray(color)
    }

    fun release() {
        program?.release()
        program = null
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 7

        /** The floor the GL ES 2.0 spec guarantees, used until the real range is known. */
        const val DEFAULT_MAX_POINT_SIZE = 63f

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute float aSize;
            attribute vec4 aColor;
            uniform vec2 uResolution;
            varying vec4 vColor;
            void main() {
                vColor = aColor;
                gl_PointSize = aSize;
                vec2 clip = aPosition / uResolution * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uSharpness;
            varying vec4 vColor;
            void main() {
                float distance = length(gl_PointCoord - vec2(0.5)) * 2.0;
                float intensity = max(0.0, 1.0 - distance);
                intensity = pow(intensity, uSharpness);
                gl_FragColor = vec4(vColor.rgb * intensity * vColor.a, intensity * vColor.a);
            }
        """
    }
}
