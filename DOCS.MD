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
`Launcher.onBackRequested` decides the order: cancel a drag, else leave overview, else close the
drawer, else close a folder. When a fifth state appears, that is the moment to build one.

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
