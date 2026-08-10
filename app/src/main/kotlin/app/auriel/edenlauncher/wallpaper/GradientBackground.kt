package app.auriel.edenlauncher.wallpaper

import android.opengl.GLES20

/**
 * A full-screen vertical gradient, drawn opaquely as the first thing in a frame.
 *
 * Doubles as the frame's clear: it covers every pixel, so there is no `glClear` before it. One
 * fewer full-screen write per frame is worth having on a phone with no memory bandwidth to spare.
 */
class GradientBackground {

    private var program: GLProgram? = null

    private val quad = floatBuffer(8).apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    fun onSurfaceCreated() {
        program = GLProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    }

    /** Colours are linear 0..1 components; [topRed] is the colour at the top of the screen. */
    fun draw(
        topRed: Float, topGreen: Float, topBlue: Float,
        bottomRed: Float, bottomGreen: Float, bottomBlue: Float,
    ) {
        val program = program ?: return
        program.use()

        val position = program.attrib("aPosition")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(position)

        GLES20.glUniform3f(program.uniform("uTop"), topRed, topGreen, topBlue)
        GLES20.glUniform3f(program.uniform("uBottom"), bottomRed, bottomGreen, bottomBlue)

        // Opaque replace: this is the clear, so blending is off for exactly this draw.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glDisableVertexAttribArray(position)
    }

    fun release() {
        program?.release()
        program = null
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying float vHeight;
            void main() {
                vHeight = aPosition.y * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uTop;
            uniform vec3 uBottom;
            varying float vHeight;
            void main() {
                gl_FragColor = vec4(mix(uBottom, uTop, vHeight), 1.0);
            }
        """
    }
}
