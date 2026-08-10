package app.auriel.edenlauncher.wallpaper

import android.content.ComponentName
import android.content.Context
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.wallpaper.live.FallRenderer
import app.auriel.edenlauncher.wallpaper.live.FallWallpaperService
import app.auriel.edenlauncher.wallpaper.live.Galaxy4Renderer
import app.auriel.edenlauncher.wallpaper.live.Galaxy4WallpaperService
import app.auriel.edenlauncher.wallpaper.live.GrassRenderer
import app.auriel.edenlauncher.wallpaper.live.GrassWallpaperService
import app.auriel.edenlauncher.wallpaper.live.HoloSpiralRenderer
import app.auriel.edenlauncher.wallpaper.live.HoloSpiralWallpaperService
import app.auriel.edenlauncher.wallpaper.live.MagicSmokeRenderer
import app.auriel.edenlauncher.wallpaper.live.MagicSmokeWallpaperService
import app.auriel.edenlauncher.wallpaper.live.MusicVisualizationRenderer
import app.auriel.edenlauncher.wallpaper.live.MusicVisualizationWallpaperService
import app.auriel.edenlauncher.wallpaper.live.NexusRenderer
import app.auriel.edenlauncher.wallpaper.live.NexusWallpaperService
import app.auriel.edenlauncher.wallpaper.live.NoiseFieldRenderer
import app.auriel.edenlauncher.wallpaper.live.NoiseFieldWallpaperService
import app.auriel.edenlauncher.wallpaper.live.PhaseBeamRenderer
import app.auriel.edenlauncher.wallpaper.live.PhaseBeamWallpaperService
import app.auriel.edenlauncher.wallpaper.live.PolarClockRenderer
import app.auriel.edenlauncher.wallpaper.live.PolarClockWallpaperService
import app.auriel.edenlauncher.wallpaper.live.WalkaroundRenderer
import app.auriel.edenlauncher.wallpaper.live.WalkaroundWallpaperService

/**
 * One wallpaper Eden ships, in the form the picker needs it: something to show, and something to
 * set.
 *
 * The [renderer] factory is the whole point of the split. The picker calls it to draw a still
 * frame offscreen; the service calls it to draw the live one. The preview is therefore the same
 * code as the wallpaper, which is why the thumbnail is never a stale screenshot that no longer
 * matches what you get.
 *
 * @param needsPermissionNote true for wallpapers whose behaviour depends on a permission the user
 *   may not have granted, so the picker can say so rather than letting them wonder why it looks
 *   different from the description.
 */
class BundledWallpaper(
    val id: String,
    val titleRes: Int,
    val summaryRes: Int,
    val service: Class<*>,
    val renderer: (Context) -> WallpaperRenderer,
    val needsPermissionNote: Boolean = false,
)

/**
 * Every live wallpaper Eden bundles.
 *
 * All of them are rewrites of AOSP originals that modern Android no longer ships and could not
 * ship if it wanted to: they were RenderScript, deprecated in Android 12 and removed from the
 * Android Gradle plugin in 9.0. Nothing is copied from those sources - what carries over is the
 * composition, the palette, and in a few cases the maths that gives each one its character.
 * `NOTICE` names each original.
 *
 * Two names from the AOSP wallpaper repositories are deliberately absent, because neither is a
 * wallpaper: `LivePicker` is the chooser app, which Eden's own picker replaces, and
 * `ImageWallpaper` is the system's static-image renderer. The old `Basic` galaxy is absent too -
 * `Galaxy4` is its successor and is here instead.
 */
object BundledWallpapers {

    val all: List<BundledWallpaper> = listOf(
        BundledWallpaper(
            id = "phasebeam",
            titleRes = R.string.wallpaper_phasebeam,
            summaryRes = R.string.wallpaper_phasebeam_summary,
            service = PhaseBeamWallpaperService::class.java,
            renderer = { PhaseBeamRenderer() },
        ),
        BundledWallpaper(
            id = "noisefield",
            titleRes = R.string.wallpaper_noisefield,
            summaryRes = R.string.wallpaper_noisefield_summary,
            service = NoiseFieldWallpaperService::class.java,
            renderer = { NoiseFieldRenderer() },
        ),
        BundledWallpaper(
            id = "galaxy4",
            titleRes = R.string.wallpaper_galaxy4,
            summaryRes = R.string.wallpaper_galaxy4_summary,
            service = Galaxy4WallpaperService::class.java,
            renderer = { Galaxy4Renderer() },
        ),
        BundledWallpaper(
            id = "holospiral",
            titleRes = R.string.wallpaper_holospiral,
            summaryRes = R.string.wallpaper_holospiral_summary,
            service = HoloSpiralWallpaperService::class.java,
            renderer = { HoloSpiralRenderer() },
        ),
        BundledWallpaper(
            id = "magicsmoke",
            titleRes = R.string.wallpaper_magicsmoke,
            summaryRes = R.string.wallpaper_magicsmoke_summary,
            service = MagicSmokeWallpaperService::class.java,
            renderer = { MagicSmokeRenderer() },
        ),
        BundledWallpaper(
            id = "nexus",
            titleRes = R.string.wallpaper_nexus,
            summaryRes = R.string.wallpaper_nexus_summary,
            service = NexusWallpaperService::class.java,
            renderer = { NexusRenderer() },
        ),
        BundledWallpaper(
            id = "fall",
            titleRes = R.string.wallpaper_fall,
            summaryRes = R.string.wallpaper_fall_summary,
            service = FallWallpaperService::class.java,
            renderer = { FallRenderer() },
        ),
        BundledWallpaper(
            id = "grass",
            titleRes = R.string.wallpaper_grass,
            summaryRes = R.string.wallpaper_grass_summary,
            service = GrassWallpaperService::class.java,
            renderer = { GrassRenderer() },
        ),
        BundledWallpaper(
            id = "polarclock",
            titleRes = R.string.wallpaper_polarclock,
            summaryRes = R.string.wallpaper_polarclock_summary,
            service = PolarClockWallpaperService::class.java,
            renderer = { PolarClockRenderer() },
        ),
        BundledWallpaper(
            id = "walkaround",
            titleRes = R.string.wallpaper_walkaround,
            summaryRes = R.string.wallpaper_walkaround_summary,
            service = WalkaroundWallpaperService::class.java,
            renderer = { WalkaroundRenderer(it) },
        ),
        BundledWallpaper(
            id = "musicvis",
            titleRes = R.string.wallpaper_musicvis,
            summaryRes = R.string.wallpaper_musicvis_summary,
            service = MusicVisualizationWallpaperService::class.java,
            renderer = { MusicVisualizationRenderer(it) },
            needsPermissionNote = true,
        ),
    )

    fun componentFor(context: Context, wallpaper: BundledWallpaper): ComponentName =
        ComponentName(context, wallpaper.service)
}
