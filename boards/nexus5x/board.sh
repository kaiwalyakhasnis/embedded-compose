# LG Nexus 5X (bullhead): `fastboot boot` of a boot image that lives only in
# RAM. Nothing is flashed; a reboot returns the phone to its installed system.
#
# Kernel: the stock bullhead kernel (3.10) from a LineageOS 15.1 boot or
# recovery image. Set one of these in boards/nexus5x/local.env:
#   BOOT_IMG=<path to a LineageOS 15.1 boot.img or recovery.img>  (kernel is extracted)
#   KERNEL=<path to an Image.gz-dtb>
BOARD_DESC="LG Nexus 5X (bullhead), LineageOS 15.1 kernel, fastboot boot (RAM only)"

ARCH=arm64
BACKEND=minui
BACKEND_FLAGS=        # msm fbdev, RGBA byte order: minui's default
DENSITY=2.625         # 420 dpi: 1080 px = 411 dp
FONT=18x32            # xxhdpi, as recovery would pick
BOARD_INIT=board-init.sh
EC_DEV_USB=${EC_DEV_USB:-0}
EC_ENV="
EC_LOG=kmsg
EC_POWER_REBOOT=1
EC_WAIT_INPUT=synaptics
GLIBC_TUNABLES=glibc.pthread.rseq=0
EC_DEV_USB=$EC_DEV_USB
EC_DEV_CAPTURE=$EC_DEV_USB
EC_TRACE=$EC_DEV_USB
"
# EC_LOG=kmsg: no console on the phone; the log is in dmesg (EC_DEV_USB=1) or,
#   after a reboot into Android, /proc/last_kmsg.
# EC_POWER_REBOOT=1: holding POWER for 3 s reboots (there is no other way out).
# GLIBC_TUNABLES: glibc >= 2.35 uses rseq, which a 3.10 kernel lacks.
# EC_DEV_USB=1 (set it when building: EC_DEV_USB=1 ./ec build nexus5x): USB
#   network + root telnet for boards/nexus5x/phone.py, tear-free screen
#   capture (EC_DEV_CAPTURE) and test-tag tracing.

board_kernel() {
  if [ -n "$KERNEL" ]; then
    [ -f "$KERNEL" ] || die "KERNEL=$KERNEL not found"
    echo "$KERNEL"
    return
  fi
  [ -n "$BOOT_IMG" ] || die "set BOOT_IMG (a LineageOS 15.1 boot/recovery image) or KERNEL in boards/nexus5x/local.env"
  [ -f "$BOOT_IMG" ] || die "BOOT_IMG=$BOOT_IMG not found"
  python3 "$BOARD_DIR/mkbootimg.py" --extract-kernel "$BOOT_IMG" "$BOARD_OUT/kernel" >&2
  echo "$BOARD_OUT/kernel"
}

board_image() {
  # bullhead's command line, minus the Android parts. GLIBC_TUNABLES here
  # reaches /init's environment, so busybox (also glibc) gets it too.
  python3 "$BOARD_DIR/mkbootimg.py" --kernel "$(board_kernel)" --ramdisk "$INITRAMFS" \
    --cmdline "boot_cpus=0-5 lpm_levels.sleep_disabled=1 msm_poweroff.download_mode=0 GLIBC_TUNABLES=glibc.pthread.rseq=0" \
    --base 0x00000000 --pagesize 4096 --ramdisk_offset 0x02000000 --tags_offset 0x01E00000 \
    -o "$BOARD_OUT/boot.img"
}

board_run() {
  [ -f "$BOARD_OUT/boot.img" ] || die "build it first: ./ec build $BOARD"
  command -v fastboot >/dev/null || die "fastboot not found (android-sdk-platform-tools)"
  fastboot devices | grep -q . || die "no device in fastboot mode (hold Vol-Down + Power)"
  note "fastboot boot (RAM only, nothing is flashed)"
  fastboot boot "$BOARD_OUT/boot.img"
}
