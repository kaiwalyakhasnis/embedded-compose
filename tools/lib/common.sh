# Shared shell helpers, sourced by ./ec (which sets EC_ROOT) and, through it,
# by every boards/<name>/board.sh. Everything is relative to the repo root;
# build products, downloads and per-board state live under out/.

EC_OUT=$EC_ROOT/out
EC_DL=$EC_OUT/downloads          # tarball cache (checksummed)
EC_DEPS=$EC_OUT/deps             # host-independent third-party builds (busybox, kernels)
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}

die() { echo "ec: $*" >&2; exit 1; }
note() { echo "==> $*" >&2; }

# Downloads <url> into $EC_DL once and checks its sha256. Prints the path.
fetch() {
  url=$1 sum=$2 f=$EC_DL/${1##*/}
  mkdir -p "$EC_DL"
  if [ ! -f "$f" ]; then
    note "downloading $url"
    curl -fL --retry 3 -o "$f.part" "$url" || die "download failed: $url"
    mv "$f.part" "$f"
  fi
  echo "$sum  $f" | sha256sum -c --quiet - >&2 || die "checksum mismatch: $f (delete it to re-download)"
  echo "$f"
}

# Unpacks <tarball> into <dir> unless already there.
unpack() {
  [ -d "$2" ] && return 0
  note "unpacking ${1##*/}"
  mkdir -p "$2.tmp"
  tar -xf "$1" -C "$2.tmp" --strip-components=1
  mv "$2.tmp" "$2"
}

# Cross toolchain prefix for an ARCH (override with CROSS=...).
cross_for() {
  case $1 in
    arm64) echo "${CROSS:-aarch64-linux-gnu-}" ;;
    *) die "unsupported ARCH '$1'" ;;
  esac
}

# Kotlin/Native target for an ARCH.
kotlin_target_for() {
  case $1 in
    arm64) echo linuxArm64 ;;
    *) die "no Kotlin/Native target for ARCH '$1'" ;;
  esac
}

# Gradle 8.13 + Kotlin 2.2 need JDK 17+: use JAVA_HOME, else the one Gradle
# provisioned, else a system JDK.
find_java() {
  [ -n "$JAVA_HOME" ] && return 0
  for j in "$HOME"/.gradle/jdks/jdk-17* "$HOME"/.gradle/jdks/*jdk*17* /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/java-17-openjdk-*; do
    [ -x "$j/bin/java" ] && { export JAVA_HOME=$j; return 0; }
  done
  command -v java >/dev/null || die "need a JDK 17+ (set JAVA_HOME)"
}
