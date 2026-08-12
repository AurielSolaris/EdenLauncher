# Eden 0.5.0

Widgets, at last - plus a switch for people who want less launcher rather than more, and a run of
fixes for things that were quietly wrong.

`versionCode 5`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

---

## Widgets

Deferred since Phase 2 and now hosted properly.

- **Every widget your phone provides**, grouped by the app it came from, each with a preview.
- **Sizes in cells of your grid**, not an assumed one. A provider only states its size in cells
  since Android 12; before that it states pixels, and Eden works back from that against the columns
  and rows *you* chose - so the same widget is 4x1 on one grid and 3x1 on another.
- **Anything that cannot fit is shown but not offered.** The alternative is walking you through a
  permission prompt and then admitting there was never anywhere to put it.
- **Resize by dragging the handles**, snapped to whole cells, and never over a neighbouring icon.
- **Drag them between pages** like anything else, and remove them the same way.

Widgets you placed before this release come back bound: the database has carried the columns for
them since the very first version.

## Simple mode

One switch in settings turns Eden back into what it was at v0.2.0 - a home screen, a drawer,
wallpapers, and the settings for those. Icon packs, per-app icons, the long-press icon menu and the
way in to widgets are put away.

**Nothing is deleted.** Your renames, your chosen icons, your icon pack and your placed widgets all
stay exactly where they are and come straight back when you switch it off.

Two things it deliberately does not undo, because they are fixes and not features: the drawer still
notices apps being installed and removed, and the log is still reachable. A simpler launcher should
not also be a more broken one, or one you cannot file a bug against.

## The home screen stops rotating

Locked to your device's natural orientation. A launcher that swings sideways because you put the
phone down at an angle is rarely what anyone wanted. Apps you open still rotate exactly as they did,
and there is a setting if you want the old behaviour back.

## Deleting a page asks first

The X on an overview card used to destroy a page and everything on it on one tap. It now says how
many items are about to go and waits for an answer. An empty page still goes straight away - there
is nothing to lose and asking would be noise.

## Fixes

- **Apps in an open folder have their names back.** The folder cell was being sized square from the
  workspace cell's width, which left no room for the label underneath. An open folder also stops
  opening at full height for three apps.
- **No more blank tiles in the icon browser.** Names a pack lists but does not ship are left out
  rather than offered - picking one used to set an *empty* icon on an app. Packs that keep their
  icons under `mipmap` rather than `drawable` now work at all, where before they showed nothing.
- **Page dots are tappable.** Tap one to go to that page, on the home screen and in the paged
  drawer.
- **Pull the drawer down to close it.** From the top of the list, drag down and let go - the same
  gesture that opened it, run backwards. A fast fling up the list will not trigger it.
- **Deleting a page no longer strands folder contents.** It used to remove the folder's own entry
  but leave everything that was inside it in the database forever, invisible. Existing strays are
  cleared automatically on the next launch.

## New

- **An About screen**, in Settings, with the version to quote in a bug report and what Eden owes to
  AOSP.

---

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
