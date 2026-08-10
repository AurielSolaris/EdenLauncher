package app.auriel.edenlauncher.wallpaper

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.SurfaceHolder

/**
 * The EGL plumbing shared by the live wallpaper engine and the still-frame preview.
 *
 * Written against EGL14 directly rather than reusing `GLSurfaceView`: that class insists on being
 * a `View`, and a `WallpaperService.Engine` has a [SurfaceHolder] but no view hierarchy to put it
 * in. The usual workaround is a copy of GLSurfaceView with the view parts cut out, which is
 * roughly this file, minus the parts we never use.
 *
 * The same object serves both surface kinds. A window surface draws the live wallpaper; a pbuffer
 * draws one frame into an offscreen buffer the picker reads back as a bitmap.
 */
class EglContextHolder {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    val isReady: Boolean get() = surface != EGL14.EGL_NO_SURFACE

    /** Brings up a display and context. Returns false if the device cannot give us GL ES 2.0. */
    fun createContext(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return fail("no display")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fail("eglInitialize")

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Both surface kinds come from one config so the context is interchangeable.
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] <= 0) {
            return fail("eglChooseConfig")
        }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        return true
    }

    fun createWindowSurface(holder: SurfaceHolder): Boolean = createWindowSurface(holder as Any)

    /**
     * @param window a [SurfaceHolder] or a [android.view.Surface]. EGL accepts either; the video
     *   transcoder passes the encoder's input surface here.
     */
    fun createWindowSurface(window: Any): Boolean {
        destroySurface()
        surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            window,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        return makeCurrent()
    }

    /**
     * Stamps the next [swapBuffers] with a presentation time, in nanoseconds.
     *
     * Only meaningful when the window surface is a `MediaCodec` input surface: this is what the
     * encoder reads to timestamp the frame, and getting it wrong produces a file that plays at the
     * wrong speed rather than one that fails outright.
     */
    fun setPresentationTime(nanoseconds: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds)
    }

    fun createPbufferSurface(width: Int, height: Int): Boolean {
        destroySurface()
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreatePbufferSurface")
        return makeCurrent()
    }

    fun makeCurrent(): Boolean {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return fail("eglMakeCurrent")
        return true
    }

    /** @return false when the surface has been lost, which the caller treats as "stop drawing". */
    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun destroySurface() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    fun release() {
        destroySurface()
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context)
            context = EGL14.EGL_NO_CONTEXT
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        config = null
    }

    private fun fail(stage: String): Boolean {
        Log.w(TAG, "EGL setup failed at $stage: 0x" + Integer.toHexString(EGL14.eglGetError()))
        return false
    }

    private companion object {
        const val TAG = "EglContextHolder"
    }
}
