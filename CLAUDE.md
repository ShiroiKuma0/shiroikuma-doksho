# CLAUDE.md — shiroikuma-doksho

**白い熊 読書** — 白い熊's fork of [Librera Reader](https://github.com/foobnix/LibreraReader) (GPL-3.0),
the MuPDF-based Android reader (PDF, EPUB, MOBI/AZW, DjVu, FB2, CBZ/CBR, DOC/DOCX, RTF, TXT, OPDS).
Package **`shiroikuma.doksho`**, installable side-by-side with Librera, Librera PRO and Librera F-Droid.

Why this fork exists: to make Librera the PDF reader and writer 白い熊 wants on the Huawei Mate XT —
first **search you can step through** (next / previous hit with a counter, not only a page grid with
highlights) and **writing on the PDF with a stylus** (real ink annotations saved into the file). Both
start from what upstream already has: `PageSearcher` reports every hit, `DrawView` + MuPDF's
`addInkAnnotationInternal` write ink.

## Read this first

- **`.claude/skills/build-apk/SKILL.md`** — identity, the native + Gradle build, signing, versioning.
- **`.claude/skills/upstream-new-version/SKILL.md`** — the proceed-gated upstream sync.

## Branch & remote model (same as the sister forks)

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Mirrors upstream's latest **release tag** (`9.6.25`, …). No fork work here. | reset to each new tag |
| `custom` | All our work, rebased onto `master`; the GitHub default branch. | rebased each sync |

- `origin` = `git@github.com:ShiroiKuma0/shiroikuma-doksho.git` (ssh, push here).
- `upstream` = `https://github.com/foobnix/LibreraReader.git` (https, **fetch only** — push URL `DISABLED`).
- Upstream tags every release with the bare version (`9.6.17`, `9.6.25` — no `v`). We base on the
  **latest tag**, never on bleeding `upstream/master` (upstream pushes several commits a day).
- **Never rename the code namespaces** (`com.foobnix.pdf.info`, `com.foobnix.*`, `org.ebookdroid.*`).
  Only the installed `applicationId` differs. Renaming would make every rebase a mass-conflict.

## Identity

| What | Value | Where |
| --- | --- | --- |
| applicationId | `shiroikuma.doksho` | `shiroikuma/fork.gradle` → flavour `doksho` |
| App label | `白い熊 読書` | `shiroikuma/fork.gradle` → `manifestPlaceholders.appName` |
| Our settings page | **`白い熊 読書 UI`** — holds every configurable item of our changes (spec to come from 白い熊) | not built yet |
| Flavour | `doksho` — Google-free (F-Droid stubs in `app/src/doksho/java`), real junrar (CBR), `vmSafeMode=false`, arm64-v8a only | `shiroikuma/fork.gradle` |
| `LibreraBuildConfig.FLAVOR` | `"doksho"` → `AppsConfig.IS_FDROID` true (Google-free paths), `AppsConfig.IS_RAR` true | `app/src/doksho/java/com/foobnix/LibreraBuildConfig.java`, `AppsConfig.java` |
| Launcher icon | Librera PRO's book traced as yellow line-art on black, 読 in place of Librera's "L" swash (confirmed by 白い熊 2026-09-25) | source `shiroikuma/icon/doksho-icon.svg` ← `trace-icon.py`; resources ← `gen-icons.py` into `app/src/doksho/res` |
| App data | the app's own directory (`/sdcard/Android/data/shiroikuma.doksho/files`), **never** `/sdcard/Librera` or `Download/Librera` (白い熊 2026-09-25); default profile `白い熊 読書` | `AppProfile.DATA_DIR` / `DATA_NAME`, `AppSP.getRootDir` |
| Keystore | `~/.android-keystores/shiroikuma-doksho.jks`, alias `doksho` | `keystore.properties` (gitignored) |

