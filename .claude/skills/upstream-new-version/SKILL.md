---
name: upstream-new-version
description: Sync the shiroikuma-doksho fork (白い熊 読書) onto a new upstream release of foobnix/LibreraReader — check upstream for a newer release tag, present 白い熊 a proceed-gated, tabular, descriptive summary of what the new upstream version introduces, then (only on an explicit go-ahead) reset `master` to the new tag, rebase the `custom` patch stack onto it, merge the changelog, reset the build counter and build the new +001. Use when 白い熊 runs /upstream-new-version, says a new Librera version is out, or asks to check for a new version, update/sync to upstream, or rebase custom onto the latest upstream tag.
---

# Sync shiroikuma-doksho onto a new upstream Librera release

`master` mirrors upstream's **latest release tag**; `custom` carries our patches and is rebased onto
it. Every concrete fact (identity, keystore, version scheme, the build) lives in the **`build-apk`**
skill — read it first.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** The rebase and build
> happen on the local tree; everything is re-runnable (`git rebase --abort`) until 白い熊 says
> **"Push"**.

## Branch / remote model

| Branch | Role | Update mode |
| --- | --- | --- |
| `master` | Upstream's latest release tag. No fork work here. | `git checkout -B master <tag>` |
| `custom` | Our patches; the working branch and the GitHub default branch. | rebased onto `master` |

`origin` = `git@github.com:ShiroiKuma0/shiroikuma-doksho` (push). `upstream` =
`https://github.com/foobnix/LibreraReader` (fetch only, push URL `DISABLED`).

**Upstream tracking: release tags.** Upstream tags each release with the bare version (`9.6.17`,
`9.6.25` — no `v`, no suffix) and publishes a GitHub release with the Pro / F-Droid / LibreraX APKs.
We never base on bleeding `upstream/master`. The global `git-versioning` pin does **not** apply.

## Step 0 — Preconditions

- cwd `~/git/shiroikuma-doksho`, on `custom`, working tree clean (`git status --short` empty;
  `keystore.properties`, `jniLibs`, `Builder/mupdf-*` are gitignored).
- git / `gh` / `gradlew` **unsandboxed** (`dangerouslyDisableSandbox: true`).

## Step 1 — Is there a newer release?

```bash
git fetch upstream --tags --force
git describe --tags --exact-match master            # our current base, e.g. 9.6.25
git tag -l '[0-9]*.[0-9]*.[0-9]*' --sort=-version:refname | head -5
gh release list -R foobnix/LibreraReader -L 8
```

Newest tag not newer than our base → report **"already current"** and stop. Otherwise, for
`NEW=<newest tag>` and `OLD=<our base>`:

```bash
git show $NEW:app/build.gradle | grep -E 'FDroid(Version|Code)Number ='   # the new upstream pair
git log --oneline $OLD..$NEW | wc -l
git diff $OLD..$NEW -- CHANGELOG.md                                        # upstream's own notes
for t in $(git tag -l '[0-9]*.[0-9]*.[0-9]*' --sort=version:refname); do  # every release in between
  git merge-base --is-ancestor $OLD $t && [ $t != $OLD ] && gh release view $t -R foobnix/LibreraReader --json tagName,publishedAt,body
done
```

Upstream's `CHANGELOG.md` often lags its tags, so the commit log is the primary source. Read the
commit **bodies** (`git log --format='%h %s%n%b' $OLD..$NEW`), not only the subjects. Also get the
conflict set before anything moves:

```bash
git diff --name-only $OLD..$NEW > /tmp/doksho-upstream-touched.txt
git diff --name-only master..custom | grep -Fxf /tmp/doksho-upstream-touched.txt
git diff --stat $OLD..$NEW -- Builder/jni Builder/all-release.sh   # native rebuild needed?
```

## Step 2 — ⛔ PROCEED GATE: the new-features table (MANDATORY, before any branch moves)

**白い熊's standing request: before rebasing, give a tabular, descriptive summary of what the new
upstream version introduces, and wait for an explicit go-ahead.** No `master` move, no rebase, no
build until they say proceed. Never skip it, never fold it into the rebase turn.

Describe what each change **does**, in plain language — not a commit dump. Cover **every** release
between our base and the new tag. Group features first, then fixes; fold noise (translations,
dependency bumps, refactors) into one row each.

| Release | Area | Change | What it does for the reader | Touches our patches? |
| --- | --- | --- | --- | --- |
| e.g. 9.6.29 | PDF search | Search results list shows the matched line | Easier to pick a hit | **Yes** — our next/previous search navigation hangs off the search dialog |
| e.g. 9.6.29 | Annotations | Ink width follows zoom | Strokes keep their size | **Yes** — stylus drawing |
| e.g. 9.6.27 | Native | MuPDF 1.28.4 → 1.28.5 | Rendering fixes | Native rebuild (`build-mupdf.sh clean`) |
| e.g. 9.6.26 | Translations | 12 locales updated | — | No |

Flag in the last column anything that:

- touches a **file our layer patches** (the inventory in Step 6) — likely conflicts;
- changes **search** (`DragingDialogs` search dialog, `PageSearcher`, `doSearch` in the controllers)
  or **drawing / annotations** (`DrawView`, `dialogEditColors`, `addAnnotation`, `saveAnnotations`,
  `libmupdf_librera_*.c`) — the two areas this fork exists for;
- adds a **Google / telemetry / ads** path, a new product flavour (needs its placeholder keys blanked
  in `fork.gradle`), or a new Google API the `app/src/doksho/java` stubs lack;
- moves the **MuPDF version** or `Builder/jni` — the native library must be rebuilt;
- adds **branding or links** (About, Help, FAQ, rate / share, "Librera" strings) our de-branding
  must cover;
