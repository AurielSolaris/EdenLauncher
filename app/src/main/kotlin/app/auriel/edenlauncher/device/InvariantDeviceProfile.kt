package app.auriel.edenlauncher.device

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import app.auriel.edenlauncher.settings.LauncherPrefs
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Grid metrics that do not change with orientation: how many rows and columns the workspace has,
 * how big an icon is, how many hotseat slots exist.
 *
 * Ported from `InvariantDeviceProfile` (AOSP 7). Discrete values (rows, columns, hotseat count)
 * are taken from the single closest predefined profile; continuous values (icon and text size)
 * are interpolated between the nearest few, which is what keeps a 5.5" phone from inheriting a
 * tablet's icon size just because it matched on one axis.
 */
class InvariantDeviceProfile private constructor(
    @JvmField val name: String,
    @JvmField val minWidthDp: Float,
    @JvmField val minHeightDp: Float,
    @JvmField val numRows: Int,
    @JvmField val numColumns: Int,
    @JvmField val numFolderRows: Int,
    @JvmField val numFolderColumns: Int,
    @JvmField val iconSizeDp: Float,
    @JvmField val iconTextSizeDp: Float,
    @JvmField val numHotseatIcons: Int,
    @JvmField val hotseatIconSizeDp: Float,
) {

    /** Pixel size of a cached icon bitmap on this device. */
    var iconBitmapSizePx: Int = 0
        private set

    /** Metrics for the current orientation; recomputed on configuration change. */
    lateinit var portrait: DeviceProfile
        private set

    lateinit var landscape: DeviceProfile
        private set

    fun profileFor(isLandscape: Boolean): DeviceProfile = if (isLandscape) landscape else portrait

    private fun withDeviceProfiles(context: Context): InvariantDeviceProfile {
        val dm = context.resources.displayMetrics
        iconBitmapSizePx = pxFromDp(iconSizeDp, dm)

        val config = context.resources.configuration
        val widthDp = config.screenWidthDp.toFloat()
        val heightDp = config.screenHeightDp.toFloat()
        val shortSideDp = min(widthDp, heightDp)
        val longSideDp = maxOf(widthDp, heightDp)

        portrait = DeviceProfile(context, this, shortSideDp, longSideDp, isLandscape = false)
        landscape = DeviceProfile(context, this, longSideDp, shortSideDp, isLandscape = true)
        return this
    }

    companion object {
        /** Icon size on a reference 4-5" phone. */
        private const val DEFAULT_ICON_SIZE_DP = 60f

        private const val NEAREST_NEIGHBOURS = 3
        private const val WEIGHT_POWER = 5.0
        private const val WEIGHT_SCALE = 100_000f

        /**
         * name, minWidthDp, minHeightDp, rows, cols, folderRows, folderCols,
         * iconDp, iconTextDp, hotseatCount, hotseatIconDp.
         *
         * Values are AOSP Launcher3's, minus the Go-edition and 20" entries. Hotseat counts stay
         * odd: the centre slot is reserved for the all-apps button in the classic layout.
         */
        private val PREDEFINED = arrayOf(
            InvariantDeviceProfile("Super Short Stubby", 255f, 300f, 2, 3, 2, 3, 48f, 13f, 3, 48f),
            InvariantDeviceProfile("Shorter Stubby", 255f, 400f, 3, 3, 3, 3, 48f, 13f, 3, 48f),
            InvariantDeviceProfile("Short Stubby", 275f, 420f, 3, 4, 3, 4, 48f, 13f, 5, 48f),
            InvariantDeviceProfile("Stubby", 255f, 450f, 3, 4, 3, 4, 48f, 13f, 5, 48f),
            InvariantDeviceProfile("Nexus S", 296f, 491.33f, 4, 4, 4, 4, 48f, 13f, 5, 48f),
            InvariantDeviceProfile("Nexus 5", 335f, 567f, 4, 4, 4, 4, DEFAULT_ICON_SIZE_DP, 13f, 5, 56f),
            InvariantDeviceProfile("Nexus 4", 359f, 567f, 4, 4, 4, 4, DEFAULT_ICON_SIZE_DP, 13f, 5, 56f),
            InvariantDeviceProfile("Large Phone", 406f, 694f, 5, 5, 4, 4, 64f, 14.4f, 5, 56f),
            InvariantDeviceProfile("Nexus 7", 575f, 904f, 5, 6, 4, 5, 72f, 14.4f, 7, 60f),
            InvariantDeviceProfile("Nexus 10", 727f, 1207f, 5, 6, 4, 5, 76f, 14.4f, 7, 76f),
        )

        fun create(context: Context): InvariantDeviceProfile {
            val config = context.resources.configuration
            val widthDp = min(config.screenWidthDp, config.screenHeightDp).toFloat()
            val heightDp = maxOf(config.screenWidthDp, config.screenHeightDp).toFloat()

            val closest = findClosestProfiles(widthDp, heightDp)
            val discrete = findGridProfile(widthDp, heightDp)
            val interpolated = interpolate(widthDp, heightDp, closest)

            // The user's grid always wins over the measured one: this is the whole point of a
            // launcher with its own settings.
            val prefs = LauncherPrefs(context)
            val columns = prefs.workspaceColumns.takeIf { it > 0 } ?: discrete.numColumns
            val rows = prefs.workspaceRows.takeIf { it > 0 } ?: discrete.numRows
            val hotseatIcons = prefs.hotseatIcons.takeIf { it > 0 } ?: discrete.numHotseatIcons
            val iconScale = prefs.iconSizePercent / 100f

            return InvariantDeviceProfile(
                name = discrete.name,
                minWidthDp = widthDp,
                minHeightDp = heightDp,
                numRows = rows,
                numColumns = columns,
                numFolderRows = discrete.numFolderRows,
                numFolderColumns = discrete.numFolderColumns,
                iconSizeDp = interpolated.iconSizeDp * iconScale,
                iconTextSizeDp = interpolated.iconTextSizeDp,
                numHotseatIcons = hotseatIcons,
                hotseatIconSizeDp = interpolated.hotseatIconSizeDp * iconScale,
            ).withDeviceProfiles(context)
        }

        private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float =
            kotlin.math.hypot(x1 - x0, y1 - y0)

        /**
         * Neighbours for interpolating icon sizes: plain Euclidean distance, as in AOSP.
         */
        private fun findClosestProfiles(widthDp: Float, heightDp: Float): List<InvariantDeviceProfile> =
            PREDEFINED.sortedBy { dist(widthDp, heightDp, it.minWidthDp, it.minHeightDp) }
                .take(NEAREST_NEIGHBOURS)

        /**
         * The profile that decides rows and columns.
         *
         * Deliberately not the Euclidean nearest: AOSP measured height with
         * `Display.getCurrentSizeRange`, which excludes the system bars in whichever orientation
         * is shorter. Reading `Configuration.screenHeightDp` instead makes a tall modern phone
         * look tablet-shaped, and it lands on the 6-column Nexus 7 profile. Width is what actually
         * determines how many columns fit, so the discrete profile is matched on width, with
         * height only breaking ties.
         */
        private fun findGridProfile(widthDp: Float, heightDp: Float): InvariantDeviceProfile =
            PREDEFINED.minByOrNull { profile ->
                abs(profile.minWidthDp - widthDp) * 100f + abs(profile.minHeightDp - heightDp)
            } ?: PREDEFINED.last()

        /**
         * Inverse-distance weighted mean of the continuous properties. An exact match short-circuits
         * so a known device gets its exact numbers rather than a rounding of them.
         */
        private fun interpolate(
            widthDp: Float,
            heightDp: Float,
            neighbours: List<InvariantDeviceProfile>,
        ): Interpolated {
            var weightSum = 0f
            var icon = 0f
            var text = 0f
            var hotseatIcon = 0f

            for (p in neighbours) {
                val d = dist(widthDp, heightDp, p.minWidthDp, p.minHeightDp)
                if (d == 0f) return Interpolated(p.iconSizeDp, p.iconTextSizeDp, p.hotseatIconSizeDp)
                val w = weight(d)
                weightSum += w
                icon += p.iconSizeDp * w
                text += p.iconTextSizeDp * w
                hotseatIcon += p.hotseatIconSizeDp * w
            }
            return Interpolated(icon / weightSum, text / weightSum, hotseatIcon / weightSum)
        }

        private fun weight(distance: Float): Float =
            (WEIGHT_SCALE / distance.toDouble().pow(WEIGHT_POWER)).toFloat()

        private class Interpolated(
            val iconSizeDp: Float,
            val iconTextSizeDp: Float,
            val hotseatIconSizeDp: Float,
        )
    }
}

internal fun pxFromDp(dp: Float, dm: DisplayMetrics): Int =
    Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, dm))

internal fun pxFromSp(sp: Float, dm: DisplayMetrics): Int =
    Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm))

internal fun dpFromPx(px: Float, dm: DisplayMetrics): Float =
    px / (dm.densityDpi / DisplayMetrics.DENSITY_DEFAULT.toFloat())
