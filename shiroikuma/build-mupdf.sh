#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
# shiroikuma-doksho — build the native library (MuPDF + DjVu + MOBI + …) for arm64-v8a only.
#
# Upstream's Builder/link_to_mupdf_<ver>.sh does the same for four ABIs, reads its NDK from the
# global ~/.gradle/gradle.properties and needs a host `make release` (X/GL dev libraries). This
# wrapper keeps upstream's recipe but:
#   - takes the MuPDF version from Builder/all-release.sh (the one upstream ships with),
#   - replays upstream's own list of patched MuPDF sources, parsed out of that link script, so
#     a new upstream version brings its new list in by itself,
#   - runs only `make generate` on the host (fonts as C sources), and ndk-build for arm64-v8a only,
#   - copies the .so files into app/src/main/jniLibs/arm64-v8a (gitignored).
#
# Usage: shiroikuma/build-mupdf.sh [clean]
# Env:   NDK=<ndk dir>   (default: newest under ~/android-sdk/ndk)
# ---------------------------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILDER="$ROOT/Builder"

VERSION_TAG="$(sed -nE 's#^\./link_to_mupdf_([0-9.]+)\.sh.*#\1#p' "$BUILDER/all-release.sh" | head -1)"
LINK_SCRIPT="$BUILDER/link_to_mupdf_$VERSION_TAG.sh"
[ -n "$VERSION_TAG" ] && [ -f "$LINK_SCRIPT" ] || { echo "cannot find the MuPDF version in Builder/all-release.sh"; exit 1; }

NDK="${NDK:-$(ls -d "$HOME"/android-sdk/ndk/* | sort -V | tail -1)}"
[ -x "$NDK/ndk-build" ] || { echo "no ndk-build under $NDK"; exit 1; }

MUPDF_ROOT="$BUILDER/mupdf-$VERSION_TAG"          # gitignored by upstream (/Builder/mupdf*)
MUPDF_JAVA="$MUPDF_ROOT/platform/librera"
SRC="$BUILDER/jni/~mupdf-$VERSION_TAG"
DEST="$MUPDF_ROOT/source"
LIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"

echo ">>> MuPDF $VERSION_TAG, NDK $NDK"

if [ ! -d "$MUPDF_ROOT/.git" ]; then
  git clone --recursive --depth 1 --shallow-submodules --branch "$VERSION_TAG" \
    https://github.com/ArtifexSoftware/mupdf.git "$MUPDF_ROOT"
fi

if [ "${1:-}" = "clean" ]; then
  (cd "$MUPDF_ROOT" && git reset -q --hard && git clean -fdq && rm -rf generated build)
  rm -rf "$MUPDF_JAVA"
fi

# HAVE_OBJCOPY=no: on Linux MuPDF would link the fonts in with objcopy and `generate` would skip
# the generated/resources/fonts/*.c sources that upstream's MuPDF-<ver>.mk compiles (upstream
# builds on macOS, where there is no objcopy and they are always generated).
[ -d "$MUPDF_ROOT/generated/resources/fonts/noto" ] || (cd "$MUPDF_ROOT" && make generate HAVE_OBJCOPY=no)

# Upstream's patched MuPDF sources: the `cp -rpv $SRC/… <dest>` lines of its link script.
grep -E '^cp -rpv \$SRC/' "$LINK_SCRIPT" | while read -r line; do
  eval "$line" >/dev/null
done

rm -rf "$MUPDF_JAVA/jni"
mkdir -p "$MUPDF_JAVA"
cp -Rp "$BUILDER/jni" "$MUPDF_JAVA/jni"
mv "$MUPDF_JAVA/jni/Android-$VERSION_TAG.mk" "$MUPDF_JAVA/jni/Android.mk"

(cd "$MUPDF_JAVA" && "$NDK/ndk-build" -j"$(nproc)" \
  NDK_APPLICATION_MK=jni/Application.mk APP_ABI=arm64-v8a APP_PLATFORM=android-24)

# Upstream's script leaves symlinks here for four ABIs; we keep real files for one.
rm -rf "$ROOT/app/src/main/jniLibs"
mkdir -p "$LIBS"
cp -p "$MUPDF_JAVA/libs/arm64-v8a/"*.so "$LIBS/"
echo ">>> $(ls "$LIBS" | tr '\n' ' ')→ $LIBS"
