# What ./ec needs to know about the hub75 backend (sourced with the board).
. "$EC_ROOT/backends/matrix/matrix.sh"

# hzeller/rpi-rgb-led-matrix (GPL-2.0), master of 2026-10-10. Raspberry Pi 1
# to 5 (the Pi 5 through its RP1 chip).
RGBMATRIX_COMMIT=14464b3ad8974665f75a3c82355cf872311fec21

# backend_make_vars: extra NAME=value words for backends/Makefile. Builds the
# library once (static, no LTO) under out/deps/.
backend_make_vars() {
  dir=$EC_DEPS/rpi-rgb-led-matrix-$RGBMATRIX_COMMIT-$ARCH
  if [ ! -f "$dir/lib/librgbmatrix.a" ]; then
    t=$(fetch "https://github.com/hzeller/rpi-rgb-led-matrix/archive/$RGBMATRIX_COMMIT.tar.gz" - \
      "rpi-rgb-led-matrix-$RGBMATRIX_COMMIT.tar.gz")
    unpack "$t" "$dir"
    note "building rpi-rgb-led-matrix ($ARCH)"
    cross=$(cross_for "$ARCH")
    make -C "$dir/lib" -j"$JOBS" CC="${cross}gcc" CXX="${cross}g++" AR="${cross}ar" LTO_FLAGS= librgbmatrix.a \
      > "$dir/build.log" 2>&1 || die "rpi-rgb-led-matrix build failed, see $dir/build.log"
  fi
  echo "RGBMATRIX=$dir"
}
