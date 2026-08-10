package app.auriel.edenlauncher.wallpaper

import android.graphics.Bitmap
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Renders a live wallpaper to a still [Bitmap], offscreen, without ever setting it.
 *
 * This exists because the platform's idea of previewing a live wallpaper is to actually start the
 * wallpaper service in a preview activity. That is fine right up to the moment anything else
 * wants the foreground - a notification you tap, a camera shortcut you fat-finger, an incoming
 * call. Coming back drops you out of the preview with nothing selected, and on some OEM skins it
 * drops you out of the picker entirely, so you start over. A wallpaper you are only *looking* at
 * has no business owning the foreground, and losing your place because you glanced at something
 * else is not a trade anybody agreed to.
 *
 * So Eden renders a frame into a pbuffer and shows you a picture. Choosing is a separate,
 * deliberate act, and nothing about looking can lose your place.
 */
object WallpaperStillRenderer {

    /**
     * Frames simulated before the readback.
     *
     * Particle systems integrate rather than evaluate: their state at t=6s is the sum of the steps
     * that got there, so a single call with a large timestamp would render the initial state with
     * a misleading clock. Stepping is the only honest way to reach a representative frame.
     */
    private const val MAX_WARMUP_FRAMES = 150
    private const val FRAME_STEP_SECONDS = 1f / 30f

    /**
     * @param width pixel width of the still. Callers pass the tile size, not the screen size -
     *   there is no reason to rasterise 1080p for a thumbnail.
     * @return the frame, or null if the device would not give us a GL ES 2.0 context.
     */
    fun render(factory: () -> WallpaperRenderer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null

        val egl = EglContextHolder()
        var renderer: WallpaperRenderer? = null
        try {
            if (!egl.createContext()) return null
            if (!egl.createPbufferSurface(width, height)) return null

            renderer = factory()
            renderer.onSurfaceCreated()
            GLES20.glViewport(0, 0, width, height)
            renderer.onSurfaceChanged(width, height)
            // Mid-scroll, so a parallax wallpaper is shown at a neutral offset rather than hard
            // against one edge.
            renderer.onOffsetsChanged(0.5f)

            val frames = (renderer.previewTimeSeconds / FRAME_STEP_SECONDS)
                .toInt()
                .coerceIn(1, MAX_WARMUP_FRAMES)
            for (frame in 1..frames) {
                renderer.onDrawFrame(frame * FRAME_STEP_SECONDS)
            }
            GLES20.glFinish()

            return readPixels(width, height)
        } finally {
            renderer?.onSurfaceDestroyed()
            egl.release()
        }
    }

    /**
     * Pulls the framebuffer into a bitmap.
     *
     * Two conversions are needed and both are easy to get subtly wrong. GL returns rows bottom-up
     * where a bitmap wants them top-down, and `GL_RGBA` bytes read back as little-endian ints are
     * ABGR where a bitmap wants ARGB, so red and blue are swapped.
     */
    private fun readPixels(width: Int, height: Int): Bitmap {
        val buffer: IntBuffer = ByteBuffer
            .allocateDirect(width * height * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val source = IntArray(width * height)
        buffer.get(source)

        val flipped = IntArray(width * height)
        for (y in 0 until height) {
            val sourceRow = y * width
            val targetRow = (height - 1 - y) * width
            for (x in 0 until width) {
                val pixel = source[sourceRow + x]
                val blue = pixel shr 16 and 0xFF
                val red = pixel and 0xFF
                flipped[targetRow + x] =
                    (pixel and 0xFF00FF00.toInt()) or (red shl 16) or blue
            }
        }

        return Bitmap.createBitmap(flipped, width, height, Bitmap.Config.ARGB_8888)
    }
}
