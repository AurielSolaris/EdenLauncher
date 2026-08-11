# Eden internals

How the pieces fit together, for anyone reading the code. `README.MD` has the why.

## The one rule that shapes everything

Views never touch the database. A gesture reaches a view, the view calls an interface, and
`Launcher` is the only class that turns that into a write. This is why the workspace, the dock, and
an open folder can share one drop path, and why no view needs a `Context` that is really an
`Activity`.

```
touch -> DragLayer -> DragController -> DropTarget -> ItemPlacementHandler -> Launcher
                                                                                |
                                                                  LauncherRepository -> Room
```

## Startup

1. `EdenApplication` does nothing. The launcher process is resident from boot; work here is charged
   to every cold start.
2. `Launcher.onCreate` inflates the hierarchy and wires the controllers.
3. `LauncherModel.load()` runs once on `Dispatchers.IO`: enumerate activities for every user
   profile, read `favorites` and `workspaceScreens`, resolve icons, write a first-run dock layout
   if the database is empty.
4. The result is bound on the main thread. One disk read, one bind.

`LauncherAppState` holds everything process-wide and builds it lazily: the database, the icon
cache, the grid, preferences.

## Data

Room, in `data/`. Column names match AOSP Launcher3's so a database lifted from a stock launcher
still reads, and so the eventual backup agent has nothing to translate.

| Table | Holds |
|-------|-------|
| `favorites` | Every placed item: apps, shortcuts, folders, widgets. `container` is `-100` (desktop), `-101` (dock), or a folder's row id. |
| `workspaceScreens` | Page order. A page exists here even with nothing on it, which is what lets a deliberately empty page survive a reboot. |

No destructive-migration fallback. Losing someone's home screen to a schema change is not an
acceptable failure mode.

Deep shortcuts reuse the `intent` column: the package and shortcut id ride inside a stored intent,
so pinned shortcuts needed no schema change.

## Views

| Class | Job |
|-------|-----|
| `DragLayer` | Root container and the coordinate space every drag is expressed in. Owns window insets and the transforms that map a point between any descendant and the root. |
| `PagedView` | Horizontal paging. AOSP's fling and significant-move thresholds and velocity-scaled snap duration are kept exactly - that is what makes it feel like Launcher3. |
| `Workspace` | Pages, drops onto them, and overview. |
| `CellLayout` | One page: grid placement, occupancy, and its own overview card decoration. |
| `Hotseat` | The dock. Maps rank to cell so the rest of the launcher stays orientation-agnostic. |
| `BubbleTextView` | An icon with a label, as a compound drawable rather than child views. Cell padding lives here, so every grid in the app spaces identically from one setting. |
| `PageIndicatorDots` | Drawn directly. The home page gets a ring. |

## State transitions

There is no state machine yet, deliberately. Each overlay knows how to open and close itself, and
`Launcher.onBackRequested` decides the order: cancel a drag, else dismiss the icon menu, else leave
overview, else close the drawer, else close a folder, else go to the home page. When the next state
appears, that is the moment to build one.

## Long press does two things

A long press on an icon has to mean both "show me the options" and "pick this up", and neither can
be given up. `DragController.preparePendingDrag` arms a drag without starting one and the launcher
shows `IconOptionsPopup`; the first movement past the touch slop, seen in
`DragLayer.onInterceptTouchEvent`, promotes it into a real drag and dismisses the menu. Hold still
and you get the menu; move and you get the drag. This is what Launcher3 has done since Android 8.

The popup fills the drag layer and handles its own dismissal rather than using
`DragLayer.setTouchCompleteListener`. That hook fires on the `ACTION_UP` of the very long press
that opened the menu, so routing dismissal through it made the popup appear and vanish inside one
gesture.

## Icons

Three sources, most specific first. `IconCache.resolve` is the whole policy:

1. an icon the user picked for that one app (`icons/CustomIcons`),
2. the icon pack in force (`icons/IconPack`),
3. what the app ships.

That order is deliberate. Choosing an icon for one app is a more specific instruction than choosing
a pack for all of them, so switching packs must not silently undo it.

