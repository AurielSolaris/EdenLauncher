# Eden 0.4.0

Icons are yours now. So is the wallpaper crop. And the drawer finally notices when you install
something.

Still a pre-release. It is a daily driver if you do not need widgets.

## Icon packs

Any icon pack you have installed, chosen from a dropdown in Settings. Eden reads the pack the way
every other launcher does - `appfilter.xml` out of the pack's own APK - so packs written for Nova,
ADW, Go, Apex, or Lawnchair work here without being written for Eden.

There is no Android API for icon packs and never has been. They are a convention that grew out of
ADW fifteen years ago, and a pack announces itself with whichever theme action its author's
launcher of choice used. Eden checks all seven of the common ones, because checking only the
popular one silently loses packs you can see are installed.

- Packs that ship a background, mask, and overlay use them on apps they have no entry for, so a
  themed screen is themed all the way rather than two thirds of the way.
- Packs that do not ship those leave uncovered apps alone - which is where the next feature
  comes in.
- Switching packs takes effect when you return to the home screen. It does not wait for the
  launcher to be killed.

## Any icon, for any app

Long-press an icon, **Change the icon**:

- **Choose from the icon pack** - the whole pack, in a searchable grid. This is the answer to an
  app your pack's author never heard of: go and find something in the pack that will do. Nova,
  Niagara, and Smart Launcher all have this, and a pack without it covers your popular apps and
  abandons the rest.
- **Choose a picture** - any image on the device. It is centre-cropped to a square and scaled to
  your grid's icon size, so a 16:9 photo loses its edges rather than its proportions.
- **Use the default icon** - always there once you have overridden one.

A picked icon beats the pack, and the pack beats the app's own. That order is the only one that
makes sense: choosing an icon for one app is a more specific instruction than choosing a pack for
all of them, and it should not quietly stop working because you changed packs.

## Wallpapers you position yourself

Setting a photo as the wallpaper used to hand it to the system and let it decide the crop. On a
tall screen it decides badly, and there is no arguing with it afterwards.

Now there is a framing screen. The bright rectangle is the real shape of your display and exactly
what will be visible; the dimmed surround is what gets cut. Drag to move, pinch to zoom, and the
picture cannot be pulled far enough to leave a blank edge.

- **Home screen, lock screen, or both** - asked before it is set, because Android keeps them
  separate and people use them differently.
- **Reframe without choosing again.** Eden keeps its own copy of the picture, so next week you can
  adjust the crop without going back to the gallery to find the photo.
- **Your picture gets a tile**, like every live wallpaper does, so the picker shows what is set
  rather than a bare button. Reading the wallpaper back out of the system needs a storage
  permission a launcher has no business holding, so Eden remembers what it set instead.

Two resolutions are in play on purpose: what you drag around is a downsampled copy so panning stays
smooth on a slow phone, and what gets set is decoded again from the original at the region you
framed, so zooming in does not cost you the picture's detail.

## The drawer keeps up

Install an app and it appears. Uninstall one and it goes, along with its home screen icon and, if
that empties a folder, the folder. Update one and its new icon shows.

Previously the drawer showed the app list as it stood when the launcher was last started, so a
newly installed app was missing until the launcher happened to be killed. Package changes arrive in
bursts - a restore, an install that pulls in three more - so the reload is coalesced: one rebuild
for the burst, and never one under your finger mid-drag.

An app that vanishes because its storage was unmounted is treated differently from one that was
uninstalled. Its icons stay put and come back with the card.

## Page dots in the paged drawer

The horizontal drawer had no way to tell how many pages there were or which one you were on until
you hit the end. It has the same dots the home screen does now. Vertical mode does not, because
there is nothing to count.

## A log you can actually read

**Settings -> Troubleshooting -> Open the log.** The last 48 hours of what Eden did, on screen,
with Share and Clear.

logcat is the obvious answer and it is not good enough. A launcher crash takes the home screen with
it, so your next move is a reboot, and a reboot is exactly what clears logcat. This writes the same
lines somewhere they survive, throws them away on a timer so it can never fill your storage, and
records the stack trace of a crash before the process dies.

Nothing leaves the phone unless you press Share.

## Also

- Settings dropdowns have an accent border, so a control does not read as a heading.
- Uninstall works from the icon menu and from the drop target. It was silently doing nothing in
  0.3.0: Android has required `REQUEST_DELETE_PACKAGES` to launch the uninstaller since Android 8,
  and without it the intent is dropped with no exception and no log entry.

## What is still missing

- **Widgets are not hosted.** Rows and ids are stored and shown as a placeholder tile, so nothing
  is lost.
- Icon shapes and adaptive-icon masking of your own.
- Pages cannot be reordered by dragging cards in overview; use the badges.
- No gestures beyond swipe-up, no notification dots.

## Install

Download `app-release.apk` and sideload it. Allow installs from your browser or file manager, then
pick Eden the next time you press HOME.

**Signed with a throwaway test key**, not a real release key:

- Your device may warn about the installer source. Expected.
- A future release signed with a different key will not install over this one without uninstalling
  first, which clears your layout.

Installing over 0.3.0 keeps your layout, your wallpaper, and your renames.

## Compatibility

| | |
|---|---|
| Minimum | Android 10 (API 29) |
| Tested on | Galaxy M31, Android 16 (API 36) |
| ABIs | All - no native code, so one APK covers every device |
| Permissions | Query installed packages, set wallpaper, expand status bar, request package deletion. Record audio only if you turn the visualiser's real-audio mode on |

Icon packs need no permission of their own. Reading another app's resources is something any app
may do; Eden only ever reads.

## Licence

Eden is MIT. Parts are ported from AOSP Launcher3 and the AOSP wallpapers and remain Apache 2.0;
`NOTICE` names each file and what it came from. Eden shares no source files or git history with
those projects, but it is a derivative work and says so.

Bugs, ideas, disagreements: debadityamalakar@gmail.com
