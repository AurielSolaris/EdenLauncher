package app.auriel.edenlauncher.wallpaper

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import app.auriel.edenlauncher.settings.LauncherPrefs

/**
 * Base for every wallpaper Eden bundles: runs a [WallpaperRenderer] on its own GL thread.
 *
 * The rules this enforces on all of them, because a live wallpaper that gets them wrong is the
 * reason people turn live wallpapers off:
 *
 * - **Nothing runs while hidden.** The loop stops on `onVisibilityChanged(false)`, so a wallpaper
 *   behind an open app costs zero. This is the single biggest thing separating a wallpaper you
 *   keep from one you uninstall on a 4 GB phone.
 * - **30 fps, not vsync.** These are slow drifting scenes; 60 fps buys nothing visible and costs
 *   double the GPU. The frame period is fixed and the loop sleeps out the remainder.
 * - **One thread, no allocation in the loop.** The draw runnable is a field, not a lambda per
 *   frame.
 */
abstract class GLWallpaperService : WallpaperService() {

    /** Builds the renderer for a new engine. Called once per engine, off the GL thread. */
    protected abstract fun createRenderer(): WallpaperRenderer

    override fun onCreateEngine(): Engine = GLEngine()

    private inner class GLEngine : Engine() {

        private val egl = EglContextHolder()
        private val renderer: WallpaperRenderer = createRenderer()
        private val prefs = LauncherPrefs(this@GLWallpaperService)

        private var thread: HandlerThread? = null
        private var handler: Handler? = null

        private var running = false

        /**
         * Scene time, which is not wall time: it accumulates at the user's chosen speed.
         *
         * Accumulated rather than computed as `elapsed * speed` so that changing the speed does
         * not make the scene jump. At 2x, an hour in, the latter would leap an hour forward.
         */
        private var sceneSeconds = 0f
        private var lastTickMs = 0L
        private var speedScale = 1f

        private var pendingWidth = 0
        private var pendingHeight = 0
        private var sizeChanged = false

        /** Reused rather than allocated per frame. */
        private val drawFrame = Runnable { drawAndReschedule() }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // Ask for offset notifications so parallax works; harmless for renderers that ignore
            // them.
            setOffsetNotificationsEnabled(true)

            val thread = HandlerThread("EdenWallpaper", android.os.Process.THREAD_PRIORITY_DISPLAY)
            thread.start()
            this.thread = thread
            handler = Handler(thread.looper)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            handler?.post {
                if (!egl.createContext()) return@post
                if (!egl.createWindowSurface(holder)) return@post
                renderer.onSurfaceCreated()
                lastTickMs = SystemClock.uptimeMillis()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            pendingWidth = width
            pendingHeight = height
            sizeChanged = true
            handler?.post { applySizeAndDrawOnce() }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xStep: Float,
            yStep: Float,
            xPixels: Int,
            yPixels: Int,
        ) {
            handler?.post { renderer.onOffsetsChanged(xOffset) }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) start() else stop()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stop()
            handler?.post {
                renderer.onSurfaceDestroyed()
                egl.destroySurface()
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stop()
            handler?.post {
                renderer.onSurfaceDestroyed()
                egl.release()
            }
            // quitSafely lets the teardown above run before the looper stops.
            thread?.quitSafely()
            thread = null
            handler = null
            super.onDestroy()
        }

        // ---- the loop ------------------------------------------------------------------------

        /**
         * Set by the launcher when the app drawer covers the screen.
         *
         * The system does not consider the wallpaper hidden here - the launcher window still
         * declares that it shows the wallpaper - so without this the wallpaper would keep drawing
         * thirty frames a second behind an opaque drawer. The launcher only sends it when the
         * drawer is opaque enough that nothing would be visible anyway.
         */
        private var pausedByLauncher = false

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: android.os.Bundle?,
            resultRequested: Boolean,
        ): android.os.Bundle? {
            when (action) {
                COMMAND_PAUSE -> {
                    pausedByLauncher = true
                    stop()
                }

                COMMAND_RESUME -> {
                    pausedByLauncher = false
                    if (isVisible) start()
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun start() {
            if (running || pausedByLauncher) return
            running = true
            handler?.post { renderer.onResumed() }
            // Re-read on every wake rather than caching: changing the speed in settings then
            // returning to the home screen takes effect, with no service restart.
            speedScale = prefs.liveWallpaperSpeed
            // The clock ran on while we were hidden; do not credit that to the scene.
            lastTickMs = SystemClock.uptimeMillis()
            handler?.post(drawFrame)
        }

        private fun stop() {
            if (!running) return
            running = false
            handler?.removeCallbacks(drawFrame)
            handler?.post { renderer.onPaused() }
        }

        private fun applySizeAndDrawOnce() {
            if (!egl.isReady || !sizeChanged) return
            sizeChanged = false
            GLES20.glViewport(0, 0, pendingWidth, pendingHeight)
            renderer.onSurfaceChanged(pendingWidth, pendingHeight)
            // Draw immediately so a wallpaper being previewed or rotated is never a blank frame
            // waiting on the next tick.
            drawOnce()
        }

        private fun drawAndReschedule() {
            if (!running) return
            val frameStart = SystemClock.uptimeMillis()
            drawOnce()

            // Schedule against the frame's start rather than its end, so a slow frame does not
            // compound into a slower frame rate than asked for.
            val next = frameStart + FRAME_PERIOD_MS
            handler?.postAtTime(drawFrame, maxOf(next, SystemClock.uptimeMillis()))
        }

        private fun drawOnce() {
            if (!egl.isReady) return
            if (sizeChanged) {
                applySizeAndDrawOnce()
                return
            }
            val now = SystemClock.uptimeMillis()
            // Clamped: a frame delayed by a stall must not teleport the scene forward.
            val step = ((now - lastTickMs) / 1000f).coerceIn(0f, MAX_STEP_SECONDS)
            lastTickMs = now
            sceneSeconds += step * speedScale

            renderer.onDrawFrame(sceneSeconds)
            if (!egl.swapBuffers()) {
                // The surface went away underneath us; stop rather than spin on a dead surface.
                stop()
            }
        }
    }

    companion object {
        /**
         * Commands the launcher sends through
         * [android.app.WallpaperManager.sendWallpaperCommand] to stop and restart drawing while
         * something of its own covers the wallpaper.
         */
        const val COMMAND_PAUSE = "app.auriel.edenlauncher.WALLPAPER_PAUSE"
        const val COMMAND_RESUME = "app.auriel.edenlauncher.WALLPAPER_RESUME"

        /** 30 fps. See the class comment: these scenes do not benefit from more. */
        private const val FRAME_PERIOD_MS = 33L

        /** Longest step credited to the scene in one frame, so a stall cannot fast-forward it. */
        private const val MAX_STEP_SECONDS = 0.2f
    }
}
