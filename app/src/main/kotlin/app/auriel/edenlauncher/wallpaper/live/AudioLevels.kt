package app.auriel.edenlauncher.wallpaper.live

import android.media.audiofx.Visualizer
import app.auriel.edenlauncher.util.EdenLog
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where the music visualiser gets its bars from.
 *
 * Two implementations, and which one runs is the user's call rather than Eden's:
 *
 * - [RealAudioLevels] reads what the device is actually playing. Honest, reacts to your music,
 *   and costs a permission prompt that says "record audio" - which for a wallpaper is a big ask.
 * - [AmbientAudioLevels] invents a plausible rhythm. No permission, nothing to be uneasy about,
 *   and it looks like a music visualiser because it is built out of the same shapes one makes.
 *
 * Offering both is the point. Nobody should have to grant microphone-shaped access to get a
 * pretty background, and nobody who wants the real thing should be denied it for their own good.
 */
interface AudioLevels {

    /**
     * Fills [out] with band magnitudes in 0..1, quietest band first.
     *
     * @param timeSeconds scene time, used by the ambient source and ignored by the real one.
     */
    fun read(out: FloatArray, timeSeconds: Float)

    fun release() = Unit
}

/**
 * Bands invented from overlapping oscillators.
 *
 * The trick to not looking fake is that real music is not periodic at the second scale: a bar that
 * pulses on a clean sine reads instantly as a screensaver. So each band mixes three oscillators at
 * deliberately unrelated rates plus a slow envelope, which never quite repeats, and a shared
 * "beat" term that all bands respond to so they move together the way they would to a kick drum.
 */
class AmbientAudioLevels : AudioLevels {

    private val rateA = FloatArray(MAX_BANDS)
    private val rateB = FloatArray(MAX_BANDS)
    private val rateC = FloatArray(MAX_BANDS)
    private val phase = FloatArray(MAX_BANDS)

    init {
        val random = Random(SEED)
        for (i in 0 until MAX_BANDS) {
            // Low bands move slowly and hit harder; high bands flicker. Same as real spectra.
            val highness = i.toFloat() / MAX_BANDS
            rateA[i] = 0.7f + highness * 3.5f + random.nextFloat() * 0.9f
            rateB[i] = 1.9f + highness * 5.0f + random.nextFloat() * 1.3f
            rateC[i] = 0.31f + random.nextFloat() * 0.5f
            phase[i] = random.nextFloat() * TWO_PI
        }
    }

    override fun read(out: FloatArray, timeSeconds: Float) {
        // A shared pulse at roughly 100 bpm, sharpened so it reads as a hit rather than a swell.
        val beatPhase = (timeSeconds * BEATS_PER_SECOND) % 1f
        val beat = (1f - beatPhase) * (1f - beatPhase)

        val bands = minOf(out.size, MAX_BANDS)
        for (i in 0 until bands) {
            val highness = i.toFloat() / bands

            val a = sin(timeSeconds * rateA[i] + phase[i])
            val b = sin(timeSeconds * rateB[i] + phase[i] * 1.7f)
            val envelope = 0.55f + 0.45f * sin(timeSeconds * rateC[i])

            // Low bands take most of the beat; the top end barely notices it.
            val beatShare = beat * (1f - highness) * 0.55f

            val level = (abs(a) * 0.5f + abs(b) * 0.28f) * envelope + beatShare
            // Quieter towards the top, as a real spectrum falls away.
            out[i] = (level * (1f - highness * 0.45f)).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val MAX_BANDS = 64
        const val TWO_PI = 6.2831855f
        const val BEATS_PER_SECOND = 1.7f
        const val SEED = 0xA3B107
    }
}

/**
 * Bands from the device's real audio output, via [Visualizer].
 *
 * Worth being precise about what this does and does not do, because the permission it needs is
 * alarming and the reality is milder: `Visualizer` attached to session 0 taps the **output mix** -
 * what the speakers are playing. It cannot hear the room. Android gates it behind `RECORD_AUDIO`
 * anyway, and the microphone indicator lights up regardless, which is exactly why the ambient
 * source exists as the default.
 */
class RealAudioLevels : AudioLevels {

    private var visualizer: Visualizer? = null
    private var fft: ByteArray? = null

    /** Smoothed levels, so bars fall away rather than snapping to zero between samples. */
    private val smoothed = FloatArray(MAX_BANDS)

    /** @return true if capture started; false means the caller should fall back to ambient. */
    fun start(): Boolean = try {
        val instance = Visualizer(0).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(CAPTURE_SIZE)
            enabled = true
        }
        visualizer = instance
        fft = ByteArray(instance.captureSize)
        true
    } catch (e: RuntimeException) {
        // Thrown when the permission is missing, another app holds the effect, or the device has
        // no output session. All of them mean the same thing here: use the ambient source.
        EdenLog.w(TAG, "Visualizer unavailable, falling back to ambient", e)
        release()
        false
    }

    override fun read(out: FloatArray, timeSeconds: Float) {
        val instance = visualizer
        val buffer = fft
        if (instance == null || buffer == null) {
            out.fill(0f)
            return
        }

        if (instance.getFft(buffer) != Visualizer.SUCCESS) return

        val bands = minOf(out.size, MAX_BANDS)
        // getFft returns interleaved real/imaginary pairs; magnitude is the hypotenuse. Bytes are
        // signed, so each has to be widened before squaring.
        val perBand = (buffer.size / 2) / bands
        for (i in 0 until bands) {
            var peak = 0f
            for (k in 0 until perBand) {
                val index = (i * perBand + k) * 2
                if (index + 1 >= buffer.size) break
                val real = buffer[index].toInt().toFloat()
                val imaginary = buffer[index + 1].toInt().toFloat()
                val magnitude = kotlin.math.sqrt(real * real + imaginary * imaginary)
                if (magnitude > peak) peak = magnitude
            }

            val level = (peak / MAGNITUDE_SCALE).coerceIn(0f, 1f)
            // Attack fast, release slow: bars jump to a beat and glide back down, which is what
            // makes a visualiser look like it is listening rather than twitching.
            smoothed[i] = if (level > smoothed[i]) {
                level
            } else {
                smoothed[i] + (level - smoothed[i]) * RELEASE
            }
            out[i] = smoothed[i]
        }
    }

    override fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        fft = null
    }

    private companion object {
        const val TAG = "RealAudioLevels"
        const val MAX_BANDS = 64
        const val CAPTURE_SIZE = 512
        const val MAGNITUDE_SCALE = 90f
        const val RELEASE = 0.22f
    }
}
