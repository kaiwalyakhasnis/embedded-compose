#!/bin/sh
# Re-creates this directory: minui and the few AOSP sources it needs,
# straight from android.googlesource.com at one tag (default
# android-8.1.0_r52, Android 8.1).
#
#   third_party/aosp/fetch.sh [tag]
#
# Only the files the build uses are kept. Paths mirror the AOSP tree.
set -e
DST=$(cd "$(dirname "$0")" && pwd)
TAG=${1:-android-8.1.0_r52}
GS=https://android.googlesource.com
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

retry() {  # googlesource answers 503 now and then
  n=0
  until "$@"; do
    n=$((n + 1)); [ $n -ge 6 ] && { echo "failed: $*" >&2; return 1; }
    sleep $((n * 4))
  done
}

# Subdirectory (or "" = whole project) as a tarball: <project> <path> <dest>
archive() {
  mkdir -p "$TMP/$3"
  retry sh -c "curl -sf --max-time 180 '$GS/$1/+archive/refs/tags/$TAG${2:+/$2}.tar.gz' | tar -xz -C '$TMP/$3'"
}

# Single file (system/core is too big for gitiles archives): <project> <path> <dest>
file() {
  mkdir -p "$(dirname "$TMP/$3")"
  retry sh -c "curl -sf --max-time 60 '$GS/$1/+/refs/tags/$TAG/$2?format=TEXT' | base64 -d > '$TMP/$3' && test -s '$TMP/$3'"
}

echo "fetching AOSP $TAG ..."
archive platform/bootable/recovery minui bootable/recovery/minui
file    platform/bootable/recovery NOTICE bootable/recovery/NOTICE
file    platform/bootable/recovery fonts/12x22.png bootable/recovery/fonts/12x22.png
file    platform/bootable/recovery fonts/18x32.png bootable/recovery/fonts/18x32.png
file    platform/bootable/recovery fonts/OFL.txt bootable/recovery/fonts/OFL.txt
file    platform/bootable/recovery res-xxhdpi/images/icon_error.png bootable/recovery/res-xxhdpi/images/icon_error.png
for f in adf/libadf/adf.cpp adf/libadf/include/adf/adf.h adf/libadf/include/video/adf.h \
         libsync/sync.c libsync/NOTICE libsync/include/android/sync.h libsync/include/ndk/sync.h \
         base/strings.cpp base/include/android-base/strings.h NOTICE; do
  file platform/system/core "$f" "system/core/$f"
done
# In AOSP this one is a symlink; gitiles returns its target as the content.
rm "$TMP/system/core/libsync/include/sync/sync.h" 2>/dev/null || true
mkdir -p "$TMP/system/core/libsync/include/sync"
ln -s ../android/sync.h "$TMP/system/core/libsync/include/sync/sync.h"

for f in Roboto-Regular.ttf Roboto-Medium.ttf NOTICE README.android; do  # ui.material's text
  file platform/external/roboto-fonts "$f" "external/roboto-fonts/$f"
done

archive platform/external/zlib "" external/zlib.full
archive platform/external/libpng "" external/libpng.full
archive platform/external/libdrm "" external/libdrm.full

# Keep only what backends/minui/backend.mk compiles or includes (+ licences).
mkdir -p "$TMP/external/zlib/src" "$TMP/external/libpng" "$TMP/external/libdrm"
cp "$TMP"/external/zlib.full/src/*.[ch] "$TMP/external/zlib/src/"
cp "$TMP"/external/zlib.full/NOTICE "$TMP/external/zlib/"
cp "$TMP"/external/libpng.full/*.[ch] "$TMP"/external/libpng.full/LICENSE "$TMP"/external/libpng.full/NOTICE "$TMP/external/libpng/" 2>/dev/null || \
  cp "$TMP"/external/libpng.full/*.[ch] "$TMP"/external/libpng.full/LICENSE "$TMP/external/libpng/"
rm -f "$TMP"/external/libpng/pngtest.c "$TMP"/external/libpng/example.c
cp "$TMP"/external/libdrm.full/*.[ch] "$TMP"/external/libdrm.full/NOTICE "$TMP/external/libdrm/" 2>/dev/null || \
  cp "$TMP"/external/libdrm.full/*.[ch] "$TMP/external/libdrm/"
cp -r "$TMP"/external/libdrm.full/include "$TMP/external/libdrm/"
rm -rf "$TMP"/external/*.full

rm -rf "$DST/bootable" "$DST/system" "$DST/external"
cp -a "$TMP"/. "$DST"/
cat > "$DST/VERSION" <<EOF
AOSP tag: $TAG
Fetched: $(date -u +%Y-%m-%d) by fetch.sh from $GS
  platform/bootable/recovery   minui/, fonts/12x22.png + 18x32.png, res-xxhdpi/images/icon_error.png
  platform/system/core         adf/libadf, libsync, base/strings (needed files only)
  platform/external/zlib       src/*.c *.h
  platform/external/libpng     *.c *.h
  platform/external/libdrm     *.c *.h, include/
  platform/external/roboto-fonts  Roboto-Regular.ttf, Roboto-Medium.ttf
EOF
echo "vendored into $DST:"
du -sh "$DST"/*/* | sed "s|$DST/||"
