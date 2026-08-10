package app.auriel.edenlauncher.media

import android.net.Uri
import java.io.File

/**
 * Turns a video the user picked into one Eden is willing to loop forever as a wallpaper.
 *
 * This is an interface with exactly one implementation on purpose. The obvious way to do video
 * processing on Android is to bundle FFmpeg, and Eden deliberately does not: the maintained
 * Android build of it was retired in January 2025, the builds that can encode H.264 are GPL and
 * would relicense this whole MIT app, and it would take the release APK from under a megabyte to
 * tens of them - on a launcher aimed at 4 GB phones. [MediaCodecTranscoder] uses the hardware
 * encoder that is already in the phone.
 *
 * The cost of that choice is real and worth naming: the platform decoder only handles what the
 * device's own codecs handle, so an exotic container or an AV1 file on an older phone will be
 * refused where FFmpeg would have coped. If that turns out to bite in practice, this interface is
 * where a second implementation plugs in - as an optional download, not a bundled blob.
 */
interface VideoTranscoder {

    /**
     * @param maxHeight longest-edge cap for the output. 1080 keeps a wallpaper sharp on any phone
     *   while staying well inside what a low-end encoder will accept.
     * @param frameRate target frames per second. 24 is a deliberate choice, not a limitation: a
     *   wallpaper is behind your icons, and the difference between 24 and 60 there is battery.
     * @param keepAudio whether the source's audio track is carried over. It is copied, never
     *   re-encoded. Even when kept it plays silent until the user turns it on - keeping the track
     *   means that setting is a toggle rather than a reason to re-import the video.
     * @param maxDurationSeconds how much of the source is kept, or 0 for all of it. A wallpaper
     *   loops, so past a point the extra minutes are storage and decode work nobody sees. The
     *   picker says out loud that it takes the first minute rather than silently truncating.
     */
    data class Request(
        val source: Uri,
        val target: File,
        val maxHeight: Int = 1080,
        val frameRate: Int = 24,
        val keepAudio: Boolean = true,
        val maxDurationSeconds: Int = 60,
    )

    sealed interface Result {
        data class Success(val file: File, val width: Int, val height: Int) : Result

        /** The device's codecs cannot read this file. Distinct from [Failed]: retrying will not help. */
        data object Unsupported : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Runs the conversion. Blocking and CPU-bound; callers put it on [kotlinx.coroutines.Dispatchers.Default]
     * or an IO dispatcher, never the main thread.
     *
     * @param onProgress called with 0..100. Coarse by design - it is driven by presentation
     *   timestamps, which do not advance smoothly.
     */
    fun transcode(request: Request, onProgress: (Int) -> Unit = {}): Result
}