Icon packs have no Android API and never did - they are a convention that grew out of ADW, in which
an ordinary APK ships `appfilter.xml` mapping `ComponentInfo{pkg/cls}` to a drawable name. A pack
advertises itself with whichever theme action its author's launcher used, so `icons/IconPacks`
queries all seven common ones; checking only one loses a third of the packs a user has installed.
`appfilter.xml` lives in assets, `res/xml`, or `res/raw` depending on the pack, and all three are
tried.

| Class | Job |
|-------|-----|
| `IconPack` | Parses `appfilter.xml` and `drawable.xml` for one pack, resolves a component to a drawable, and composes an unthemed icon from the pack's background, mask, overlay, and scale. |
| `IconPacks` | Finds installed packs across every theme action. |
| `IconPickerActivity` | The whole pack in a searchable grid, for apps a pack has no entry for. Decodes lazily as rows scroll; a pack has thousands of icons and only twenty are on screen. |
| `CustomIcons` | Per-app PNGs under `filesDir/icons`, one file per component. Not in preferences - preferences are read whole at startup, and a dozen base64 icons in there is a dozen blobs charged against every cold start. |

Icons are resolved during the load, so changing the pack changes nothing until something reloads.
`Launcher.onResume` compares the stored pack against the one it last bound with and reloads when
they differ - the same shape as the grid-signature check next to it, but a rebind rather than a
recreate, because the views are still the right shape and only hold the wrong bitmaps.

## Packages appearing and disappearing

A `LauncherApps.Callback` registered for the whole life of the activity, not between `onStart` and
`onStop`: an app is almost always installed with the launcher in the background, which is exactly
the case that has to work.

Events arrive in bursts, so the rebind is coalesced behind a short delay and waits out any drag in
progress rather than deleting the view under a finger. Genuine removals accumulate across the burst
and go to `LauncherModel.purgePackages`, which deletes the rows - including folder children, and
the folder itself if that empties it. A cold load takes the opposite view and only flags a missing
item disabled, because there the cause might be an unmounted card; here `LauncherApps` has said the
package was removed, and it does not say that for an update or an unmount.

## The log

`util/EdenLog` writes to `filesDir/eden.log` and keeps 48 hours. Everything also goes to logcat, so
`adb logcat` behaves exactly as before.

It exists because logcat is not good enough for this app in particular: a launcher crash takes the
home screen with it, the user's next move is a reboot, and a reboot is what clears logcat. Lines go
into an unbounded channel drained by one coroutine, so no caller ever waits on the filesystem;
losing the last few lines to a hard kill is the accepted cost. The crash handler is the exception
and writes directly, because the process is about to end and the drain will not get another turn.

Pruning happens on startup and whenever the file passes 512 KB: lines older than the window are
dropped, continuation lines (stack frames, which carry no timestamp) share the fate of the line
that introduced them, and the file is rewritten. `settings/LogViewerActivity` shows it, with Share
through a `FileProvider` scoped to that one directory.

## Wallpapers

`wallpaper/` holds a small GL engine and eleven renderers. The split that matters is
`WallpaperRenderer`: it knows how to draw and nothing else, so the same code serves both the live
`GLWallpaperService` and `WallpaperStillRenderer`, which draws one frame into a pbuffer and reads
it back as a bitmap for the picker. The preview is therefore the wallpaper, not a screenshot of it
that can go stale.

| Class | Job |
|-------|-----|
| `GLWallpaperService` | EGL, a render thread, 30fps pacing, visibility gating, and the scene clock (scaled by the speed setting). |
| `EglContextHolder` | EGL14 for both window and pbuffer surfaces. Written directly because `GLSurfaceView` insists on being a `View`, and a wallpaper engine has a `SurfaceHolder` and no hierarchy to put one in. |
| `SoftPointField` | Batched soft round points, for particles and stars. |
| `SoftQuadField` | The same, as quads. Exists because `gl_PointSize` is capped by the driver - commonly 63 or 255 px - and oversized point sprites are **silently clamped**. Anything larger than a dot must be a quad. |
| `WallpaperStillRenderer` | Offscreen still frames for the picker. |

Sizes and speeds in every renderer are fractions of the surface, never pixel counts. That keeps a
wallpaper looking the same at 720p and 1440p, and it is what makes the picker's small still an
honest preview rather than the same scene at a quarter scale.

