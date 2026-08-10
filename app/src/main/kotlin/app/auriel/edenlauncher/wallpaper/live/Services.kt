package app.auriel.edenlauncher.wallpaper.live

import app.auriel.edenlauncher.wallpaper.GLWallpaperService
import app.auriel.edenlauncher.wallpaper.WallpaperRenderer

/**
 * The wallpaper services.
 *
 * Each is a name in the manifest and nothing else - all the behaviour is in the renderer, which is
 * also what the picker rasterises for its still preview. One class per file would be four files of
 * three lines.
 */

class PhaseBeamWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = PhaseBeamRenderer()
}

class NoiseFieldWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = NoiseFieldRenderer()
}

class Galaxy4WallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = Galaxy4Renderer()
}

class GrassWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = GrassRenderer()
}

class HoloSpiralWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = HoloSpiralRenderer()
}

class MagicSmokeWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = MagicSmokeRenderer()
}

class NexusWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = NexusRenderer()
}

class FallWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = FallRenderer()
}

class PolarClockWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = PolarClockRenderer()
}

/** Needs a context: it reads the audio-source preference and may hold a capture session. */
class MusicVisualizationWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = MusicVisualizationRenderer(this)
}

/** Needs a context for the accelerometer. */
class WalkaroundWallpaperService : GLWallpaperService() {
    override fun createRenderer(): WallpaperRenderer = WalkaroundRenderer(this)
}
