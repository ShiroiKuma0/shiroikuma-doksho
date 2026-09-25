<div align="center">

<img src="shiroikuma/icon/doksho-icon-512.png" width="120" alt="白い熊 読書 icon" />

# 白い熊 読書

**A PDF and e-book reader you can search through and write in.**

A fork of [Librera Reader](https://github.com/foobnix/LibreraReader) with **major additions** in the
works: step-through PDF search and stylus writing saved into the PDF — on a Google-free build.

Installs **side-by-side** with Librera, Librera PRO and Librera F-Droid (app id `shiroikuma.doksho`).

**📥 Latest release: [all releases & APK downloads »](https://github.com/ShiroiKuma0/shiroikuma-doksho/releases)**

</div>

---

## 📚 Every format Librera reads

PDF, EPUB, MOBI / AZW / AZW3, DjVu, FB2, CBZ **and CBR**, DOC / DOCX, RTF, HTML, TXT and OPDS
catalogues — on the MuPDF engine, with Librera's library, bookmarks, notes, dictionaries and
text-to-speech.

---

## 🔒 Google-free, and nothing on your shared storage

No Google services, no ads, no analytics. Settings, profiles, bookmarks, caches and backups live in
the app's own directory — nothing is written to `/sdcard/Librera` or `Download/Librera`. Runs at
full speed (no `vmSafeMode`), arm64.

---

## 🔍 Coming next

- **Search you can step through** — next / previous hit with a counter, not only a grid of pages.
- **Write on the PDF with a stylus** — ink saved into the file as real PDF annotations.
- **白い熊 読書 UI** — one settings page for everything this fork changes.

---

## Built on Librera Reader

A fork of [Librera Reader](https://github.com/foobnix/LibreraReader) by Ivan Ivanenko (app id
`shiroikuma.doksho`, so it coexists with the official builds). All the reading features are
Librera's; this fork adds to them. The code remains under the GNU GPL v3.0.

## Building

```bash
git clone -b custom https://github.com/ShiroiKuma0/shiroikuma-doksho.git && cd shiroikuma-doksho
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=$HOME/android-sdk
shiroikuma/build-mupdf.sh            # native MuPDF library, arm64-v8a (needs the Android NDK)
./gradlew assembleDokshoRelease      # signing: keystore.properties, see .claude/skills/build-apk
```
