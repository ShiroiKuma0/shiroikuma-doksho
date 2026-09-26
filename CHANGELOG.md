<!-- This file carries BOTH histories: 白い熊 読書's releases first, newest on top, then Librera
     Reader's own changelog below, untouched. -->

# 白い熊 読書 — changelog

Releases of [白い熊 読書](https://github.com/ShiroiKuma0/shiroikuma-doksho), each built on a Librera
Reader release. Librera Reader's own changelog follows unchanged below.

## 白い熊 読書 9.6.25+005 — 2026-09-26

The first release of 白い熊 読書 — built on **Librera Reader 9.6.25** (upstream tag `9.6.25`, 2026-09-19).

### Major features
- **Night mode as a yellow-on-black duotone.** Every pixel of a PDF / DjVu page is placed by its darkness between night paper (default `#000000`) and night ink (default `#FFFF00`): black print becomes full yellow, white paper full black, greys proportional — no threshold, smooth glyph edges. Works in both Vertical and Paged mode; text formats (EPUB, FB2, …) get yellow text on a black page; library covers keep their colours. **Duotone strength** 0–100 % blends it with Librera's plain inversion (0 = upstream's night mode).
- **白い熊 読書 UI** — the fork's own settings page, opened by a **long-press** on the Preferences cog, either hamburger (main screen and Library page) or the Settings tab; a tap keeps the upstream action. kxkb-style layout: bold headings underlined only as wide as their text, a thin rule between groups, deep indents per level, tight rows. Sections: Export / Import · Fork behaviour · Colours · Borders & shapes · Fonts · Reading · About; every group ends in a **live preview** and the page repaints itself as you change it.
- **App-wide black-yellow skin.** Library, settings, reader bars, all of Librera's dialogs and popup menus repainted from the page's settings: neutral text → text / secondary text, coloured text → accent (reds kept), neutral grounds and Librera's bar tint → our grounds, rounded boxes and pills → our border colour, width and corners, vector icons → accent. Book covers, page images, colour swatches, the reader's page area and web views are left alone. Master switch *白い熊 colours everywhere*; *Reset to the house defaults*.
- **Export / Import** (the family panel): SAF export directory — shown in yellow once set, red while unset, with the last export under it; one `shiroikuma-doksho_<yyyy-MM-dd_HH-mm-ss>.zip` with categories **白い熊 読書 UI** (settings + imported fonts), **Settings** (app & reading settings, web dictionaries / searches, text replacements) and **Library** (bookmarks, progress, recent, favourites, excluded, tags, playlists). Written atomically (`.part` → rename). Success: a bordered info dialog whose OK closes the dialog, the panel and the page; import ends in *Later* / *Restart now*; failures leave the panel open. The app-lock password is never exported.
- **保存復元 automation** (sister-app contract v2): `shiroikuma.doksho.action.EXPORT_STATE` / `LIST_CATEGORIES` / `CANCEL_EXPORT`, the `shiroikuma.doksho.automation` data door (describe / export / import / cancel, callers pinned by package and signing certificate), a dataSync foreground service with progress and heartbeat; *Automation export* (on), *Use authorization token?* (off) and the token row on the page.

### UI & theming
- Colour picker: one-click swatches of the colours chosen before, a preview labelled `#AARRGGBB`, A / R / G / B sliders; live, Cancel reverts.
- Colours for screens (background, text, secondary text, accent, box border), bars & tabs (ground, ink), dialogs & menus (ground, text, border).
- Border width 0–8 dp and corner roundness 0–40 dp for boxes & buttons and for dialogs & menus.
- Fonts for interface text (font, weight 100–900, size 70–160 %) and the page's headings; each font shown in its own glyphs; import of .ttf / .otf / .ttc from anywhere (copied into the app, carried by the export).
- Launcher icon: Librera PRO's book traced as yellow line-art on black, with 読 on the left page; the same icon on the splash, notifications, file info and menus.