## Build (summary — details in `build-apk`)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk
shiroikuma/build-mupdf.sh             # native lib → app/src/main/jniLibs/arm64-v8a (once per MuPDF / JNI change)
./gradlew buildFork < /dev/null       # signed APK → ~/tmp/shiroikuma-doksho_<version>_arm64-v8a.apk, bumps the counter
```

- **Version:** `versionName = "<upstream release>+<BUILD_NUMBER padded to 3>"` (e.g. `9.6.25+001`),
  `versionCode = <upstream code> * 10000 + BUILD_NUMBER` (`7338` → `73380001`). The upstream pair is
  `FDroidVersionNumber` / `FDroidCodeNumber` in `app/build.gradle` — the one that matches the tag.
  `BUILD_NUMBER` lives in **`shiroikuma/fork.properties`**, is bumped by every `buildFork`, and resets
  to `1` on each new upstream release. Every build gets a new number; a build is never overwritten.
- APK: `~/tmp/shiroikuma-doksho_<versionName>_arm64-v8a.apk`; delivered via the global `/after-build`.

## The fork layer — where our changes live

Keep it a **small, legible layer** so rebases stay cheap:

- `shiroikuma/` — everything that is only ours: `fork.gradle` (flavour, signing, version, `buildFork`),
  `fork.properties` (counter), `build-mupdf.sh` (native build).
- `app/src/doksho/` — the flavour source set: `LibreraBuildConfig`, the Google-API stubs copied from
  `app/src/fdroid/java` (without the junrar stubs). If upstream's main code starts using a Google API
  the stubs lack, the compile fails in `app/src/doksho/java` — re-copy the stub from `app/src/fdroid/java`.
- `app/src/main/java/shiroikuma/doksho/Doksho.java` — our name and GitHub URLs (site, Help, releases, issues).
- **De-branding without touching upstream's resources** — flavour resources win over `main` by name:
  - `shiroikuma/debrand.py` generates `app/src/doksho/res/values*/strings_doksho.xml` (every string
    naming Librera, in all locales, with `白い熊 読書`; LibreraX kept) and
    `app/src/doksho/assets/licenses.html` (our header on top, crediting Librera Reader). **Re-run it
    after every upstream sync** and commit the output.
  - `shiroikuma/icon/gen-icons.py` writes the icon overrides: `mipmap-anydpi-v26/icon_pdf_pro.xml`
    (launcher, splash, TTS notification layouts, file info, popups all use it),
    `mipmap-xxhdpi/icon_pdf_pro.png`, `drawable-xxhdpi/icon_pro_square.png`,
    `drawable/ic_notification_librera.xml`, the foreground / monochrome vectors.
  - `app/src/doksho/res/values/config_doksho.xml` — `my_site`, `wiki_url`, `my_email` (empty), `app_name_pro`.
- Upstream files we patch (keep this list current):
  - `app/build.gradle` — one line: `apply from: "$rootDir/shiroikuma/fork.gradle"`, before `android {`.
  - `AppsConfig.java` — `doksho` joins `IS_FDROID`; `IS_DOKSHO`; `IS_RAR`.
  - `ExtUtils.java`, `org/ebookdroid/BookType.java` — CBR gated on `IS_RAR`; LibreraX mode hidden (`ExtUtils`).
  - `model/AppProfile.java` (`DATA_DIR`, `DATA_NAME`, `initDataDir`), `model/AppSP.java` (root dir,
    default profile, no Demo fallback), `LibreraApp.java` (`initDataDir`), `pdf/info/model/BookCSS.java`
    (cache / TTS / backup / cloud / OPDS-download paths), `pdf/info/Clouds.java` (cloud folder names),
    `pdf/info/ExportConverter.java`, `ui2/fragment/BrowseFragment2.java` (shortcut names) — app data.
  - `ui2/fragment/PrefFragment2.java` — About: our GitHub as site and Help, releases as "What's new",
    e-mail and PRO rows hidden, LibreraX reading mode dropped; `pdf/info/Urls.java` — rate → our GitHub;
    `pdf/info/widget/ShareDialog.java` — LibreraX entry hidden; `pdf/info/view/confline/ConfLineView.java`
    — null options skipped.
  - `README.md` — ours (upstream edits theirs every release: on a rebase conflict keep ours).
  - `.gitignore` — our block at the end.

## Changelog

Upstream keeps a `CHANGELOG.md` (often lagging behind its tags; the GitHub release bodies fill the gap).
**Every release publishes a merged changelog**: our block at the very top (newest first, each entry
naming the upstream release it is built on), upstream's file untouched beneath it. The upstream notes of
each synced release are folded in by `upstream-new-version`; `/publish-version` (global) publishes it.

## Working rules (override harness defaults where noted)

- **No `Co-Authored-By: Claude` / "Generated with Claude" trailer** in commits or PR bodies — end the
  message at the last line of the body. (Global rule, `~/.claude/CLAUDE.md`.)
- **Never commit or push until 白い熊 says "Push".** "Push" = commit + `git push origin custom` (and
  `master` after a sync; `--force-with-lease` for `custom` after a rebase).
- **Build when a change is finished** (global `/after-build` standing authorization) and deliver via
  `/after-build` — never ask how to transfer. Never `adb install` / `adb uninstall`; 白い熊 installs
  from `/sdcard/tmp/`. `adb` always unsandboxed.
- Git, `gh`, Gradle and keystore commands run with the sandbox disabled (writes under `~/git`, `~/.ssh`).
- Never delete or overwrite a built APK (global rule). `buildFork` refuses to overwrite one.
- Device: Huawei Mate XT tri-fold — see the global `mate-xt-folded-screen` skill for its geometry quirks.
