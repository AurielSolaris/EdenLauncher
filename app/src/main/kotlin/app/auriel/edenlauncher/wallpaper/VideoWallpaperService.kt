package app.auriel.edenlauncher.wallpaper

import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import app.auriel.edenlauncher.settings.LauncherPrefs
import java.io.File

/**
 * Plays the user's imported video as a wallpaper, on a loop.
 *
 * Uses [MediaPlayer] rather than a codec pipeline: the file has already been normalised by
 * [app.auriel.edenlauncher.media.MediaCodecTranscoder] to something every device can decode in
 * hardware, so there is nothing left for a custom player to fix, and MediaPlayer is the one that
 * already knows how to loop seamlessly.
 *
 * Playback stops the moment the wallpaper is hidden. A video wallpaper that keeps decoding behind
 * a full-screen app is the single worst thing a launcher can do to a battery, and it is a very
 * common bug in the ones you download.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {

        private var player: MediaPlayer? = null
        private val prefs = LauncherPrefs(this@VideoWallpaperService)

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startPlayer(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val player = player ?: return
            if (visible) {
                // Volume is re-read here rather than cached: changing the setting takes effect the
                // next time you look at the home screen, with no service restart.
                applyVolume(player)
                runCatching { player.start() }
            } else {
                runCatching { player.pause() }
            }
        }

        private fun startPlayer(holder: SurfaceHolder) {
            releasePlayer()

            val file = File(prefs.videoWallpaperPath ?: return)
            if (!file.exists()) {
                Log.w(TAG, "Video wallpaper file is gone: ${file.path}")
                return
            }

            player = MediaPlayer().apply {
                try {
                    setDataSource(file.absolutePath)
                    setSurface(holder.surface)
                    isLooping = true
                    applyVolume(this)
                    setOnPreparedListener { if (isVisible) it.start() }
                    prepareAsync()
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Could not open video wallpaper", e)
                    release()
                    player = null
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "MediaPlayer refused the video wallpaper", e)
                    release()
                    player = null
                }
            }
        }

        private fun applyVolume(player: MediaPlayer) {
            val volume = if (prefs.videoWallpaperAudio) 1f else 0f
            runCatching { player.setVolume(volume, volume) }
        }

        private fun releasePlayer() {
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
        }
    }

    private companion object {
        const val TAG = "VideoWallpaper"
    }
}
