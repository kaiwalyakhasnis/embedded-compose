# Third-party pieces that boards share, built from pinned upstream tarballs.
# Results are cached under out/deps/; delete a directory there to rebuild.

BUSYBOX_VERSION=1.36.1
BUSYBOX_SHA256=b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314

# Static busybox for $ARCH. Prints the binary's path.
dep_busybox() {
  out=$EC_DEPS/busybox-$BUSYBOX_VERSION-$ARCH
  if [ ! -x "$out/busybox" ]; then
    tar=$(fetch "https://busybox.net/downloads/busybox-$BUSYBOX_VERSION.tar.bz2" $BUSYBOX_SHA256)
    src=$EC_DEPS/src/busybox-$BUSYBOX_VERSION
    unpack "$tar" "$src"
    note "building busybox $BUSYBOX_VERSION ($ARCH)"
    mkdir -p "$out"
    mk="make -C $src O=$out ARCH=$ARCH CROSS_COMPILE=$(cross_for "$ARCH")"
    $mk defconfig >/dev/null || die "busybox defconfig failed"
    # Static, and without tc (doesn't build against kernel headers >= 6.8).
    sed -i -e 's/# CONFIG_STATIC is not set/CONFIG_STATIC=y/' -e 's/^CONFIG_TC=y/# CONFIG_TC is not set/' "$out/.config"
    $mk -j"$JOBS" >"$out/build.log" 2>&1 || die "busybox build failed, see $out/build.log"
  fi
  echo "$out/busybox"
}

# Mainline kernel: dep_kernel <version> <sha256> <config-fragment> <outdir>.
# arm64 defconfig plus the board's fragment; builds only the Image (no
# modules: everything the board needs must be =y). Prints the Image path.
dep_kernel() {
  ver=$1 sum=$2 frag=$3 out=$4
  img=$out/arch/$ARCH/boot/Image
  stamp=$out/.fragment
  if [ -f "$img" ] && cmp -s "$frag" "$stamp"; then echo "$img"; return 0; fi
  tar=$(fetch "https://cdn.kernel.org/pub/linux/kernel/v${ver%%.*}.x/linux-$ver.tar.xz" "$sum")
  src=$EC_DEPS/src/linux-$ver
  unpack "$tar" "$src"
  note "building linux $ver for $BOARD (log: $out/build.log)"
  mkdir -p "$out"
  mk="make -C $src O=$out ARCH=$ARCH CROSS_COMPILE=$(cross_for "$ARCH")"
  {
    $mk defconfig &&
    "$src/scripts/kconfig/merge_config.sh" -m -O "$out" "$out/.config" "$frag" &&
    $mk olddefconfig &&
    $mk -j"$JOBS" Image
  } >"$out/build.log" 2>&1 || die "kernel build failed, see $out/build.log"
  # Options from the fragment that did not stick (usually a missing dependency).
  grep -E '^CONFIG_' "$frag" | while read -r opt; do
    grep -qxF "$opt" "$out/.config" || echo "warning: kernel option not set: $opt" >&2
  done
  sed -nE 's/^# (CONFIG_[A-Z0-9_]+) is not set/\1/p' "$frag" | while read -r opt; do
    if grep -q "^$opt=[ym]" "$out/.config"; then echo "warning: kernel option still enabled: $opt" >&2; fi
  done
  cp "$frag" "$stamp"
  echo "$img"
}