- is a genuinely useful fix for 白い熊.

Close with the version move (`OLD` → `NEW`, upstream code), the commit count, the conflict set and
whether a native rebuild is needed — then ask **"Proceed with the rebase to `NEW`?"** and **STOP**.
Only an explicit "proceed" / "go" / "yes" continues. If 白い熊 declines, nothing has been touched.

## Step 3 — Advance `master` to the new tag

```bash
git branch -f custom-pre-$NEW custom      # local safety ref; /publish-version deletes it
git checkout master
git reset --hard $NEW                     # master carries no fork work; it IS the tag
```

## Step 4 — Rebase `custom`

```bash
git checkout custom
git rebase master
```

Resolve so **all** our customizations survive — reconcile, don't drop; if upstream restructured a
file we patch, port our change onto the new structure.

- **`app/build.gradle`**: our only line is `apply from: "$rootDir/shiroikuma/fork.gradle"` right
  before `android {`. Keep upstream's new `FDroidVersionNumber` / `FDroidCodeNumber` literals exactly.
- **`CHANGELOG.md`**: our block sits above everything upstream has, so upstream's insertions merge
  cleanly; if it does conflict, keep upstream's text byte-for-byte and our block on top.

**"Not huge" — resolve in place**, `git add`, `git rebase --continue`: context shifts, a commit going
empty because upstream did the same.

**"Significant" — STOP and plan with 白い熊**: upstream refactored something a patch depends on
(the search dialog, the draw / annotation path, `AppsConfig`, the flavour or signing blocks), many
commits conflict, or a **semantic** conflict (hunks merge but behaviour changed). Gather `git status`,
the conflicted hunks and what upstream did to that file, say which of **our** commits conflicts and
why, and present options — resolve together, re-derive the commit, defer it, or `git rebase --abort`
(returns the tree exactly to where it was).

## Step 5 — Reset the build counter

In `shiroikuma/fork.properties` set **`BUILD_NUMBER=1`**. Leave `LAST_BUILT_VERSION_CODE` alone: the
new upstream code × 10000 + 1 is higher than any code of the old line, and `buildFork` checks it.

## Step 6 — Verify our customizations survived

| What | Expected | Where |
| --- | --- | --- |
| Fork hook | `apply from: "$rootDir/shiroikuma/fork.gradle"` before `android {` | `app/build.gradle` |
| Flavour, signing, version, `buildFork` | `doksho` flavour: `shiroikuma.doksho`, `白い熊 読書`, `appSafeMode false`, arm64 | `shiroikuma/fork.gradle` |
| Counter | `BUILD_NUMBER=1` | `shiroikuma/fork.properties` |
| Flavour source set | `LibreraBuildConfig.FLAVOR = "doksho"` + Google stubs | `app/src/doksho/java` |
| Google-free + CBR | `doksho` in `IS_FDROID`; `IS_RAR`; `.cbr` gated on `IS_RAR` | `AppsConfig.java`, `ExtUtils.java`, `BookType.java` |
| Gitignore block | `keystore.properties`, `*.jks`, `/app/src/main/jniLibs/`, `/.scratch/` | `.gitignore` |
| Our guide + skills | present | `CLAUDE.md`, `.claude/skills/` |
| Feature patches | every shipped customization (keep this table growing as they land) | their files |

Regression greps — each must print nothing (a rebase will not flag a new upstream flavour or stub gap):

```bash
git diff master..custom --stat -- app/src/fdroid          # we never edit upstream's fdroid set
diff <(cd app/src/fdroid/java && find . -name '*.java' ! -path './com/github/junrar/*' | sort) \
     <(cd app/src/doksho/java && find . -name '*.java' | sort)   # a new upstream stub → copy it over
```

Then confirm the build script still evaluates:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk \
  ./gradlew buildFork --dry-run --console=plain < /dev/null
```

## Step 7 — Merge the changelog

**白い熊's standing request: every release publishes a merged changelog.** Upstream's `CHANGELOG.md`
arrives with the rebase untouched. At the very top of the file, above upstream's `# Changelog`, our
block records — newest first — each fork release as `## 白い熊 読書 <version> — <YYYY-MM-DD>`, naming
the upstream release it is built on. For this sync, add the new entry and, under it, a short
*Upstream <NEW>* list of what the new upstream release(s) brought (from Step 2's table / the GitHub
release bodies) whenever upstream's own `CHANGELOG.md` has no section for them yet — its file lags
its tags. Upstream's text is never edited: `git diff master -- CHANGELOG.md | grep -c '^-[^-]'` → `0`.
The global `/publish-version` publishes the merged file with the release.

## Step 8 — Native rebuild, then build the new `+001`

If Step 1 showed changes under `Builder/jni` or a new MuPDF version: `shiroikuma/build-mupdf.sh clean`.
Then build via the **build-apk** skill (`./gradlew buildFork`) and deliver via `/after-build`. The
printed version must be `<NEW>+001`. A build failure on the rebase result is a significant conflict —
diagnose and replan with 白い熊, don't patch blindly.

## Step 9 — Stop, then push only on "Push"

Let 白い熊 test. Only on their explicit **"Push"**:

```bash
git push --force-with-lease origin master      # master moved to the new tag
git push --force-with-lease origin custom      # history rewritten by the rebase
```

`--force-with-lease`, never bare `--force`.

## Hard rules

- Never skip the Step 2 proceed gate.
- Never `adb install` / `adb uninstall`; never commit / push unprompted.
- Never rename the `com.foobnix.*` / `org.ebookdroid.*` namespaces — only `applicationId` differs.
- Never hand-edit upstream's `FDroid*Number` or `app/gradle.properties` numbers.
- No Claude attribution in commits (see `CLAUDE.md`).
