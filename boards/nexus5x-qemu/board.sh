# The Nexus 5X's own kernel (LineageOS 15.1 bullhead, 3.10, with QEMU-only
# patches) on QEMU "virt", with a 1080x1920 framebuffer like the phone's
# panel. It catches what the generic qemu-virt board can't: the phone's old
# kernel (3.10: no rseq, no clone3) at the phone's resolution.
#
# Needs the QEMU kit of github.com/kaiwalyakhasnis/nexus5x-recovery-roadfighter
# (qemu/setup.sh builds it from a LineageOS 15.1 tree into its WORK directory,
# then `SMP=6 MEM=2048 qemu/gen-dtb.sh`). Point to that WORK directory in
# boards/nexus5x-qemu/local.env:
#   NEXUS5X_QEMU_KIT=<WORK directory>
BOARD_DESC="Nexus 5X kernel (LineageOS 15.1) on QEMU, 1080x1920 (emulated)"
BOARD_EMULATED=1

ARCH=arm64
BACKEND=minui
BACKEND_FLAGS=        # the kit's simplefb is a8b8g8r8 (bytes R,G,B,A): minui's default
DENSITY=2.625         # same as the phone
FONT=18x32
EC_ENV="
GLIBC_TUNABLES=glibc.pthread.rseq=0
"

kit() {
  [ -n "$NEXUS5X_QEMU_KIT" ] || die "set NEXUS5X_QEMU_KIT in boards/nexus5x-qemu/local.env (see boards/nexus5x-qemu/board.sh)"
  for f in kobj/arch/arm64/boot/Image dtb/virt-fb-6-2048.dtb; do
    [ -f "$NEXUS5X_QEMU_KIT/$f" ] || die "missing $NEXUS5X_QEMU_KIT/$f"
  done
}

board_image() {
  kit
}

# The kit's device tree puts the framebuffer at 0x80000000 and its kernel
# hands it to QEMU's ramfb; mem=1024M keeps Linux off it.
board_run() {
  kit
  qemu_boot "$NEXUS5X_QEMU_KIT/kobj/arch/arm64/boot/Image" "mem=1024M" \
    -M virt,gic-version=2,virtualization=on -cpu cortex-a57 -smp 6 -m 2048 \
    -dtb "$NEXUS5X_QEMU_KIT/dtb/virt-fb-6-2048.dtb" -device ramfb
}
