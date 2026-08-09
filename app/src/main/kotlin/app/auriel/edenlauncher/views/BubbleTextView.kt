package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.device.DeviceProfile
import app.auriel.edenlauncher.model.AppInfo
import app.auriel.edenlauncher.model.ItemInfo
import app.auriel.edenlauncher.model.ShortcutInfo

/**
 * An icon with a label under it: the view used for every app on the workspace and in the dock.
 *
 * Ported from `BubbleTextView` (AOSP 7). The icon is a compound drawable rather than a child
 * `ImageView`, which is what keeps a full page of icons at one view each instead of three.
 */
class BubbleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    /** The model object this view stands for; the click handler reads its intent. */
    var itemInfo: ItemInfo? = null
        private set

    private val profile: DeviceProfile = deviceProfile(context)
    private val prefs = LauncherAppState.getInstance(context).preferences

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        includeFontPadding = false

        setTextColor(context.getColor(R.color.workspace_icon_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, profile.iconTextSizePx.toFloat())
        // A shadow is what keeps labels readable over an arbitrary wallpaper.
        setShadowLayer(2f, 0f, 1f, context.getColor(R.color.workspace_icon_text_shadow))
        compoundDrawablePadding = dp(prefs.iconLabelSpacingDp)

        isFocusable = true
        isClickable = true
        isLongClickable = true
        setBackgroundResource(0)

        // Cell padding lives on the icon rather than on each container: the workspace grid, the
        // dock, folders, and both drawer styles then space identically from one setting, without
        // four copies of the same numbers.
        setPadding(
            dp(prefs.iconPaddingHorizontalDp),
            dp(prefs.iconPaddingVerticalDp),
            dp(prefs.iconPaddingHorizontalDp),
            dp(prefs.iconPaddingVerticalDp),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Shows or hides the label. The dock hides it - a single row has no space for one - and the
     * same view shows it again when it is dragged back onto the workspace.
     */
    fun setLabelVisible(visible: Boolean) {
        val padH = dp(prefs.iconPaddingHorizontalDp)
        if (visible) {
            text = itemInfo?.title
            setPadding(padH, dp(prefs.iconPaddingVerticalDp), padH, dp(prefs.iconPaddingVerticalDp))
        } else {
            text = null
            setPadding(padH, 0, padH, 0)
        }
    }

    /** Binds a drawer entry. The [AppInfo] itself is the tag, so a drag can copy it out. */
    fun applyFromApp(info: AppInfo) {
        itemInfo = info
        text = info.title
        contentDescription = info.title
        setIconBitmap(info.icon)
        alpha = if (info.isDisabled) DISABLED_ALPHA else 1f
    }

    /** Binds an app or shortcut, sizing the icon to the current grid. */
    fun applyFrom(info: ShortcutInfo, icon: Bitmap?) {
        itemInfo = info
        text = info.title
        contentDescription = info.contentDescription ?: info.title
        setIconBitmap(icon)
        alpha = if (info.isDisabled) DISABLED_ALPHA else 1f
    }

    fun setIconBitmap(icon: Bitmap?) {
        if (icon == null) {
            setCompoundDrawables(null, null, null, null)
            return
        }
        val drawable = BitmapDrawable(resources, icon)
        drawable.setBounds(0, 0, profile.iconSizePx, profile.iconSizePx)
        setCompoundDrawables(null, drawable, null, null)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f

        fun deviceProfile(context: Context): DeviceProfile {
            val landscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return LauncherAppState.getInstance(context).invariantDeviceProfile.profileFor(landscape)
        }
    }
}
