package app.auriel.edenlauncher.wallpaper.picker

import android.app.Activity
import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.auriel.edenlauncher.LauncherAppState
import app.auriel.edenlauncher.R
import app.auriel.edenlauncher.media.MediaCodecTranscoder
import app.auriel.edenlauncher.media.VideoTranscoder
import app.auriel.edenlauncher.util.EdenLog
import app.auriel.edenlauncher.wallpaper.BundledWallpaper
import app.auriel.edenlauncher.wallpaper.BundledWallpapers
import app.auriel.edenlauncher.wallpaper.VideoWallpaperService
import app.auriel.edenlauncher.wallpaper.WallpaperStillRenderer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Eden's wallpaper picker.
 *
 * The point of this screen is that **looking costs nothing**. Every preview here is a still
 * frame - rendered offscreen for the bundled wallpapers, pulled from the system for third-party
 * ones, decoded from the file for videos. Nothing starts running because you glanced at it, so
 * nothing can be interrupted by a notification, a call, or a mis-tapped camera button, and there
 * is no state to lose when you come back.
 *
 * A live wallpaper can only actually be *set* through the system's own confirmation screen: the
 * API for setting one directly is reserved for the system. That is the right place for it anyway.
 * Browsing is exploratory and should be free; setting is a decision and gets one deliberate
 * confirmation, for the one wallpaper you chose.
 */
class WallpaperPickerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Still frames are rendered one at a time.
     *
     * Each render builds and tears down its own EGL context, and doing several at once on a
     * two-core phone is how you make a picker that takes a visible second to open.
     */
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)

    private lateinit var content: LinearLayout
    private var videoTile: WallpaperTileView? = null
    private var videoStatus: TextView? = null
    private var imageTile: WallpaperTileView? = null
    private var imageStatus: TextView? = null

    /** The visualiser waiting on a permission answer before it is set. */
    private var pendingVisualizerComponent: android.content.ComponentName? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.wallpaper_picker_title)
        setContentView(buildContentView())

        renderBundledPreviews()
        loadVideoPreview()
        loadImagePreview()
    }

    /** The crop screen finishes into the home screen, so a return here means it was cancelled. */
    override fun onResume() {
        super.onResume()
        loadImagePreview()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- layout ----------------------------------------------------------------------------------

    private fun buildContentView(): View {
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(header(getString(R.string.wallpaper_section_live)))
        content.addView(note(getString(R.string.wallpaper_preview_note)))
        content.addView(bundledGrid())

        content.addView(header(getString(R.string.wallpaper_section_video)))
        content.addView(videoSection())

        content.addView(header(getString(R.string.wallpaper_section_image)))
        content.addView(imageSection())

        val installed = installedLiveWallpapers()
        if (installed.isNotEmpty()) {
            content.addView(header(getString(R.string.wallpaper_section_installed)))
            content.addView(installedGrid(installed))
        }

        return ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.settings_background))
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            fitsSystemWindows = true
        }
    }

    private fun bundledGrid(): View {
        val grid = twoColumnGrid()
        for (wallpaper in BundledWallpapers.all) {
            val tile = tileView(getString(wallpaper.titleRes)) { setLiveWallpaper(wallpaper) }
            tile.tag = wallpaper.id
            grid.addView(tile, tileParams())
        }
        return grid
    }

    private fun installedGrid(wallpapers: List<WallpaperInfo>): View {
        val grid = twoColumnGrid()
        for (info in wallpapers) {
            val tile = tileView(info.loadLabel(packageManager).toString()) {
                setLiveWallpaperComponent(info.component)
            }
            // The system already holds a thumbnail for these; there is nothing for Eden to render.
            tile.previewDrawable = runCatching { info.loadThumbnail(packageManager) }.getOrNull()
            grid.addView(tile, tileParams())
        }
        return grid
    }

    private fun videoSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val grid = twoColumnGrid()
        val tile = tileView(getString(R.string.wallpaper_video_current)) { setVideoWallpaper() }
        videoTile = tile
        grid.addView(tile, tileParams())
        column.addView(grid)

        val status = note(
            if (currentVideoFile()?.exists() == true) {
                getString(R.string.wallpaper_video_ready)
            } else {
                getString(R.string.wallpaper_video_none)
            },
        )
        videoStatus = status
        column.addView(status)

        column.addView(actionButton(getString(R.string.wallpaper_pick_video)) { pickVideo() })
        column.addView(note(getString(R.string.wallpaper_video_terms)))
        return column
    }

    /**
     * The static wallpaper, shown the same way as every other one here.
     *
     * A row of rendered stills next to a bare "Choose a picture" button made the static wallpaper
     * look like the odd one out, and gave no way to see what was currently set without leaving. The
     * tile is Eden's own copy of the crop it last applied - the system will not hand a wallpaper
     * back without a storage permission, and a launcher asking for one to draw a thumbnail is not
     * a trade worth making.
     */
    private fun imageSection(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val grid = twoColumnGrid()
        val tile = tileView(getString(R.string.wallpaper_image_current)) { reframeStillWallpaper() }
        imageTile = tile
        grid.addView(tile, tileParams())
        column.addView(grid)

        val status = note(
            if (stillSourceFile()?.exists() == true) {
                getString(R.string.wallpaper_image_reframe_hint)
            } else {
                getString(R.string.wallpaper_image_none)
            },
        )
        imageStatus = status
        column.addView(status)

        column.addView(actionButton(getString(R.string.wallpaper_pick_image)) { pickImage() })
        return column
    }

    private fun twoColumnGrid() = GridLayout(this).apply {
        columnCount = COLUMNS
        useDefaultMargins = false
    }

    private fun tileParams(): GridLayout.LayoutParams {
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        val screenWidth = resources.displayMetrics.widthPixels
        val padding = resources.getDimensionPixelSize(R.dimen.settings_padding)
        val tileWidth = (screenWidth - padding * 2 - spacing * (COLUMNS - 1)) / COLUMNS

        return GridLayout.LayoutParams().apply {
            width = tileWidth
            // Portrait tiles: a wallpaper is seen on a portrait screen, so a landscape thumbnail
            // would be showing a crop that does not exist.
            height = (tileWidth * TILE_ASPECT).toInt()
            setMargins(0, spacing, spacing, 0)
        }
    }

    private fun tileView(title: String, onClick: () -> Unit): WallpaperTileView =
        WallpaperTileView(this).apply {
            this.title = title
            setOnClickListener { onClick() }
        }

    // ---- previews --------------------------------------------------------------------------------

    /**
     * Rasterises each bundled wallpaper into its tile.
     *
     * Rendered at tile resolution rather than screen resolution: this is a thumbnail, and asking
     * the GPU for 1080p to show it at 160dp is the kind of waste that adds up four times over.
     */
    private fun renderBundledPreviews() {
        val params = tileParams()
        val width = params.width
        val height = params.height
        if (width <= 0 || height <= 0) return

        for (wallpaper in BundledWallpapers.all) {
            scope.launch {
                val bitmap = withContext(renderDispatcher) {
                    runCatching {
                        WallpaperStillRenderer.render(
                            { wallpaper.renderer(applicationContext) },
                            width,
                            height,
                        )
                    }.getOrNull()
                }
                if (bitmap != null) tileWithTag(wallpaper.id)?.preview = bitmap
            }
        }
    }

    private fun tileWithTag(id: String): WallpaperTileView? =
        content.findViewWithTag<WallpaperTileView>(id)

    private fun loadVideoPreview() {
        val file = currentVideoFile() ?: return
        if (!file.exists()) return

        scope.launch {
            val frame = withContext(Dispatchers.IO) { firstFrameOf(file) }
            if (frame != null) videoTile?.preview = frame
        }
    }

    private fun loadImagePreview() {
        val thumb = LauncherAppState.getInstance(this).preferences.stillWallpaperThumbPath
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?: return

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { android.graphics.BitmapFactory.decodeFile(thumb.absolutePath) }
                    .getOrNull()
            }
            if (bitmap != null) imageTile?.preview = bitmap
        }
    }

    private fun firstFrameOf(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)
        } catch (e: RuntimeException) {
            EdenLog.w(TAG, "Could not read a frame from ${file.path}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- setting -----------------------------------------------------------------------------

    private fun setLiveWallpaper(wallpaper: BundledWallpaper) {
        if (wallpaper.needsPermissionNote) {
            askAudioSourceThenSet(wallpaper)
            return
        }
        setLiveWallpaperComponent(BundledWallpapers.componentFor(this, wallpaper))
    }

    /**
     * Asks how the visualiser should get its levels, before setting it.
     *
     * The choice exists in settings too, but a toggle buried in a list is not where this decision
     * gets made - it gets made the moment someone picks a wallpaper called "Music Visualization"
     * and forms an expectation about what it does. Asking here means nobody is surprised later by
     * a permission dialog they cannot connect to anything, and nobody is quietly given a wallpaper
     * that ignores their music without being told why.
     *
     * The no-permission option is listed first and is the default, because it should be.
     */
    private fun askAudioSourceThenSet(wallpaper: BundledWallpaper) {
        val component = BundledWallpapers.componentFor(this, wallpaper)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.visualizer_choice_title)
            .setMessage(R.string.visualizer_choice_message)
            .setPositiveButton(R.string.visualizer_choice_ambient) { _, _ ->
                LauncherAppState.getInstance(this).preferences.visualizerUsesRealAudio = false
                setLiveWallpaperComponent(component)
            }
            .setNegativeButton(R.string.visualizer_choice_real) { _, _ ->
                pendingVisualizerComponent = component
                val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    onAudioPermissionSettled(true)
                } else {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        REQUEST_RECORD_AUDIO,
                    )
                }
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun onAudioPermissionSettled(granted: Boolean) {
        val component = pendingVisualizerComponent ?: return
        pendingVisualizerComponent = null

        // Denied means the wallpaper still gets set - just on its own rhythm. Refusing a
        // permission should cost you the feature it guards, not the wallpaper.
        LauncherAppState.getInstance(this).preferences.visualizerUsesRealAudio = granted
        if (!granted) {
            Toast.makeText(this, R.string.visualizer_falling_back, Toast.LENGTH_LONG).show()
        }
        setLiveWallpaperComponent(component)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        onAudioPermissionSettled(
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED,
        )
    }

    /**
     * Hands off to the system's live wallpaper confirmation.
     *
     * `WallpaperManager.setWallpaperComponent` is a system-only API, so this is the only route an
     * ordinary app has. Everything up to this point stayed still by design; this is the one place
     * a wallpaper starts running, and only because you asked for that one.
     */
    private fun setLiveWallpaperComponent(component: android.content.ComponentName) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivityForResult(intent, REQUEST_SET_LIVE)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No live wallpaper chooser on this device", e)
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Leaves for the home screen once a wallpaper has actually been set.
     *
     * You choose a wallpaper in order to look at it. Being returned to the grid of thumbnails you
     * just chose from reads as "that did not work", and the usual next move is to set it a second
     * time. Going home instead makes the result of the action the thing you see.
     *
     * A HOME intent rather than a plain `finish()` because the picker can be opened from the
     * launcher's overview or from settings, and finishing would land back on whichever it was.
     * The destination should depend on what you did, not on how you got here.
     */
    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(home) }
        finish()
    }

    private fun setVideoWallpaper() {
        val file = currentVideoFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.wallpaper_video_none, Toast.LENGTH_SHORT).show()
            return
        }
        setLiveWallpaperComponent(
            android.content.ComponentName(this, VideoWallpaperService::class.java),
        )
    }

    // ---- picking -----------------------------------------------------------------------------

    private fun pickVideo() = pick("video/*", REQUEST_VIDEO)

    private fun pickImage() = pick("image/*", REQUEST_IMAGE)

    private fun pick(mimeType: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            EdenLog.w(TAG, "No document picker for $mimeType", e)
            Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Plain Activity has no result registry; the androidx one is not worth the dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        // The live wallpaper confirmation carries no data; RESULT_OK is the whole message.
        if (requestCode == REQUEST_SET_LIVE) {
            goHome()
            return
        }

        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_VIDEO -> importVideo(uri)
            REQUEST_IMAGE -> importImage(uri)
        }
    }

    private fun importVideo(source: Uri) {
        val status = videoStatus ?: return
        status.text = getString(R.string.wallpaper_video_preparing)

        val target = File(filesDir, VIDEO_FILE_NAME)
        val request = VideoTranscoder.Request(source = source, target = target)

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                MediaCodecTranscoder(applicationContext).transcode(request) { percent ->
                    // Progress arrives on the worker thread; hop back to touch the view.
                    scope.launch {
                        status.text = getString(R.string.wallpaper_video_progress, percent)
                    }
                }
            }

            when (result) {
                is VideoTranscoder.Result.Success -> {
                    LauncherAppState.getInstance(this@WallpaperPickerActivity)
                        .preferences.videoWallpaperPath = result.file.absolutePath
                    status.text = getString(R.string.wallpaper_video_ready)
                    loadVideoPreview()
                }

                VideoTranscoder.Result.Unsupported -> {
                    status.text = getString(R.string.wallpaper_video_unsupported)
                }

                is VideoTranscoder.Result.Failed -> {
                    EdenLog.w(TAG, "Transcode failed: ${result.reason}")
                    status.text = getString(R.string.wallpaper_video_failed)
                }
            }
        }
    }

    /**
     * Takes a copy of the picked image, then hands it to the crop screen.
     *
     * Copied for the same reason the video is: the picture lives in another app's storage, and a
     * content URI can be revoked or the file moved. Owning the original is what lets the crop be
     * adjusted later without going back to the gallery to find the photo again.
     */
    private fun importImage(source: Uri) {
        val status = imageStatus
        status?.setText(R.string.wallpaper_image_copying)

        scope.launch {
            val file = withContext(Dispatchers.IO) { copyToFiles(source) }
            if (file == null) {
                status?.setText(R.string.wallpaper_image_none)
                Toast.makeText(
                    this@WallpaperPickerActivity,
                    R.string.wallpaper_crop_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            LauncherAppState.getInstance(this@WallpaperPickerActivity)
                .preferences.stillWallpaperSourcePath = file.absolutePath
            status?.setText(R.string.wallpaper_image_reframe_hint)
            startActivity(WallpaperCropActivity.intentFor(this@WallpaperPickerActivity, file))
        }
    }

    private fun copyToFiles(source: Uri): File? = runCatching {
        val target = File(filesDir, IMAGE_FILE_NAME)
        contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: return null
        target.takeIf { it.length() > 0 }
    }.onFailure { EdenLog.w(TAG, "Could not copy $source", it) }.getOrNull()

    /** Reopens the crop screen on the picture already chosen, so framing can be changed alone. */
    private fun reframeStillWallpaper() {
        val file = stillSourceFile()
        if (file == null || !file.exists()) {
            pickImage()
            return
        }
        startActivity(WallpaperCropActivity.intentFor(this, file))
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun currentVideoFile(): File? =
        LauncherAppState.getInstance(this).preferences.videoWallpaperPath?.let(::File)

    private fun stillSourceFile(): File? =
        LauncherAppState.getInstance(this).preferences.stillWallpaperSourcePath?.let(::File)

    private fun installedLiveWallpapers(): List<WallpaperInfo> {
        val intent = Intent(WallpaperService.SERVICE_INTERFACE)
        return packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)
            .asSequence()
            // Eden's own are already listed above under their real names.
            .filter { it.serviceInfo.packageName != packageName }
            .mapNotNull { runCatching { WallpaperInfo(this, it) }.getOrNull() }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .toList()
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text))
        textSize = 22f
        gravity = Gravity.START
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        setPadding(0, spacing * 2, 0, spacing)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.settings_text_secondary))
        textSize = 13f
        val spacing = resources.getDimensionPixelSize(R.dimen.settings_row_spacing)
        setPadding(0, 0, 0, spacing)
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private companion object {
        const val TAG = "WallpaperPicker"
        const val COLUMNS = 2
        const val TILE_ASPECT = 1.5f
        const val REQUEST_VIDEO = 1
        const val REQUEST_IMAGE = 2
        const val REQUEST_SET_LIVE = 3
        const val REQUEST_RECORD_AUDIO = 4
        const val VIDEO_FILE_NAME = "wallpaper_video.mp4"
        const val IMAGE_FILE_NAME = "wallpaper_image"
    }
}
