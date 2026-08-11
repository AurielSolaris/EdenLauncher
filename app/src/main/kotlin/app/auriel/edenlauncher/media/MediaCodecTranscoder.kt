package app.auriel.edenlauncher.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import app.auriel.edenlauncher.util.EdenLog
import app.auriel.edenlauncher.wallpaper.EglContextHolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Converts a video with the phone's own hardware codecs. See [VideoTranscoder] for why this is
 * not FFmpeg.
 *
 * The path is the one Android has supported since API 18 and is the same one the CTS media tests
 * exercise: extractor to decoder, decoder to an external texture, texture drawn into the encoder's
 * input surface, encoder to muxer. The draw in the middle is where the scale to 1080p happens, so
 * resizing costs nothing extra - the GPU was going to touch every pixel anyway.
 *
 * Audio is copied through the muxer untouched rather than re-encoded. There is nothing to gain by
 * re-encoding it and a generation of quality to lose, and it means importing a video is bounded by
 * the video work alone.
 */
class MediaCodecTranscoder(private val context: Context) : VideoTranscoder {

    override fun transcode(
        request: VideoTranscoder.Request,
        onProgress: (Int) -> Unit,
    ): VideoTranscoder.Result {
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglContextHolder? = null
        var blitter: TextureFrameBlitter? = null
        val callbackThread = HandlerThread("EdenTranscodeFrames")

        try {
            extractor = MediaExtractor().apply { setSource(context, request.source) }
            val videoTrack = extractor.firstTrackOfType("video/")
                ?: return VideoTranscoder.Result.Unsupported
            extractor.selectTrack(videoTrack)

            val sourceFormat = extractor.getTrackFormat(videoTrack)
            val sourceWidth = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = sourceFormat.optLong(MediaFormat.KEY_DURATION, 0L)
            val rotation = sourceFormat.optInt(MediaFormat.KEY_ROTATION, 0)

            val (targetWidth, targetHeight) = targetSize(sourceWidth, sourceHeight, request.maxHeight)

            // Encoder first: the decoder has to be told to render into a surface derived from it.
            encoder = MediaCodec.createEncoderByType(VIDEO_MIME)
            encoder.configure(
                encoderFormat(targetWidth, targetHeight, request.frameRate),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val encoderInput = encoder.createInputSurface()
            encoder.start()

            egl = EglContextHolder()
            if (!egl.createContext() || !egl.createWindowSurface(encoderInput)) {
                return VideoTranscoder.Result.Failed("no GL context for the encoder surface")
            }

            callbackThread.start()
            blitter = TextureFrameBlitter()
            blitter.setUp()
            blitter.surfaceTexture.setDefaultBufferSize(targetWidth, targetHeight)

            val frameLock = Object()
            var frameAvailable = false
            blitter.surfaceTexture.setOnFrameAvailableListener(
                { synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() } },
                Handler(callbackThread.looper),
            )

            decoder = MediaCodec.createDecoderByType(
                sourceFormat.getString(MediaFormat.KEY_MIME) ?: return VideoTranscoder.Result.Unsupported,
            )
            decoder.configure(sourceFormat, blitter.surface, null, 0)
            decoder.start()

            muxer = MediaMuxer(request.target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            // The audio track is opened now but written after the video, once the muxer has been
            // started by the encoder's format change.
            var audioFormat: MediaFormat? = null
            if (request.keepAudio) {
                audioExtractor = MediaExtractor().apply { setSource(context, request.source) }
                val audioTrack = audioExtractor.firstTrackOfType("audio/")
                if (audioTrack != null) {
                    audioExtractor.selectTrack(audioTrack)
                    audioFormat = audioExtractor.getTrackFormat(audioTrack)
                } else {
                    audioExtractor.release()
                    audioExtractor = null
                }
            }

            val loop = TranscodeLoop(
                extractor = extractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                egl = egl,
                blitter = blitter,
                frameLock = frameLock,
                isFrameAvailable = { frameAvailable },
                clearFrameAvailable = { frameAvailable = false },
                frameIntervalUs = 1_000_000L / request.frameRate,
                durationUs = durationUs,
                maxDurationUs = request.maxDurationSeconds * 1_000_000L,
                audioFormat = audioFormat,
                onProgress = onProgress,
            )
            loop.run()

            audioExtractor?.let { loop.copyAudio(it) }
            onProgress(100)

            return VideoTranscoder.Result.Success(request.target, targetWidth, targetHeight)
        } catch (e: IllegalArgumentException) {
            // MediaExtractor and MediaCodec both raise this for "I do not know this format".
            EdenLog.w(TAG, "Unsupported source ${request.source}", e)
            return VideoTranscoder.Result.Unsupported
        } catch (e: MediaCodec.CodecException) {
            EdenLog.w(TAG, "Codec failed for ${request.source}", e)
            return VideoTranscoder.Result.Unsupported
        } catch (e: java.io.IOException) {
            EdenLog.w(TAG, "IO failure transcoding ${request.source}", e)
            return VideoTranscoder.Result.Failed(e.message ?: "could not read the file")
        } finally {
            // Ordered so nothing is torn down while something else still points at it.
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { blitter?.release() }
            runCatching { egl?.release() }
            runCatching { extractor?.release() }
            runCatching { audioExtractor?.release() }
            callbackThread.quitSafely()
        }
    }

    /**
     * Output size: never upscale, cap the short edge at [maxHeight] and the long edge at 16:9 of
     * it, and round both to a multiple of 16.
     *
     * The rounding is not cosmetic. Plenty of hardware encoders quietly produce corrupt output, or
     * refuse to configure at all, for dimensions that are not a multiple of 16.
     */
    private fun targetSize(width: Int, height: Int, maxHeight: Int): Pair<Int, Int> {
        val shortEdge = minOf(width, height)
        val longEdge = maxOf(width, height)
        val maxLongEdge = maxHeight * 16 / 9

        val scale = minOf(
            1f,
            maxHeight.toFloat() / shortEdge,
            maxLongEdge.toFloat() / longEdge,
        )

        fun round(value: Int): Int = ((value * scale / 16f).roundToInt() * 16).coerceAtLeast(16)
        return round(width) to round(height)
    }

    private fun encoderFormat(width: Int, height: Int, frameRate: Int): MediaFormat =
        MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            // Bits per pixel per frame. 0.14 is a good-looking constant-ish quality for the kind
            // of slow footage people use as a wallpaper, without producing a file that dwarfs the
            // source.
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * frameRate * 0.14f).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // A keyframe every second: the wallpaper loops, and a long GOP makes the seek back to
            // zero visibly stutter.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private companion object {
        const val TAG = "MediaCodecTranscoder"
        const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}

