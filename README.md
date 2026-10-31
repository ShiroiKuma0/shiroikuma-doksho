<div align="center">

<img src="shiroikuma/icon/doksho-icon-512.png" width="120" alt="白い熊 読書 icon" />

# 白い熊 読書

**A black-and-yellow PDF and e-book reader, tuned down to the last pixel.**

A fork of [Librera Reader](https://github.com/foobnix/LibreraReader) with **major additions**: PDF
editing — forms filled in any font, text boxes, pen and eraser, saved into the original file — a
yellow-on-black night mode for PDFs, one settings page for the whole look of the app, an app-wide
black-yellow skin, category Export / Import, and headless backup automation — on a Google-free build.

Installs **side-by-side** with Librera, Librera PRO and Librera F-Droid (app id `shiroikuma.doksho`).

**📥 Latest release: [`10.0.19+010`](https://github.com/ShiroiKuma0/shiroikuma-doksho/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-doksho/releases)

</div>

---

## ✍️ Write on the PDF itself

The reader's edit button opens a small toolbar over the page: **fill in forms**, **text boxes**,
**pen** and **eraser**, with undo and redo. A long-press on a tool opens its options: colour,
thickness and opacity for the pen, size and what it erases for the eraser, font, size and colour
for text. Text boxes and form fields take **any TrueType or OpenType font** — your own, Android's, or the three
every viewer has. The font goes into the PDF cut down to just the characters used, Japanese
included. While you type, the text is drawn over the field at its real size and zoom, so you see
exactly how it sits among the print. A text box's editor docks beside it with arrows that nudge
it point by point and its X / Y in points, so the lines of a form without fields line up in a
column. Everything is real PDF content: form values, annotations and
ink, readable in any viewer.

---

## 💾 Saved where the file lives

Closing an edited book asks **Save**, **Save as…** or **Don't save**. Save writes into the original
file, even one handed over by another app; Save as keeps the original and opens in its directory.
Saving appends the changes to the file instead of rewriting it under the open book. Books sent
from 書類管理 or any file manager open as the real file, not as a hidden copy.

---

## 🌙 Night mode that turns print into yellow on black

The reader's night mode is a duotone: every pixel of a PDF or DjVu page is placed by its darkness
between night paper and night ink — black print becomes full yellow, white paper full black, and
every grey a smooth step between. A black-and-white book reads as pure yellow on black; a strength
slider lets colour pictures keep some of their own hues.

---

## 🎞️ Pages that roll by like film

PDFs open in Vertical (scroll) mode: the page slides up while the next one rises from below, and a
long flick rolls through page after page without stopping. Black around and between the pages instead
of Librera's light grey. Zoomed in, a sideways swipe pans only as far as the page's own edges. Prefer
Paged? There, up / down scrolls to the page's edge and pulling on turns the page, and a zoomed page
pans sideways instead of flipping.

---

## 🎛️ 白い熊 読書 UI — one page for the whole look

Long-press the Preferences cog or either hamburger and the fork's own settings page opens: colours
of screens, bars, dialogs and menus; border widths and corner roundness down to zero; interface and
heading fonts — including your own imported .ttf / .otf — each shown in its own glyphs. Every group
ends in a live preview, and the colour picker remembers the colours you chose before.

---

## 🖤 The whole app in black and yellow

Library, settings, reader bars, all of Librera's dialogs and popup menus are repainted from that
page: pure black grounds, yellow text, icons and borders by default. Book covers, page images and
meaningful colours are left exactly as they are.

---

## 📦 Export / Import and 保存復元 automation

One `.zip` carries the UI page, the reading settings and the whole library (bookmarks, progress,
recent, favourites, tags, playlists) — pick the categories, restore just those. Sister apps
(白い熊 応用管理 · 白い熊 自由作業盤) can run the same backup headlessly.

---

## 🔒 Google-free, and nothing on your shared storage

No Google services, ads or analytics, and no LibreraX tie-ins; one engine, MuPDF, for every
PDF and e-book (upstream's PDFium and foliate-js engines are left out); CBR comics still open. Settings, profiles, caches and
backups live in the app's own directory — nothing is written to `/sdcard/Librera` or
`Download/Librera`. It asks for *All files access* on every open until it has
it, so any book on the phone — including ones handed over by a file manager — opens. Full speed (no `vmSafeMode`), arm64.

---

## Built on Librera Reader

A fork of [Librera Reader](https://github.com/foobnix/LibreraReader) by Ivan Ivanenko (app id
`shiroikuma.doksho`, so it coexists with the official builds). Every reading feature — PDF, EPUB,
MOBI/AZW, DjVu, FB2, CBZ/CBR, DOC/DOCX, RTF, OPDS, dictionaries, text-to-speech — is Librera's;
this fork adds to them. Current base: Librera Reader **10.0.19**. The code remains under the GNU GPL v3.0.

## Building

```bash
git clone -b custom https://github.com/ShiroiKuma0/shiroikuma-doksho.git && cd shiroikuma-doksho
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/android-sdk
shiroikuma/build-mupdf.sh            # native libraries (MuPDF & co.), arm64-v8a (needs the Android NDK)
./gradlew assembleDokshoRelease      # signing: keystore.properties, see .claude/skills/build-apk
```
