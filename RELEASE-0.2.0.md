# Eden 0.2.0

First public build. A Kotlin rewrite of AOSP Launcher3 for Android 10 and up, built around the
idea that the launcher should ask rather than assume.

It is usable as a daily driver if you do not need widgets.

## What works

**Home screen**
- Swipeable pages with AOSP Launcher3's paging feel - the fling thresholds, the significant-move
  threshold, and the velocity-scaled snap duration are ported exactly
- Long-press empty space to zoom out into overview and manage pages as cards
- **Pick any page as your home page** with the house badge in overview. Keep the middle one as home
  and have pages on both sides. Back and HOME both return to it
- **Empty pages stay empty.** Eden does not delete a page just because you moved the last icon off
  it
- Add and remove pages from overview; page order and empty pages persist across reboots

**App drawer**
- Swipe up to open
- Two navigation styles, switchable in settings: continuous vertical grid, or fixed horizontal
  pages
- Search matches anywhere in the name, so "tube" finds "YouTube"
- Background opacity from 0 to 100%. The home screen fades out behind it, so a transparent drawer
  shows your wallpaper rather than your dock

**Drag and drop**
- Drag icons between pages and the dock, or onto the trailing empty page to start a new one
- Drop an icon on another icon to make a folder; open it, rename it, add and remove. A folder that
  loses its last item deletes itself
- Drag to the Remove bar at the top to take an icon off the home screen
- Drag to a screen edge to page across

**Settings** (Eden's own - no reliance on your OEM's Settings app)
- Drawer navigation style and background opacity
- Drawer columns, home grid columns and rows, dock icon count - each Auto or 3 to 8
- Icon size 60-140%, vertical and horizontal spacing, label gap

**Other**
- Accepts "add to home screen" requests from other apps
- Deep shortcuts launch correctly
- Work profile apps launch under the right user

## What is missing

- **Widgets are not hosted.** Widget rows and their ids are stored and shown as a placeholder tile,
  so nothing is lost, but they do not render yet. Widget pin requests are declined rather than
  half-accepted
- Live wallpaper support is the next phase
- Pages cannot yet be reordered by dragging cards in overview; use the badges
- No icon packs, no gestures beyond swipe-up, no notification dots

## Install

Download `app-release.apk` and sideload it. You will need to allow installs from your browser or
file manager, then pick Eden the next time you press HOME.

**This build is signed with a throwaway test key**, not a real release key. That means:

- Your device may warn about the installer source. That is expected
- A future release signed with a different key will not install over this one; you would have to
  uninstall first, which clears your layout

If that matters to you, wait for a build with a stable key.

## Compatibility

| | |
|---|---|
| Minimum | Android 10 (API 29) |
| Tested on | Galaxy M31, Android 16 (API 36) |
| Size | 635 KB |
| ABIs | All - there is no native code in Eden, so one APK covers every device |
| Permissions | Query installed packages (to build the drawer), set wallpaper, expand status bar |

## Licence

Eden is MIT. Parts are ported from AOSP Launcher3 and remain Apache 2.0; `NOTICE` names each file
and the class it came from. Eden shares no source files or git history with Launcher3, but it is a
derivative work and says so.

Bugs, ideas, disagreements: debadityamalakar@gmail.com
