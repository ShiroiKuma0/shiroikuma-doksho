---
name: build-apk
description: Build the signed release APK of shiroikuma-doksho (白い熊 読書 — 白い熊's fork of foobnix/LibreraReader, the MuPDF-based PDF / e-book reader, app id shiroikuma.doksho) — the native MuPDF library via shiroikuma/build-mupdf.sh when needed, then the `buildFork` Gradle task — and deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no prompt). Always build without asking permission. Use whenever 白い熊 mentions Librera, shiroikuma-doksho, 読書, asks to build the app, build the APK, make a release build, or build and send to the phone.
---

# Build the 白い熊 読書 release APK and deliver it

> **Never ask whether to build — just build.** When this skill applies (白い熊 asked to build, or a
> change is finished), run the build immediately. There is **no** transfer question either: after a
> successful build, deliver via the global **`/after-build`** skill — no prompts at all.

> **The push destination is ALWAYS `/sdcard/tmp/`.** Never `adb install` / `pm install` /
> `adb uninstall` — 白い熊 installs the APK from the phone's file manager.

> **Never `git commit` or `git push` on your own.** Building does not include committing. Only on
> 白い熊's explicit **"Push"** do you commit and `git push origin custom` ("Push" is unrelated to
> `adb push`).

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `foobnix/LibreraReader` (remote `upstream`, HTTPS, **fetch only** — push URL `DISABLED`) |
| Fork repo | `git@github.com:ShiroiKuma0/shiroikuma-doksho.git` (remote `origin`, SSH — push here) |
| Local working tree | `~/git/shiroikuma-doksho` |
| Mirror branch | `master` — reset to upstream's latest release tag, never carries our changes |
| Custom branch | `custom` — all our commits, rebased onto `master`; the GitHub default branch |
| applicationId | `shiroikuma.doksho` |
| App label | `白い熊 読書` |
| Settings page of our changes | `白い熊 読書 UI` |
| Code namespaces (**UNCHANGED**) | `com.foobnix.pdf.info` (R / manifest), `com.foobnix.*`, `org.ebookdroid.*` — never rename |
| Product flavour | `doksho` (defined in `shiroikuma/fork.gradle`; every other upstream flavour is disabled) |
| Target ABI | `arm64-v8a` only (ABI splits off, `ndk.abiFilters` arm64) → exactly one APK |
| Native library | `libMuPDF.so` + `liblame.so`, built by `shiroikuma/build-mupdf.sh` into `app/src/main/jniLibs/arm64-v8a/` (gitignored) |
| Gradle task | `./gradlew buildFork` (root project name resolves it to `:app:buildFork`) |
| Built APK dir | `app/build/outputs/apk/doksho/release/` |
| Delivered APK | `~/tmp/shiroikuma-doksho_<versionName>_arm64-v8a.apk` → `/sdcard/tmp/` |
| Keystore | `~/.android-keystores/shiroikuma-doksho.jks`, alias `doksho` |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK / NDK | `~/android-sdk`, compileSdk 37, NDK = newest under `~/android-sdk/ndk` (29.0.14206865 as of 2026-09-25) |
| Gradle / AGP | 9.7.1 wrapper / 9.3.2 (upstream's; `app/build.gradle` is Groovy) |

## Build environment (this machine)

The default `java` is JDK 11, which cannot run Gradle 9.x, and the Android SDK is not on a default
env var. Export both in **every** invocation:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/shiroikuma/android-sdk
```

Run every build, git and keystore command with `dangerouslyDisableSandbox: true` (writes under `~/git`).

## Steps

1. **Native library present?** `ls app/src/main/jniLibs/arm64-v8a/libMuPDF.so`. It is gitignored,
   so a fresh clone, a `git clean`, or an upstream sync that touched `Builder/jni/` or moved the MuPDF
   version (a new `Builder/all-release-<ver>.sh`) needs a rebuild:

   ```bash
   shiroikuma/build-mupdf.sh            # add `clean` to start MuPDF over from a pristine checkout
   ```

   - Clones MuPDF — the newest `Builder/all-release-<ver>.sh` that has a `jni/Android-<ver>.mk`
     (since 9.6.39 upstream ships one release per MuPDF; `MUPDF=<ver>` overrides) — into `Builder/mupdf-<ver>/`
     (gitignored), replays upstream's patched MuPDF sources (parsed from
     `Builder/link_to_mupdf_<ver>.sh`), runs `make generate HAVE_OBJCOPY=no`, `ndk-build` for arm64.
   - `HAVE_OBJCOPY=no` is load-bearing: without it, on Linux, `generate` skips the font `.c`
     sources and the link fails on `_binary_Noto…_otf` symbols. (Upstream builds on macOS.)
   - A Java-only change never needs this step.

2. **Note the version you are about to produce:**

   ```bash
   grep -E '^(BUILD_NUMBER|LAST_BUILT_VERSION_CODE)=' shiroikuma/fork.properties
   grep -E 'FDroid(Version|Code)Number =' app/build.gradle
   ```

   The APK will be `shiroikuma-doksho_<FDroidVersionNumber>+<BUILD_NUMBER padded to 3>_arm64-v8a.apk`
   with the counter **before** the build. Read the printed `>>>` lines rather than reconstructing it.

3. **Build** (release, signed):

   ```bash
   ./gradlew buildFork --console=plain < /dev/null
   ```

   - Runs `assembleDokshoRelease`, copies the signed APK to `~/tmp/<apk name>` (refusing to overwrite
     an existing file), bumps `BUILD_NUMBER` and records `LAST_BUILT_VERSION_CODE`.
   - Prints `>>> <path>`, `>>> versionCode <n>`, `>>> BUILD_NUMBER bumped to <n>` in cyan; confirm
     `BUILD SUCCESSFUL`.
   - A cold build (fresh Gradle cache) can exceed the foreground timeout — use `run_in_background`.
   - **Fast iteration:** `./gradlew :app:assembleDokshoDebug` (no R8). The shippable build is `buildFork`.

4. **Deliver via `/after-build`** — every successful build, no asking.

## Signing

Upstream's `signingConfigs.release` reads `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` from the global `~/.gradle/gradle.properties`.
`shiroikuma/fork.gradle` sets them from the gitignored **`keystore.properties`** at the repo root
(`storeFile` / `storePassword` / `keyAlias` / `keyPassword`), so upstream's block stays untouched.

- Keystore `~/.android-keystores/shiroikuma-doksho.jks`, alias `doksho` — PKCS12, RSA-4096,
  SHA384withRSA, 10000 days, DN `CN=白い熊 読書, O=ShiroiKuma0`, created 2026-09-25. Certificate
  SHA-256 `fd:fc:8f:95:de:7a:19:32:a8:1c:2b:50:3b:1b:e0:d8:09:0b:bd:cf:61:21:1f:fb:99:2e:b8:88:af:14:9e:8a`.
- Password: `~/〇/[666] 私資料/[666][27] 暗号/android-keystores.org`; the `.jks` is mirrored to
  `~/〇/[666] 私資料/[666][27] 暗号/android-keystores/`.
- Missing `keystore.properties` → the configuration prints `shiroikuma: no keystore.properties …` and
  signing fails. Restore it rather than shipping anything else:

  ```bash
  cat > keystore.properties <<EOF
  storeFile=$HOME/.android-keystores/shiroikuma-doksho.jks
  storePassword=<from the vault>
  keyAlias=doksho
  keyPassword=<same>
  EOF
  chmod 600 keystore.properties
  ```

## Versioning (how the numbers are formed)

All of it is in **`shiroikuma/fork.gradle`**, which overrides upstream's `versionNumber` /
`codeNumber` ext values before its `android {}` block reads them.

- Upstream's release pair is **`FDroidVersionNumber` / `FDroidCodeNumber`** in `app/build.gradle`:
  upstream's release script writes them from the version it tags. `app/gradle.properties`
  (`appVersionNumberBase.appVersionNumberIndex` / `appCodeNumber`) drifts — tag `9.6.25` carries
  index `24`. Never hand-edit either.
- `BUILD_NUMBER` in **`shiroikuma/fork.properties`** is our per-build increment.
- `versionName = "<FDroidVersionNumber>+<BUILD_NUMBER padded to 3>"` → `9.6.25+001`.
- `versionCode = FDroidCodeNumber * 10000 + BUILD_NUMBER` → `73380001`.
- `BUILD_NUMBER` **resets to `1` on each new upstream release** (`upstream-new-version`); upstream's
  code rises by 4+ per release, so the new line always exceeds the old. `LAST_BUILT_VERSION_CODE`
  is the floor: `buildFork` fails rather than build at or below it.

## Traps specific to this project

- **Every upstream flavour is still evaluated.** Their `manifestPlaceholders` read per-flavour ad /
  Drive keys (`librera_admobAppId`, `pro_appGdriveKey`, …) from the global gradle properties;
  `fork.gradle` blanks the known ones. A new upstream flavour fails configuration with
  *"Could not get unknown property '<flavour>_…'"* — add the flavour to the list in `fork.gradle`.
- **Google stubs.** `app/src/doksho/java` is a copy of `app/src/fdroid/java` minus the junrar stubs,
  with `LibreraBuildConfig.FLAVOR = "doksho"`. A compile error about a Google class means upstream's
  code uses a new API — re-copy the matching stub from `app/src/fdroid/java`.
- **No `google-services.json`, on purpose.** The plugin stays applied (upstream's `plugins {}`), its
  `process*GoogleServices` tasks are disabled by `fork.gradle`.
- **`IS_FDROID` is true for us** (Google Drive tab hidden, rate → GitHub, …) — that is intended. CBR is
  gated on our `AppsConfig.IS_RAR` instead, which is true because we ship the real junrar.
- Upstream's `Builder/all-*.sh`, `link_to_mupdf_*.sh`, `copyApks`, `incVersion`, `updateFDroid` are
  **not** our pipeline; never run them (they bump upstream's numbers and build four ABIs).

## Related skills

- **`upstream-new-version`** — proceed-gated sync onto a new upstream release tag.
- Global **`/after-build`** (deliver), **`/publish-version`** (GitHub release with the merged changelog).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` /
"Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line
of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
