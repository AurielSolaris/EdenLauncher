# Eden 0.5.1

The first release driven by a bug report rather than a plan. Icon packs stop making the launcher
sit still on startup, folders stop being one-way, and the home page you picked is the one you
actually land on.

`versionCode 6`, `minSdk 29`, `targetSdk 36`. No native code, no network code.

---

## Icon packs no longer make you wait

An app's own icon is cheap to load. A themed one is not: the pack's mapping file has to be parsed,
and every app the pack has no entry for gets a background, a mask and an overlay composed under and
over it. Eden used to do all of that before it would show you anything, so choosing a pack meant
choosing a launcher that paused on every start.

Now the home screen loads with the apps' own icons and the pack arrives on top of it, a few icons
at a time, starting with the ones you are looking at. The launcher is usable immediately and the
theming lands over it in waves.

Nothing about the result changes - the same packs, the same composition, the same rule that an icon
you picked by hand outranks the pack. Only the order changed. Switching packs in settings is faster
too: your apps' own icons are no longer thrown away and read back off the system to find out they
did not change.

If you use no icon pack and have set no icons of your own, none of this machinery runs at all.

## Folders open both ways

Apps could be dropped into a folder and there was no gesture that took them out again. Holding an
icon inside an open folder now does what holding one anywhere else does:

- **Move to drag it out** - onto a page, into the dock, or onto Remove or Uninstall.
- **Hold still for the menu** - rename it, change its icon, open its app settings, uninstall it, or
  **take it out of this folder**, which puts it back on the page the folder is on.

A folder emptied this way deletes itself, the same as before.

## Tap beside a folder to close it

Back still closes an open folder. This is a second way, on by default, and there is a switch under
Settings, Home screen if you would rather keep it off - while a folder is open, that tap closes the
folder instead of reaching the icon behind it.

Tapping the folder's own edge no longer launches whatever happened to be underneath, either.

## Fixes

- **The home page you chose is the one you get.** Setting a page as home in overview stored the
  choice correctly, but the launcher asked which page that was before it had loaded any pages - so
  the answer was always "the first one". Every cold start opened on the leftmost page whatever you
  had picked.
- **The house badge remembers.** Re-opening overview cleared the mark from the page you had chosen,
  which made a setting that had worked look like one that had not.
- **Dropping an icon where you picked it up leaves it there.** It used to shove the icon one cell
  along, because the cell it came from still counted as taken by the icon being carried out of it.
- **You can see where an icon is going to land.** The target cell is marked while you drag, and
  marked differently when letting go would make a folder instead.

---

Thanks to Voidz for the report that most of this came out of.

Bugs, ideas, disagreements: **debadityamalakar@gmail.com**

Attach the log if something went wrong - Settings, Troubleshooting, Open the log, Share. It stays
on the device until you do.
