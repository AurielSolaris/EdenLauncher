package app.auriel.edenlauncher.folder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.device.DeviceProfile
import app.auriel.edenlauncher.model.FolderInfo
import app.auriel.edenlauncher.model.ShortcutInfo

/**
 * The closed-folder icon: a rounded tile showing up to four of the folder's app icons.
 *
 * Ported from `FolderIcon` (AOSP 7), which composed several child views and a `FolderRingAnimator`.
 * This is a single [TextView] whose compound drawable is a preview bitmap regenerated only when
 * the contents change - a folder icon costs the same as an app icon on a page.
 */
class FolderIcon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr), FolderInfo.Listener {

    var folderInfo: FolderInfo? = null
        private set

    private val profile: DeviceProfile = deviceProfile(context)
    private val previewSize = profile.iconSizePx
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val previewBounds = RectF()
    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextColor(context.getColor(R.color.workspace_icon_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, profile.iconTextSizePx.toFloat())
        setShadowLayer(2f, 0f, 1f, context.getColor(R.color.workspace_icon_text_shadow))
        compoundDrawablePadding = profile.iconDrawablePaddingPx
        isFocusable = true
        isClickable = true
        isLongClickable = true

        backgroundPaint.color = context.getColor(R.color.folder_preview_background)
    }

    fun applyFrom(info: FolderInfo) {
        folderInfo?.removeListener(this)
        folderInfo = info
        info.addListener(this)
        text = info.title ?: resources.getString(R.string.folder_name)
        contentDescription = text
        refreshPreview()
    }

    /** Rebuilds the tile bitmap from the first few contents. */
    private fun refreshPreview() {
        val info = folderInfo ?: return
        val bitmap = Bitmap.createBitmap(previewSize, previewSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val radius = previewSize * BACKGROUND_RADIUS_RATIO
        previewBounds.set(0f, 0f, previewSize.toFloat(), previewSize.toFloat())
        canvas.drawRoundRect(previewBounds, radius, radius, backgroundPaint)

        // Two-by-two grid of the first four icons, inset so the tile edge stays visible.
        val inset = (previewSize * PREVIEW_INSET_RATIO).toInt()
        val cell = (previewSize - inset * 3) / 2
        val count = minOf(NUM_ITEMS_IN_PREVIEW, info.contents.size)

        for (i in 0 until count) {
            val icon = info.contents[i].icon ?: continue
            val col = i % 2
            val row = i / 2
            val left = inset + col * (cell + inset)
            val top = inset + row * (cell + inset)
            srcRect.set(0, 0, icon.width, icon.height)
            dstRect.set(left, top, left + cell, top + cell)
            canvas.drawBitmap(icon, srcRect, dstRect, iconPaint)
        }

        val drawable = BitmapDrawable(resources, bitmap)
        drawable.setBounds(0, 0, previewSize, previewSize)
        setCompoundDrawables(null, drawable, null, null)
    }

    // ---- FolderInfo.Listener --------------------------------------------------------------------

    override fun onAdd(item: ShortcutInfo) = refreshPreview()

    override fun onRemove(item: ShortcutInfo) = refreshPreview()

    override fun onTitleChanged(title: CharSequence?) {
        text = title
        contentDescription = title
    }

    override fun onItemsChanged() = refreshPreview()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The model outlives this view; leaving the listener attached would leak the activity.
        folderInfo?.removeListener(this)
    }

    companion object {
        /** How many contents the closed icon previews. */
        const val NUM_ITEMS_IN_PREVIEW = 4

        private const val BACKGROUND_RADIUS_RATIO = 0.22f
        private const val PREVIEW_INSET_RATIO = 0.1f

        private fun deviceProfile(context: Context): DeviceProfile {
            val landscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return LauncherAppState.getInstance(context).invariantDeviceProfile.profileFor(landscape)
        }
    }
}
