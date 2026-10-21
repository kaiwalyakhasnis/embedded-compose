# LG Nexus 5X (bullhead): `fastboot boot` of a boot image that lives only in
# RAM. Nothing is flashed; a reboot returns the phone to its installed system.
#
# Kernel: LineageOS 15.1's bullhead kernel (3.10), built from source by
# default: LineageOS/android_kernel_lge_bullhead at LINEAGE_KERNEL_COMMIT,
# lineageos_bullhead_defconfig, plus patches/ (a host build fix only), with
# AOSP's aarch64-linux-android-4.9 GCC. Or, in boards/nexus5x/local.env:
#   BOOT_IMG=<a LineageOS 15.1 boot.img or recovery.img>  (kernel is extracted)
#   KERNEL=<an Image.gz-dtb>
LINEAGE_KERNEL_COMMIT=65a266ae3d341b04bcf70250c7fa1f28cf687425   # lineage-15.1
AOSP_GCC_COMMIT=7a28c220c2e9001825328dca6188ef0077a80a88         # aarch64-linux-android-4.9
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
  if [ -n "$BOOT_IMG" ]; then
    [ -f "$BOOT_IMG" ] || die "BOOT_IMG=$BOOT_IMG not found"
    python3 "$BOARD_DIR/mkbootimg.py" --extract-kernel "$BOOT_IMG" "$BOARD_OUT/kernel" >&2
    echo "$BOARD_OUT/kernel"
    return
  fi
  kernel_from_source
}

kernel_from_source() {
  obj=$BOARD_OUT/kernel-obj
  img=$obj/arch/arm64/boot/Image.gz-dtb
  if [ -f "$img" ]; then echo "$img"; return; fi
  src=$EC_DEPS/src/android_kernel_lge_bullhead-$LINEAGE_KERNEL_COMMIT
  gcc=$EC_DEPS/aarch64-linux-android-4.9-$AOSP_GCC_COMMIT
  t=$(fetch "https://github.com/LineageOS/android_kernel_lge_bullhead/archive/$LINEAGE_KERNEL_COMMIT.tar.gz" - \
    "android_kernel_lge_bullhead-$LINEAGE_KERNEL_COMMIT.tar.gz")
  if [ ! -d "$src" ]; then
    unpack "$t" "$src"
    for p in "$BOARD_DIR"/patches/*.patch; do patch -p1 -s -d "$src" < "$p" || die "patch failed: $p"; done
  fi
  t=$(fetch "https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9/+archive/$AOSP_GCC_COMMIT.tar.gz" - \
    "aarch64-linux-android-4.9-$AOSP_GCC_COMMIT.tar.gz")
  unpack "$t" "$gcc" 0
  note "building the bullhead kernel (log: out/$BOARD/kernel-obj/build.log)"
  mkdir -p "$obj"
  mk="make -C $src O=$obj ARCH=arm64 CROSS_COMPILE=$gcc/bin/aarch64-linux-android-"
  { $mk lineageos_bullhead_defconfig && $mk -j"$JOBS"; } > "$obj/build.log" 2>&1 \
    || die "kernel build failed, see out/$BOARD/kernel-obj/build.log"
  echo "$img"
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

board_package() {
  [ -f "$BOARD_OUT/boot.img" ] || die "build it first: ./ec build $BOARD"
  cp "$BOARD_OUT/boot.img" "$1/embedded-compose-nexus5x-boot.img"
  cat > "$1/README.txt" <<EOF
embedded-compose for the LG Nexus 5X (bullhead): $BOARD_DESC

Boot it once, from RAM; nothing is written to the phone:
  adb reboot bootloader        (or hold Vol-Down + Power)
  fastboot boot embedded-compose-nexus5x-boot.img

Touch works; Vol keys move focus, a short Power press clicks.
Hold Power for 3 s to reboot back into the installed system.
Needs an unlocked bootloader.
EOF
}
