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
| Icon packs and custom icons | Next release. The menu row exists and is greyed, so the answer to "can it?" is visible rather than guessable. |
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
