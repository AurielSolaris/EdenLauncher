package app.auriel.edenlauncher.media

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import app.auriel.edenlauncher.wallpaper.GLProgram
import app.auriel.edenlauncher.wallpaper.floatBuffer

/**
 * Receives decoded video frames and redraws them into whatever surface is currently bound.
 *
 * A decoder cannot be pointed straight at an encoder. Its output is an opaque buffer at the
 * source's own resolution, and the only supported way to get from there to an encoder's input is
 * to receive it as an external texture and draw it - which is convenient, because drawing is also
 * where the scale to 1080p happens, for free.
 *
 * The texture is `GL_TEXTURE_EXTERNAL_OES`, not a normal 2D texture, which is why the fragment
 * shader needs the `GL_OES_EGL_image_external` extension and the `samplerExternalOES` type.
 */
class TextureFrameBlitter {

    private var textureId = 0
    private var program: GLProgram? = null

    lateinit var surfaceTexture: SurfaceTexture
        private set

    /** The surface a decoder writes into. */
    lateinit var surface: Surface
        private set

    private val transform = FloatArray(16)

    private val quad = floatBuffer(16).apply {
        // x, y, u, v - a full-screen triangle strip.
        put(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ),
        )
        position(0)
    }

    /** Must be called with a current GL context. */
    fun setUp() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        surfaceTexture = SurfaceTexture(textureId)
        surface = Surface(surfaceTexture)
        program = GLProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    }

    /**
     * Pulls the newest decoded frame into the texture and draws it.
     *
     * The transform matrix from the [SurfaceTexture] is not optional: decoders are free to hand
     * back a frame that is cropped, flipped, or in a different colour layout than requested, and
     * that matrix is the only description of what they actually did.
     */
    fun drawLatestFrame() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)

        val program = program ?: return
        program.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(program.uniform("uTexture"), 0)
        GLES20.glUniformMatrix4fv(program.uniform("uTransform"), 1, false, transform, 0)

        val position = program.attrib("aPosition")
        val texture = program.attrib("aTexCoord")
        val stride = 4 * Float.SIZE_BYTES

        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, stride, quad)
        GLES20.glEnableVertexAttribArray(position)

        quad.position(2)
        GLES20.glVertexAttribPointer(texture, 2, GLES20.GL_FLOAT, false, stride, quad)
        GLES20.glEnableVertexAttribArray(texture)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texture)
    }

    fun release() {
        program?.release()
        program = null
        if (::surface.isInitialized) surface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTransform;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = (uTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
