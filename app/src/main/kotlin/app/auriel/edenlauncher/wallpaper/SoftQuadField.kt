package app.auriel.edenlauncher.wallpaper

import android.opengl.GLES20

/**
 * A batch of large soft blobs, drawn as quads.
 *
 * The counterpart to [SoftPointField], and the reason both exist: `gl_PointSize` is capped by the
 * driver. `GL_ALIASED_POINT_SIZE_RANGE` is commonly 63 or 255 pixels, and a point sprite asked to
 * be larger is silently clamped - no error, no warning, just a sprite that is wrong on some phones
 * and right on others. Anything bigger than a small dot has to be a quad.
 *
 * So: [SoftPointField] for particles and stars, this for smoke, haze, and silhouettes. Same
 * radial falloff, same one draw call, no size ceiling.
 */
class SoftQuadField(private val capacity: Int) {

    private val vertices = FloatArray(capacity * VERTICES_PER_QUAD * FLOATS_PER_VERTEX)
    private val buffer = floatBuffer(vertices.size)
    private var program: GLProgram? = null
    private var count = 0

    fun onSurfaceCreated() {
        program = GLProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    }

    fun begin() {
        count = 0
    }

    /** @param size the blob's full width and height in pixels. No upper limit. */
    fun add(
        x: Float,
        y: Float,
        size: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        if (count >= capacity) return
        val half = size * 0.5f
        var i = count * VERTICES_PER_QUAD * FLOATS_PER_VERTEX

        // Two triangles. The shape coordinates run -1..1 across the quad so the fragment shader
        // can measure distance from the centre without needing a texture.
        i = put(i, x - half, y - half, -1f, -1f, red, green, blue, alpha)
        i = put(i, x + half, y - half, 1f, -1f, red, green, blue, alpha)
        i = put(i, x - half, y + half, -1f, 1f, red, green, blue, alpha)

        i = put(i, x + half, y - half, 1f, -1f, red, green, blue, alpha)
        i = put(i, x + half, y + half, 1f, 1f, red, green, blue, alpha)
        put(i, x - half, y + half, -1f, 1f, red, green, blue, alpha)

        count++
    }

    private fun put(
        index: Int,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ): Int {
        vertices[index] = x
        vertices[index + 1] = y
        vertices[index + 2] = u
        vertices[index + 3] = v
        vertices[index + 4] = red
        vertices[index + 5] = green
        vertices[index + 6] = blue
        vertices[index + 7] = alpha
        return index + FLOATS_PER_VERTEX
    }

    /** @param sharpness below 1 is a broad haze; above 1 tightens to a core. */
    fun draw(width: Float, height: Float, sharpness: Float = 1f) {
        val program = program ?: return
        if (count == 0) return

        val floats = count * VERTICES_PER_QUAD * FLOATS_PER_VERTEX
        buffer.position(0)
        buffer.put(vertices, 0, floats)
        buffer.position(0)

        program.use()
        GLES20.glUniform2f(program.uniform("uResolution"), width, height)
        GLES20.glUniform1f(program.uniform("uSharpness"), sharpness)

        val position = program.attrib("aPosition")
        val shape = program.attrib("aShape")
        val color = program.attrib("aColor")
        val stride = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(position)

        buffer.position(2)
        GLES20.glVertexAttribPointer(shape, 2, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(shape)

        buffer.position(4)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(color)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count * VERTICES_PER_QUAD)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(shape)
        GLES20.glDisableVertexAttribArray(color)
    }

    fun release() {
        program?.release()
        program = null
    }

    private companion object {
        const val VERTICES_PER_QUAD = 6
        const val FLOATS_PER_VERTEX = 8

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aShape;
            attribute vec4 aColor;
            uniform vec2 uResolution;
            varying vec2 vShape;
            varying vec4 vColor;
            void main() {
                vShape = aShape;
                vColor = aColor;
                vec2 clip = aPosition / uResolution * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uSharpness;
            varying vec2 vShape;
            varying vec4 vColor;
            void main() {
                float distance = length(vShape);
                float intensity = max(0.0, 1.0 - distance);
                intensity = pow(intensity, uSharpness);
                gl_FragColor = vec4(vColor.rgb * intensity * vColor.a, intensity * vColor.a);
            }
        """
    }
}
