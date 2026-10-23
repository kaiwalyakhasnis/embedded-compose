# The rpi-hub75 app on QEMU's Raspberry Pi 3 model (-M raspi3b): the real
# HUB75 backend and rpi-rgb-led-matrix, driving emulated BCM2837 GPIO
# registers. There is no panel, so this checks everything up to the pins:
# the library starts its refresh thread, frames swap, keys arrive, SIGTERM
# turns the panel off. board-init.sh prints the GPIO levels and the frame
# on the console.
#
# Kernel: boards/qemu-virt's (arm64 defconfig includes the BCM2837 drivers),
# with the Pi 3 B device tree from the same kernel source.
#
#   ./ec run qemu-rpi3      console here; keys typed into the QEMU window go
#                           to its USB keyboard (Left/Right, Enter)
#   HEADLESS=1              no window; console in out/qemu-rpi3/console.log,
#                           keys through tools/qmp.py (hold a key >= 0.3 s:
#                           the emulated USB keyboard drops shorter presses)
BOARD_DESC="QEMU Raspberry Pi 3 (raspi3b): the HUB75 driver without a panel (emulated)"
BOARD_APP=matrix

ARCH=arm64
BACKEND=hub75
DENSITY=1
FONT=5x7
BOARD_INIT=board-init.sh
EC_ENV="
EC_HUB75='--led-rows=32 --led-cols=64 --led-gpio-mapping=regular --led-slowdown-gpio=2 --led-no-hardware-pulse'
EC_DEV_CAPTURE=1
EC_WAIT_SECS=1
"
# --led-no-hardware-pulse: QEMU doesn't emulate the PWM block that times the
#   panel's OE pulses on a real Pi.
# EC_WAIT_SECS=1: /init waits for a display device, and there is none.

eval "$(grep '^KERNEL_' "$EC_ROOT/boards/qemu-virt/board.sh")"   # KERNEL_VERSION, KERNEL_SHA256

board_kernel() {
  dep_kernel $KERNEL_VERSION $KERNEL_SHA256 "$EC_ROOT/boards/qemu-virt/kernel.config" "$EC_OUT/qemu-virt/kernel"
}

board_image() {
  board_kernel >/dev/null
  src=$EC_DEPS/src/linux-$KERNEL_VERSION
  cpp -nostdinc -I "$src/include" -I "$src/arch/arm64/boot/dts" -I "$src/scripts/dtc/include-prefixes" \
      -undef -D__DTS__ -x assembler-with-cpp "$src/arch/arm64/boot/dts/broadcom/bcm2837-rpi-3-b.dts" |
    "$EC_OUT/qemu-virt/kernel/scripts/dtc/dtc" -q -I dts -O dtb -o "$BOARD_OUT/bcm2837-rpi-3-b.dtb" - ||
    die "device tree build failed"
}

# ec.time: the emulated Pi has no real-time clock; board-init.sh sets the
# time from this, so the clock page shows the host's time (UTC).
board_run() {
  [ -f "$BOARD_OUT/bcm2837-rpi-3-b.dtb" ] || die "build it first: ./ec build $BOARD"
  rm -f "$BOARD_OUT/qmp.sock" "$BOARD_OUT/console.log"
  if [ -n "$HEADLESS" ]; then
    set -- -display none -serial "file:$BOARD_OUT/console.log"
  else
    set -- -display gtk -serial stdio
  fi
  exec qemu-system-aarch64 -M raspi3b "$@" -monitor none -device usb-kbd \
    -qmp "unix:$BOARD_OUT/qmp.sock,server=on,wait=off" \
    -kernel "$(board_kernel)" -dtb "$BOARD_OUT/bcm2837-rpi-3-b.dtb" -initrd "$INITRAMFS" \
    -append "console=ttyAMA0 rdinit=/init ec.time=$(date +%s)"
}

# A standalone archive: kernel, device tree, initramfs and a run.sh.
board_package() {
  cp "$(board_kernel)" "$1/Image"
  cp "$BOARD_OUT/bcm2837-rpi-3-b.dtb" "$INITRAMFS" "$1/"
  cat > "$1/run.sh" <<'EOF'
#!/bin/sh
# embedded-compose's HUB75 build (the matrix app + rpi-rgb-led-matrix) on
# QEMU's Raspberry Pi 3 model. There is no panel: every 5 s the console
# prints the GPIO levels the library drives and the frame it was given.
# Needs qemu-system-arm (QEMU 8.2 tested). Keys typed into the QEMU window
# (Left/Right, Enter) go to the Pi's USB keyboard; hold each for a moment.
#   HEADLESS=1 ./run.sh   no window, console only
cd "$(dirname "$0")"
display="-display gtk"
[ -n "$HEADLESS" ] && display="-display none"
exec qemu-system-aarch64 -M raspi3b $display -serial stdio -monitor none -device usb-kbd \
  -kernel Image -dtb bcm2837-rpi-3-b.dtb -initrd initramfs.cpio.gz \
  -append "console=ttyAMA0 rdinit=/init ec.time=$(date +%s)"
EOF
  chmod +x "$1/run.sh"
}
