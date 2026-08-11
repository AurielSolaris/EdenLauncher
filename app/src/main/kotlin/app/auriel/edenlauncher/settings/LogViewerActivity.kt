package app.auriel.edenlauncher.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.util.EdenLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads back [EdenLog] on the device.
 *
 * A log nobody can see is a log nobody uses. Pulling a file over adb is fine for the person who
 * wrote the launcher and useless to everyone else, so the last 48 hours are one tap from settings,
 * with a share button that puts them straight into a bug report.
 */
class LogViewerActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroller: ScrollView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_log_title)
        setContentView(buildContentView())
        reload()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, 0)
            addView(actionButton(R.string.settings_log_refresh) { reload() })
            addView(actionButton(R.string.settings_log_share) { share() })
            addView(actionButton(R.string.settings_log_clear) { clear() })
        }

        output = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.settings_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LOG_TEXT_SP)
            setPadding(padding, padding, padding, padding)
        }

        // Log lines run long and wrapping them makes a stack trace unreadable, so the text scrolls
        // sideways inside a vertical scroller rather than being reflowed.
        val sideways = HorizontalScrollView(this).apply { addView(output) }

        scroller = ScrollView(this).apply {
            addView(
                sideways,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.settings_background))
            fitsSystemWindows = true
            addView(
                actions,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                scroller,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }

    private fun actionButton(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(labelRes)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /**
     * Reads the file off the main thread. Half a megabyte of text is not much, but it is a disk
     * read, and this screen exists to diagnose jank rather than to add some.
     */
    private fun reload() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { EdenLog.read() }
            output.text = text.ifBlank { getString(R.string.settings_log_empty) }
            // Newest lines are at the bottom, which is where anyone opening this wants to be.
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun share() {
        val file = EdenLog.file()
        if (file == null) {
            Toast.makeText(this, R.string.settings_log_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.logs", file)
        }.getOrNull() ?: return

        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_log_share_subject))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.settings_log_share)))
        }.onFailure {
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clear() {
        EdenLog.clear()
        output.text = getString(R.string.settings_log_empty)
    }

    private companion object {
        const val LOG_TEXT_SP = 11f
    }
}
