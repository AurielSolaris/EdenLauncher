package app.auriel.edenlauncher.wallpaper

/**
 * One live wallpaper's drawing, with no knowledge of who is driving it.
 *
 * The same renderer is used three ways: by the wallpaper service on a real window, by the picker
 * to rasterise a single still frame offscreen, and by nothing else. Keeping it free of any
 * `WallpaperService.Engine` reference is what makes the still-frame preview possible at all.
 */
interface WallpaperRenderer {

    /** Compile shaders and allocate buffers. Called with a current GL context. */
    fun onSurfaceCreated()

    /** The drawable area changed. Called at least once before the first [onDrawFrame]. */
    fun onSurfaceChanged(width: Int, height: Int)

    /**
     * Advance and draw one frame.
     *
     * @param timeSeconds seconds since the renderer started. Passed in rather than read from the
     *   clock so the preview can ask for a specific, representative moment instead of whatever
     *   the wallpaper happens to look like at t=0, which for most of these is an empty screen.
     */
    fun onDrawFrame(timeSeconds: Float)

    /**
     * Horizontal scroll position of the home screen, 0 to 1, for parallax.
     *
     * Default is a no-op: a wallpaper that does not move with the pages just ignores it.
     */
    fun onOffsetsChanged(xOffset: Float) = Unit

    /** Release GL resources. The context may already be gone, so this must not assume otherwise. */
    fun onSurfaceDestroyed() = Unit

    /**
     * Drawing has stopped: nothing is looking at this wallpaper.
     *
     * The GL context is still current, so this is the place to let go of anything held that is not
     * GL - an audio capture session, a sensor registration. Most renderers have nothing to do
     * here, because the engine has already stopped calling [onDrawFrame], which is the entire cost
     * for a renderer that only draws.
     */
    fun onPaused() = Unit

    /** Drawing is about to resume. Reacquire whatever [onPaused] let go of. */
    fun onResumed() = Unit

    /**
     * A touch landed on the home screen, in surface pixels.
     *
     * The wallpaper sees touches that the launcher does not consume - taps on empty space, mostly.
     * Reacting to them is what made the Nexus wallpaper feel like a surface rather than a picture,
     * and it is the detail people remember about it. Most renderers ignore this.
     */
    fun onTouch(x: Float, y: Float) = Unit

    /**
     * A moment that shows this wallpaper at its most representative, in seconds.
     *
     * Most of these start from a blank or uniform state and only look like themselves once the
     * simulation has run for a while, so a preview rendered at t=0 would be a black rectangle.
     */
    val previewTimeSeconds: Float get() = 6f
}
