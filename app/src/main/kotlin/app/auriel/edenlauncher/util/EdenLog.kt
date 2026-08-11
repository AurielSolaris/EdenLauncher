package app.auriel.edenlauncher.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The launcher's own log, kept on disk for 48 hours.
 *
 * logcat is the obvious answer and it is not good enough here. A launcher's interesting failures -
 * a wallpaper that died overnight, an icon that vanished after an update, a crash on the third
 * reboot - happen while nobody is holding a cable, and by the time anyone thinks to look, the
 * kernel ring buffer has wrapped. This writes the same lines somewhere they survive, and throws
 * them away on a timer so it can never become a thing that quietly fills the user's storage.
 *
 * Everything still goes to logcat as well, so `adb logcat -s Launcher:D` behaves exactly as before.
 *
 * Nothing here blocks the caller: lines go into an unbounded channel and one coroutine drains it.
 * Losing the last few lines to a hard kill is the accepted cost of never making a UI thread wait
 * on a filesystem.
 */
object EdenLog {

    /** Where the log lives. Private storage, so no permission and no media scanner. */
    private const val FILE_NAME = "eden.log"

    /** Retention window. Past this, a line is not debugging information, it is clutter. */
    private const val RETAIN_MILLIS = 48L * 60L * 60L * 1000L

    /**
     * Hard ceiling, checked on every prune. Retention alone is not a bound: a crash loop can
     * produce more in ten minutes than a normal week, and the log must never be the reason a
     * device runs out of room.
     */
    private const val MAX_BYTES = 512L * 1024L

    /** Fraction of the file kept when [MAX_BYTES] is passed, so pruning is not a per-line cost. */
    private const val TRIM_KEEP_RATIO = 0.6

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Length of the timestamp prefix, used to find it again when pruning. */
    private const val TIMESTAMP_LENGTH = 23

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lines = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var logFile: File? = null

    /**
     * Starts the log. Safe to call more than once; later calls do nothing.
     *
     * Called from `EdenApplication`, which otherwise deliberately does no startup work. What runs
     * on the calling thread here is installing an exception handler and opening a channel; the
     * prune and every write happen on [scope].
     */
    fun install(context: Context) {
        if (logFile != null) return
        val file = File(context.filesDir, FILE_NAME)
        logFile = file

        scope.launch {
            runCatching { prune(file) }
            for (line in lines) {
                runCatching {
                    file.appendText(line)
                    if (file.length() > MAX_BYTES) prune(file)
                }
            }
        }

        installCrashHandler()
        i(TAG, "log started, keeping the last 48 hours")
    }

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write(Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, tag, message, error)

    /** The whole log as text, for sharing. Empty when nothing has been written yet. */
    fun read(): String = logFile?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull().orEmpty()

    /** The file itself, so a share intent can hand out a URI rather than a megabyte of string. */
    fun file(): File? = logFile?.takeIf { it.exists() && it.length() > 0 }

    fun sizeBytes(): Long = logFile?.takeIf { it.exists() }?.length() ?: 0L

    fun clear() {
        val file = logFile ?: return
        scope.launch { runCatching { file.writeText("") } }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        Log.println(priority, tag, if (error == null) message else "$message: $error")

        val stamp = LocalDateTime.now().format(timestampFormat)
        val text = buildString {
            append(stamp).append(' ').append(levelOf(priority)).append('/')
            append(tag).append(": ").append(message).append('\n')
            if (error != null) append(stackTraceOf(error))
        }
        // trySend rather than send: a log line is never worth suspending a caller for, and the
        // channel is unbounded so this only fails if the log was never installed.
        lines.trySend(text)
    }

    private fun levelOf(priority: Int): Char = when (priority) {
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'V'
    }

    private fun stackTraceOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use(error::printStackTrace)
        return writer.toString()
    }

    /**
     * Drops everything older than the retention window, then everything beyond the size ceiling.
     *
     * Rewrites the file rather than truncating in place. At half a megabyte that is one read and
     * one write on a background thread, which is cheaper than maintaining an index would be.
     */
    private fun prune(file: File) {
        if (!file.exists() || file.length() == 0L) return

        val cutoff = System.currentTimeMillis() - RETAIN_MILLIS
        val kept = ArrayList<String>(512)
        var dropping = false

        for (line in file.readLines()) {
            val stamp = timestampOf(line)
            when {
                // A line with no timestamp is a continuation - a stack trace frame - so it keeps
                // the fate of the line that introduced it.
                stamp == null -> if (!dropping) kept.add(line)
                stamp < cutoff -> dropping = true
                else -> {
                    dropping = false
                    kept.add(line)
                }
            }
        }

        var text = kept.joinToString("\n", postfix = "\n")
        if (text.length > MAX_BYTES) {
            val start = text.length - (MAX_BYTES * TRIM_KEEP_RATIO).toInt()
            // Start at a line boundary so the file never opens on half a message.
            val boundary = text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: start
            text = text.substring(boundary)
        }
        file.writeText(text)
    }

    /** Epoch millis from a line's leading timestamp, or null when it has none. */
    private fun timestampOf(line: String): Long? {
        if (line.length < TIMESTAMP_LENGTH) return null
        return runCatching {
            LocalDateTime.parse(line.substring(0, TIMESTAMP_LENGTH), timestampFormat)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /**
     * Records a crash before the process dies, then hands over to whatever handler was already
     * there so the system still sees a normal crash and still offers to report it.
     *
     * This is the single most useful thing in the log: a launcher crash takes the home screen with
     * it, so the user's next action is a reboot, and a reboot is exactly what clears logcat.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = logFile
                if (file != null) {
                    val stamp = LocalDateTime.now().format(timestampFormat)
                    // Written directly rather than through the channel: the process is about to
                    // end and the draining coroutine will not get another turn.
                    file.appendText(
                        "$stamp E/$TAG: crash on thread ${thread.name}\n${stackTraceOf(error)}",
                    )
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val TAG = "EdenLog"
}
