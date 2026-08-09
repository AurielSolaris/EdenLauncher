package app.auriel.edenlauncher.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsCompat

/** A view that wants the window insets handed to it rather than turned into padding. */
interface Insettable {
    fun setInsets(insets: Rect)
}

/**
 * A [FrameLayout] that forwards window insets to [Insettable] children and turns them into
 * margins for everything else.
 *
 * Ported from `InsettableFrameLayout` (AOSP 7), with `fitSystemWindows` replaced by
 * `onApplyWindowInsets`: the old callback is gone on modern Android, and the launcher draws
 * edge-to-edge under the status and navigation bars anyway.
 */
open class InsettableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    @JvmField
    protected val insets = Rect()

    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, this)
        val bars = compat.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        setInsets(Rect(bars.left, bars.top, bars.right, bars.bottom))
        // Insets are consumed here: children are positioned by the launcher, not by the system.
        return WindowInsetsCompat.CONSUMED.toWindowInsets() ?: windowInsets
    }

    open fun setInsets(newInsets: Rect) {
        if (insets == newInsets) return
        for (i in 0 until childCount) {
            applyInsetsToChild(getChildAt(i), insets, newInsets)
        }
        insets.set(newInsets)
    }

    /**
     * Margins carry the *delta* so repeated dispatches stay idempotent - a child that was already
     * pushed down by the status bar must not be pushed down again on the next pass.
     */
    private fun applyInsetsToChild(child: View, oldInsets: Rect, newInsets: Rect) {
        if (child is Insettable) {
            child.setInsets(newInsets)
            return
        }
        val lp = child.layoutParams as? LayoutParams ?: return
        if (lp.ignoreInsets) return
        lp.leftMargin += newInsets.left - oldInsets.left
        lp.topMargin += newInsets.top - oldInsets.top
        lp.rightMargin += newInsets.right - oldInsets.right
        lp.bottomMargin += newInsets.bottom - oldInsets.bottom
        child.layoutParams = lp
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        // A child added after insets arrived has none of them applied yet.
        if (!insets.isEmpty) applyInsetsToChild(child, EMPTY_INSETS, insets)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    class LayoutParams : FrameLayout.LayoutParams {
        /** Set on views that deliberately extend under the system bars (the wallpaper scrim). */
        @JvmField
        var ignoreInsets: Boolean = false

        constructor(width: Int, height: Int) : super(width, height)
        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
        constructor(source: ViewGroup.LayoutParams) : super(source)
    }

    private companion object {
        val EMPTY_INSETS = Rect()
    }
}