Nothing draws while hidden. The launcher additionally sends `COMMAND_PAUSE` through
`WallpaperManager.sendWallpaperCommand` when an opaque app drawer covers the wallpaper, because the
system still considers it visible there.

## Static wallpapers

`wallpaper/picker/CropView` frames a picture against the real display shape: the bright rectangle is
what will be visible, the dimmed surround is what gets cut, and the constraint in `constrain()`
makes it impossible to pan far enough to leave a blank edge. Nothing allocates in its `onDraw` or
`onTouchEvent` - the matrix and its scratch array are fields, and the scrim is four `drawRect` calls
rather than a clip-difference that would cost a `saveLayer` every frame of a drag.

Two resolutions on purpose. The preview is downsampled to at most 2048px so panning stays smooth on
a two-core phone; the applied wallpaper is decoded again through `BitmapRegionDecoder` at the framed
region, so zooming in does not throw away the original's detail. `CropView.normalisedCrop` returns
fractions rather than pixels, which is what decouples the two.

Eden keeps its own copy of the source picture for the same reason it keeps the transcoded video: a
content URI belongs to another app and can be revoked. It also keeps a tile-sized copy of what it
applied, because reading the wallpaper back out of the system needs a storage permission a launcher
has no business holding.

## Video wallpapers

`media/` converts a picked video with `MediaCodec`: extractor to decoder, decoder to an external
texture, texture drawn into the encoder's input surface (which is where the scale to 1080p happens
for free), encoder to muxer. Frames are dropped before the GPU touches them, so reducing to 24fps
makes the conversion faster as well as smaller. Audio is copied through the muxer untouched.

`VideoTranscoder` is an interface with one implementation on purpose. Bundling FFmpeg would have
meant a GPL relicence of an MIT app, tens of megabytes on a launcher aimed at 4 GB phones, and a
dependency retired in January 2025. A second backend can be added behind the interface later.

## Performance notes

- Nothing allocates in `onTouchEvent`, `onLayout`, `dispatchDraw`, or `computeScroll`. Scratch
  `IntArray`s and `Rect`s are fields.
- `PagedView.dispatchDraw` draws only the pages intersecting the viewport.
- `IconCache` is an `LruCache` bounded in bytes, not entries. No disk cache: on a 4 GB phone it
  mostly buys a slower cold start and a second thing to keep coherent.
- The vertical drawer recycles. The horizontal one rebuilds pages on filter change - that cost is
  paid on a keystroke, not on a fling.

## What is deliberately missing

| Missing | Why |
|---------|-----|
| Widget hosting | Needs an `AppWidgetHost`, the bind-permission flow, and resize frames. The model, columns, and a placeholder tile are in place so stored widgets survive. |
| Icon shapes | Packs supply their own masks; a launcher-wide shape override on top of that is a fight between two opinions and needs a design first. |
| Page reorder by dragging cards | Overview manages pages by badge; dragging cards to reorder is not wired yet. |
| Overview zoom and free-scroll in `PagedView` | AOSP's oversized 2x measurement existed to support them; dropping both made measurement simpler and cheaper. |
| Icon disk cache, app prediction, Quickstep | Later phases, or never. |

## Testing on a device

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.auriel.edenlauncher/.Launcher
adb logcat -s Launcher:D Workspace:D LauncherModel:W
```

Synthetic long presses need `adb shell input swipe X Y X Y 1200` - `input motionevent DOWN`/`UP`
does not trip the long-press timer.

## Reference sources

`.ref/aosp7`, `.ref/aosp8`, `.ref/aosp10` are unmodified AOSP Launcher3 checkouts, gitignored and
never compiled. Ported files name their origin in their KDoc; `NOTICE` lists them.

The wallpapers are not in those checkouts and never were - Launcher3 is one repository and the
wallpapers are nine others (`packages/wallpapers/*`). `Basic` on `main` retains only polarclock and
walkaround; fall, galaxy, grass, and nexus survive on `gingerbread-release`. All of them are
RenderScript, which the Android Gradle plugin removed in 9.0, so they are reference material for
composition and palette only and cannot be built.
