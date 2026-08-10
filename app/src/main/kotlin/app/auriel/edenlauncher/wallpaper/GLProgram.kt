package app.auriel.edenlauncher.wallpaper

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A compiled and linked GL ES 2.0 program, with the attribute and uniform lookups cached.
 *
 * `glGetUniformLocation` is a string lookup into the driver; doing it per frame is the classic way
 * to make a wallpaper cost more CPU than the thing it is drawing. Locations are resolved once and
 * kept.
 */
class GLProgram(vertexSource: String, fragmentSource: String) {

    val handle: Int = link(vertexSource, fragmentSource)

    private val locations = HashMap<String, Int>(8)

    fun use() = GLES20.glUseProgram(handle)

    fun attrib(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetAttribLocation(handle, name)
    }

    fun uniform(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetUniformLocation(handle, name)
    }

    fun release() {
        if (handle != 0) GLES20.glDeleteProgram(handle)
    }

    private companion object {
        const val TAG = "GLProgram"

        fun link(vertexSource: String, fragmentSource: String): Int {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            if (vertex == 0 || fragment == 0) return 0

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)

            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.w(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }

            // The program holds its own reference once linked.
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return program
        }

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.w(TAG, "Compile failed: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}

/** A direct float buffer sized for [capacity] floats, in the native byte order GL expects. */
fun floatBuffer(capacity: Int): FloatBuffer =
    ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
