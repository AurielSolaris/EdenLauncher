package app.auriel.edenlauncher.settings

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.auriel.edenlauncher.R

/**
 * Launcher settings.
 *
 * A launcher ported out of AOSP has no host app to hang a preference screen off, so it needs its
 * own. Built from plain views rather than `androidx.preference`: that library would pull in
 * AppCompat and a fragment stack to render half a dozen rows.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: LauncherPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LauncherPrefs(this)
        setContentView(buildContentView())
        title = getString(R.string.settings_title)
    }

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        column.addView(header(getString(R.string.settings_section_drawer)))

        column.addView(label(getString(R.string.settings_drawer_mode)), spacedParams(spacing))
        column.addView(drawerModeChooser())

        column.addView(label(getString(R.string.settings_drawer_opacity)), spacedParams(spacing))
        column.addView(opacitySlider())

        column.addView(
            label(getString(R.string.settings_drawer_columns)),
            spacedParams(spacing),
        )
        column.addView(
            gridSlider(
                current = prefs.drawerColumns,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.drawerColumns = it },
        )

        column.addView(header(getString(R.string.settings_section_home)), spacedParams(spacing * 2))

        column.addView(label(getString(R.string.settings_grid_columns)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.workspaceColumns,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.workspaceColumns = it },
        )

        column.addView(label(getString(R.string.settings_grid_rows)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.workspaceRows,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.workspaceRows = it },
        )

        column.addView(label(getString(R.string.settings_hotseat_icons)), spacedParams(spacing))
        column.addView(
            gridSlider(
                current = prefs.hotseatIcons,
                autoLabel = R.string.settings_grid_auto,
            ) { prefs.hotseatIcons = it },
        )

        column.addView(header(getString(R.string.settings_section_icons)), spacedParams(spacing * 2))

        column.addView(label(getString(R.string.settings_icon_size)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconSizePercent,
                min = LauncherPrefs.MIN_ICON_PERCENT,
                max = LauncherPrefs.MAX_ICON_PERCENT,
                suffix = "%",
            ) { prefs.iconSizePercent = it },
        )

        column.addView(label(getString(R.string.settings_icon_padding_vertical)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconPaddingVerticalDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconPaddingVerticalDp = it },
        )

        column.addView(label(getString(R.string.settings_icon_padding_horizontal)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconPaddingHorizontalDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconPaddingHorizontalDp = it },
        )

        column.addView(label(getString(R.string.settings_icon_label_spacing)), spacedParams(spacing))
        column.addView(
            rangeSlider(
                current = prefs.iconLabelSpacingDp,
                min = 0,
                max = LauncherPrefs.MAX_ICON_PADDING,
                suffix = "dp",
            ) { prefs.iconLabelSpacingDp = it },
        )

        column.addView(
            label(getString(R.string.settings_grid_restart_note)),
            spacedParams(spacing * 2),
        )

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.settings_background))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            fitsSystemWindows = true
        }
    }

    private fun drawerModeChooser(): View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }

        val vertical = radio(
            getString(R.string.settings_drawer_mode_vertical),
            getString(R.string.settings_drawer_mode_vertical_summary),
            id = ID_VERTICAL,
        )
        val horizontal = radio(
            getString(R.string.settings_drawer_mode_horizontal),
            getString(R.string.settings_drawer_mode_horizontal_summary),
            id = ID_HORIZONTAL,
        )
        group.addView(vertical)
        group.addView(horizontal)

        group.check(if (prefs.appDrawerMode == AppDrawerMode.VERTICAL) ID_VERTICAL else ID_HORIZONTAL)
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.appDrawerMode =
                if (checkedId == ID_HORIZONTAL) AppDrawerMode.HORIZONTAL else AppDrawerMode.VERTICAL
        }
        return group
    }

    private fun opacitySlider(): View {
        val readout = label("${prefs.appDrawerOpacity}%")

        val seekBar = SeekBar(this).apply {
            max = 100
            progress = prefs.appDrawerOpacity
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        readout.text = "$value%"
                        // Written continuously: the launcher observes the flow, so the drawer
                        // behind this screen updates as the slider moves.
                        if (fromUser) prefs.appDrawerOpacity = value
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    /**
     * A slider over the grid range where the lowest position means "auto".
     *
     * Auto is the first stop rather than a separate checkbox: a user who does not care never has
     * to think about it, and one who does gets an exact number.
     */
    private fun gridSlider(current: Int, autoLabel: Int, onChange: (Int) -> Unit): View {
        val span = LauncherPrefs.MAX_GRID - LauncherPrefs.MIN_GRID + 1
        val readout = label(gridText(current, autoLabel))

        val seekBar = SeekBar(this).apply {
            max = span
            progress = if (current <= 0) 0 else current - LauncherPrefs.MIN_GRID + 1
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        val resolved = if (value == 0) LauncherPrefs.AUTO else value + LauncherPrefs.MIN_GRID - 1
                        readout.text = gridText(resolved, autoLabel)
                        if (fromUser) onChange(resolved)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    private fun gridText(value: Int, autoLabel: Int): String =
        if (value <= 0) getString(autoLabel) else value.toString()

    /** A slider over a plain numeric range, with a live readout. */
    private fun rangeSlider(
        current: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChange: (Int) -> Unit,
    ): View {
        val readout = label("$current$suffix")

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        val resolved = value + min
                        readout.text = "$resolved$suffix"
                        if (fromUser) onChange(resolved)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                },
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekBar)
            addView(readout)
        }
    }

    // ---- small view builders ---------------------------------------------------------------------

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = 22f
        gravity = Gravity.START
        setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.settings_row_spacing))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = 16f
    }

    private fun radio(title: String, summary: String, id: Int) = RadioButton(this).apply {
        this.id = id
        text = buildString {
            append(title)
            append('\n')
            append(summary)
        }
        setTextColor(getColor(R.color.settings_text))
        textSize = 15f
        setPadding(
            resources.getDimensionPixelSize(R.dimen.settings_padding),
            resources.getDimensionPixelSize(R.dimen.settings_row_spacing),
            0,
            resources.getDimensionPixelSize(R.dimen.settings_row_spacing),
        )
    }

    private fun spacedParams(spacing: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = spacing }

    private companion object {
        const val ID_VERTICAL = 1
        const val ID_HORIZONTAL = 2
    }
}