### Branding & behaviour
- App id `shiroikuma.doksho`, label 白い熊 読書, installs next to every Librera.
- Every mention of Librera in all 44 translated languages reads 白い熊 読書; website, Help, rate and "What's new" point to this repository; upstream's e-mail, Telegram and PRO upsell rows removed; the licences page credits Librera Reader.
- LibreraX reading mode removed (upstream's separate closed-source app).
- All app data — profiles, settings, cache, TTS, backups, cloud copies, OPDS downloads — lives in the app's own directory; nothing is written to `/sdcard/Librera` or `Download/Librera`.

### Packaging
- Google-free build (F-Droid stubs), but with the real RAR library, so **CBR comics open**.
- No `vmSafeMode` (upstream's F-Droid build runs with the JIT off).
- arm64-v8a only; native MuPDF 1.28.4 built from source.
- Version `9.6.25+NNN`, versionCode `7338 × 10000 + NNN`; signed with the fork's own key.

### Upstream in this base (not in upstream's CHANGELOG.md yet)
- **9.6.25** (2026-09-19): crash fixes; cover radius 0–5 (0 returns to the old UI style).
- **9.6.17** (2026-09-16): crash fix; LibreraX integration for opening books (removed in this fork).

---

# Changelog

All notable changes to Librera Reader.

Release builds: https://github.com/foobnix/LibreraReader/releases · [Unreleased APK Direct Download](http://beta.librera.mobi)

## 9.6.7

* New design: floating rounded tabs, round buttons, rounded dialogs and menus, new theme colours.
* Redesigned settings, book info and TTS dialogs.
* Library button and one-tap Vertical/Paged switch in the reader.
* BBCode formatting in books.
* Bookmarks and reading position stay on the right page after font changes and sync.
* Favourites use a heart.

## 9.5.7 (2026-09-02)

**Android Auto**

* Librera now appears as a media app in Android Auto
* Browse recent books in the car, with covers, titles and authors
* Selecting a book resumes it at its saved position
* Playback controls work from the car, the lock screen and Bluetooth

**Text-to-speech**

* Uses Android's standard media notification, with cover art, a progress bar and lock screen controls
* Fixed no speech when the system default engine points at an uninstalled package
* Fixed the notification disappearing, or staying stuck on "please wait", after a long time in the background
* Fixed audio focus not being released on pause, which left other apps muted
* Fixed a crash when requesting the notification permission from the TTS controls

**Reading**

* Upgraded the MuPDF engine to 1.28.3 (Librera patches ported from 1.23.7)
* Text selection now works while "crop white space" is enabled, in both book and scroll modes
* Reading direction (RTL) can now be set in scroll mode, not only in book mode
* Annotating is allowed while crop is enabled
* Fixed reflow when toggling "crop white space"
* Book mode: the clock/battery ticker stops when the status bar is hidden
* New "Show progress slider" setting to toggle the seek bar row
* Improved jump history and "Back" arrow visibility when using the slider
* More consistent brightness values when adjusting by scroll
* Fixes for formatted .txt files

**Google Drive sync**

* Much faster: smaller listing requests, parallel downloads and a change check that skips work when nothing changed
* Deleting a book now propagates: it no longer comes back from another device
* Fixed downloads failing into folders that did not exist locally
* Fixed a sync that could stop early on files without a size
* Temporary download files are no longer uploaded to Drive

## 9.4.21 (2026-07-21)

* Fixed text replacement for multiple words
* Updated the UI for changing text and background colors
* Added an alert for permanent file deletion
* Clicking on File Information metadata now navigates to the library
* Added the "iw" translation and fixed other translations
* Fixed the grid view widget
* Fixed the eye reset timer

## 9.4.8 (2026-05-21)

* All-storage access is optional
* Apps can use the system file manager to open individual files
* Fixed choose profile

## 9.3.75 (2026-04-24)

* Fixed contrast and brightness
* Advanced option to enable contrast and brightness for all reading modes
* Fixed Chinese language
* Fixed search in many PDF and EPUB books

## 9.3.63 (2026-03-02)

* Improvements
* Fixes

## 9.3.55 (2026-01-29)

* Fixes
* Librera for macOS, supports PDF, EPUB, FB2, CBZ, CBR (beta.librera.mobi for downloads)

Older releases: https://github.com/foobnix/LibreraReader/releases
