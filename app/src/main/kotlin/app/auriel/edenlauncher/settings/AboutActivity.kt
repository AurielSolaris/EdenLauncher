package app.auriel.edenlauncher.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.auriel.edenlauncher.R

/**
 * What Eden is, which version of it you are running, and what it owes to AOSP.
 *
 * On the device rather than only in the repository, because the two audiences are different: the
 * README is read by someone deciding whether to build this, and this screen is read by someone who
 * already has it installed and wants to know what it is, what version to quote in a bug report, or
 * what the licence says. Neither is served by being told to go and look at the other.
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.about_title)
        setContentView(buildContentView())
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        column.addView(heading(getString(R.string.app_name)))
        column.addView(version(), spacedParams(spacing / 2))

        column.addView(body(getString(R.string.about_what_it_is)), spacedParams(spacing * 2))

        column.addView(sectionHeader(getString(R.string.about_section_licence)), spacedParams(spacing * 2))
        column.addView(body(getString(R.string.about_licence)), spacedParams(spacing))

        column.addView(sectionHeader(getString(R.string.about_section_contact)), spacedParams(spacing * 2))
        column.addView(body(getString(R.string.about_contact)), spacedParams(spacing))
        column.addView(emailButton(), spacedParams(spacing))

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.settings_background))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * Read from the installed package rather than from `BuildConfig`.
     *
     * It is the same number, but this one is what the system believes is installed - which is the
     * number that matters when someone is telling you which build misbehaved.
     */
    private fun version(): TextView {
        val name = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        return TextView(this).apply {
            text = getString(R.string.about_version, name)
            setTextColor(getColor(R.color.accent))
            textSize = VERSION_SP
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = HEADING_SP
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.accent))
        textSize = SECTION_SP
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text_secondary))
        textSize = BODY_SP
        setLineSpacing(0f, LINE_SPACING)
    }

    /**
     * Opens a mail composer already addressed. Falls back to a message rather than a crash when
     * there is no mail app, which on a phone set up for one purpose is a real configuration.
     */
    private fun emailButton() = Button(this).apply {
        text = getString(R.string.about_email_action)
        gravity = Gravity.CENTER
        setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:" + getString(R.string.about_email))
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_email_subject))
            }
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(
                    this@AboutActivity,
                    getString(R.string.about_email),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun spacedParams(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private companion object {
        const val HEADING_SP = 28f
        const val VERSION_SP = 15f
        const val SECTION_SP = 15f
        const val BODY_SP = 14f
        const val LINE_SPACING = 1.25f
    }
}