// ---- small helpers -------------------------------------------------------------------------------

private fun MediaExtractor.setSource(context: Context, uri: android.net.Uri) {
    context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
        requireNotNull(descriptor) { "cannot open $uri" }
        setDataSource(descriptor.fileDescriptor)
    }
}

private fun MediaExtractor.firstTrackOfType(prefix: String): Int? {
    for (i in 0 until trackCount) {
        val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(prefix)) return i
    }
    return null
}

private fun MediaFormat.optInt(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

private fun MediaFormat.optLong(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback

/**
 * The pump: extractor to decoder to texture to encoder to muxer, plus the frame dropping that
 * turns whatever the source ran at into the requested frame rate.
 *
 * Pulled out of the transcoder mostly so the state this needs is not fifteen locals in one method.
 */
private class TranscodeLoop(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val egl: EglContextHolder,
    private val blitter: TextureFrameBlitter,
    private val frameLock: Object,
    private val isFrameAvailable: () -> Boolean,
    private val clearFrameAvailable: () -> Unit,
    private val frameIntervalUs: Long,
    private val durationUs: Long,
    private val maxDurationUs: Long,
    private val audioFormat: MediaFormat?,
    private val onProgress: (Int) -> Unit,
) {

    private val bufferInfo = MediaCodec.BufferInfo()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var lastEmittedPtsUs = Long.MIN_VALUE
    private var reportedProgress = -1

    fun run() {
        var inputDone = false
        var decoderDone = false

        while (!decoderDone) {
            if (!inputDone) inputDone = feedDecoder()
            decoderDone = drainDecoder(inputDone)
            drainEncoder(endOfStream = false)
        }

        encoder.signalEndOfInputStream()
        drainEncoder(endOfStream = true)
    }

    /** @return true once the extractor has no more samples. */
    private fun feedDecoder(): Boolean {
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false

        val buffer = decoder.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        val pts = extractor.sampleTime

        val pastLimit = maxDurationUs > 0 && pts >= maxDurationUs
        if (size < 0 || pastLimit) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }

        decoder.queueInputBuffer(index, 0, size, pts, 0)
        extractor.advance()
        return false
    }

    /** @return true when the decoder has signalled end of stream. */
    private fun drainDecoder(inputDone: Boolean): Boolean {
        val index = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return false
            index < 0 -> return false
        }

        val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val hasImage = bufferInfo.size > 0

        // Frame dropping happens here, before the GPU is asked to do anything: a frame that will
        // not be kept is released without rendering, so dropping to 24 fps makes the conversion
        // faster rather than merely smaller.
        val wanted = hasImage &&
            (lastEmittedPtsUs == Long.MIN_VALUE ||
                bufferInfo.presentationTimeUs - lastEmittedPtsUs >= frameIntervalUs)

        decoder.releaseOutputBuffer(index, wanted)

        if (wanted) {
            lastEmittedPtsUs = bufferInfo.presentationTimeUs
            awaitFrame()
            blitter.drawLatestFrame()
            egl.setPresentationTime(bufferInfo.presentationTimeUs * 1000L)
            egl.swapBuffers()
            reportProgress(bufferInfo.presentationTimeUs)
        }

        return endOfStream || (inputDone && endOfStream)
    }

    private fun awaitFrame() {
        synchronized(frameLock) {
            var waited = 0L
            while (!isFrameAvailable() && waited < FRAME_WAIT_TIMEOUT_MS) {
                frameLock.wait(FRAME_WAIT_STEP_MS)
                waited += FRAME_WAIT_STEP_MS
            }
            clearFrameAvailable()
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // At end of stream keep polling: the encoder still has frames in flight.
                    continue
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startMuxer(encoder.outputFormat)
                    continue
                }

                index < 0 -> continue
            }

            val buffer = encoder.getOutputBuffer(index)
            // Codec config bytes are handed to the muxer through the track format, not as a
            // sample; writing them would corrupt the file.
            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            }
            encoder.releaseOutputBuffer(index, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun startMuxer(videoFormat: MediaFormat) {
        if (muxerStarted) return
        videoTrackIndex = muxer.addTrack(videoFormat)
        audioFormat?.let { audioTrackIndex = muxer.addTrack(it) }
        muxer.start()
        muxerStarted = true
    }

    /**
     * Copies the audio track across verbatim once the video is done.
     *
     * Kept even though the wallpaper starts muted: the mute is a playback setting, and holding the
     * track means turning sound on later is a toggle rather than a reason to import the video
     * again.
     */
    fun copyAudio(audioExtractor: MediaExtractor) {
        if (!muxerStarted || audioTrackIndex < 0) return

        val buffer = ByteBuffer.allocate(AUDIO_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val size = audioExtractor.readSampleData(buffer, 0)
            if (size < 0) return

            val pts = audioExtractor.sampleTime
            if (maxDurationUs > 0 && pts >= maxDurationUs) return

            info.offset = 0
            info.size = size
            info.presentationTimeUs = pts
            info.flags = audioExtractor.sampleFlags
            muxer.writeSampleData(audioTrackIndex, buffer, info)
            audioExtractor.advance()
        }
    }

    private fun reportProgress(presentationTimeUs: Long) {
        val total = if (maxDurationUs > 0) minOf(durationUs, maxDurationUs) else durationUs
        if (total <= 0) return
        val percent = ((presentationTimeUs * 100) / total).toInt().coerceIn(0, 99)
        if (percent != reportedProgress) {
            reportedProgress = percent
            onProgress(percent)
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val FRAME_WAIT_TIMEOUT_MS = 500L
        const val FRAME_WAIT_STEP_MS = 10L
        const val AUDIO_BUFFER_BYTES = 256 * 1024
    }
}
