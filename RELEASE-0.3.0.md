# Eden 0.3.0

Live wallpapers. Eleven of them, plus your own videos, and a picker that does not lose your place.

Still a pre-release. It is a daily driver if you do not need widgets.

## The picker

Android's way of previewing a live wallpaper is to actually start it, in an activity that owns the
whole screen. Anything that steals focus while you are looking - a notification you tap, a
mis-tapped camera button, a call - drops you out with nothing selected, and on some skins drops you
out of the picker entirely. You start over.

Eden renders a frame **offscreen** and shows you a picture. Browsing costs nothing and cannot be
interrupted, because nothing is running. Only when you commit does the real thing start, through
the system's own one-tap confirmation. Then it takes you to the home screen, so you see what you
just chose.

## Eleven live wallpapers

All rewritten in OpenGL ES 2.0 from the AOSP originals, which modern Android no longer ships - and
could not ship if it wanted to. They were RenderScript, deprecated in Android 12 and removed
outright from the Android Gradle plugin in 9.0. Nothing here is copied; `NOTICE` names each
original.

| | |
|---|---|
| **Phase Beam** | Drifting beams of light over deep blue. The Android 4.x default |
| **Noise Field** | Particles carried by a slow current |
| **Galaxy** | A spiral turning above a starfield, with real differential rotation - the arms wind up |
| **Holo Spiral** | A tunnel of light spiralling away |
| **Magic Smoke** | Slow coloured smoke, curling |
| **Nexus** | Coloured pulses racing on black. **Touch it and it answers** |
| **Fall** | Leaves onto water, and the ripples where they land. Touch the water too |
| **Grass** | A meadow under **your own sky**, hour by hour. Stars only when it is actually night where you are |
| **Polar Clock** | The time, as six turning arcs |
| **Walkaround** | Tilt the phone to look around the ridges |
| **Music Visualization** | A spectrum that rises and falls |

`LivePicker` and `ImageWallpaper` are not included because neither is a wallpaper - one is the
chooser app Eden replaces, the other is the system's static-image renderer.

**Speed is adjustable**, 25% to 200%, in settings. It applies to all of them.

## Your own video as a wallpaper

Pick an MP4 and Eden converts it to 1080p at 24fps using the phone's hardware encoder, then loops
it. Sound is off by default and is a toggle in settings, not a reason to import the video again.

No FFmpeg. The maintained Android build of it was retired in January 2025, the builds that can
encode H.264 are GPL and would relicense this MIT app, and it would take the APK from under a
megabyte to tens of them - on a launcher built for 4 GB phones. The cost of that choice is real:
the platform decoder only handles what your device's codecs handle, so an exotic container may be
refused where FFmpeg would have coped.

## The microphone question

Music Visualization asks you, before it is set, how it should react:

- **Its own rhythm** - no permission, nothing to be uneasy about, and most people cannot tell.
- **Follow real audio** - needs the record-audio permission.

It is worth being precise: that permission reads the sound your phone is **playing**, not your
microphone. It cannot hear the room. But Android asks with the same dialog and lights the same
indicator, so the choice is yours to make, and declining costs you the feature rather than the
wallpaper - it falls back to its own rhythm.

## Icons answer to a long press

Hold an icon and its options appear. Move instead and it becomes the drag it always was; neither
gesture had to be given up for the other.

- **Rename** anything, on the home screen or in the drawer. Clearing the field puts the app's own
  name back. Search still matches the original name, so a rename can never lose you an app.
- **Put in a new folder** - you no longer need two icons you already want together just to make one.
- **Uninstall**, from the menu or by dragging to the top of the screen, where it now sits beside
  Remove.
- **App settings**, straight to the system page.

Icon packs are listed and greyed. They arrive in 0.4.0; a menu that silently omits them would leave
you guessing whether they exist.

## Battery

- Nothing draws while hidden.
- The launcher explicitly pauses the wallpaper behind an opaque app drawer, where the system still
  considers it visible.
- The visualiser drops its audio capture the moment it is not being watched.
- 30fps, not 60. These are slow scenes; the difference is battery.

## What is still missing

- **Widgets are not hosted.** Rows and ids are stored and shown as a placeholder tile, so nothing
  is lost.
- Icon packs and custom icons - next release.
- Pages cannot be reordered by dragging cards in overview; use the badges.
- No gestures beyond swipe-up, no notification dots.

## Install

Download `app-release.apk` and sideload it. Allow installs from your browser or file manager, then
pick Eden the next time you press HOME.

**Signed with a throwaway test key**, not a real release key:

- Your device may warn about the installer source. Expected.
- A future release signed with a different key will not install over this one without uninstalling
  first, which clears your layout.

Installing over 0.2.0 keeps your layout. Reinstalling resets a live wallpaper back to the system
default - set it again from the picker.

## Compatibility

| | |
|---|---|
| Minimum | Android 10 (API 29) |
| Tested on | Galaxy M31, Android 16 (API 36) |
| ABIs | All - no native code, so one APK covers every device |
| Permissions | Query installed packages, set wallpaper, expand status bar, request package deletion. Record audio only if you turn the visualiser's real-audio mode on |

## Licence

Eden is MIT. Parts are ported from AOSP Launcher3 and the AOSP wallpapers and remain Apache 2.0;
`NOTICE` names each file and what it came from. Eden shares no source files or git history with
those projects, but it is a derivative work and says so.

Bugs, ideas, disagreements: debadityamalakar@gmail.com
