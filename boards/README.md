# Boards

A board is one directory, `boards/<name>/`, with a `board.sh` that `./ec`
sources. A board describes a device: its kernel, its display settings, how
to pack and boot an image, and the environment the app runs in. Nothing
outside the board's directory knows about the device.

## board.sh

Variables:

| | |
|---|---|
| `BOARD_DESC` | one line for `./ec boards` |
| `ARCH` | `arm64` (selects the cross compiler and the Kotlin/Native target) |
| `BACKEND` | display backend: a directory under `backends/` (`minui`) |
| `BACKEND_FLAGS` | backend build flags, e.g. minui's pixel format (`-DRECOVERY_BGRA`) |
| `DENSITY` | pixels per dp (420 dpi phone: 2.625) |
| `FONT` | minui font: `12x22` or `18x32` (`third_party/aosp/bootable/recovery/fonts`) |
| `EC_ENV` | extra `NAME=value` lines exported to the app by `/init` (below) |
| `BOARD_INIT` | optional shell snippet in this directory, run by `/init` before the app |
| `BOARD_EMULATED` | `1` if `board_run` is QEMU through `qemu_boot`: enables `./ec test` and `./ec shot` |

Functions:

| | |
|---|---|
| `board_image` | after the initramfs (`$INITRAMFS`) is built: make whatever the board boots (a boot.img, or nothing for QEMU) |
| `board_run` | boot it: `qemu_boot <kernel> <cmdline> <machine args>` (tools/lib/qemu.sh), or deploy to hardware |

Available to them: `$EC_ROOT`, `$BOARD`, `$BOARD_DIR`, `$BOARD_OUT`
(`out/<board>`), `$INITRAMFS`, and the helpers in `tools/lib/`. Those helpers
include `dep_kernel` (a mainline kernel from kernel.org plus a config
fragment), `dep_busybox`, `fetch` (checksummed download) and `die`/`note`.

Paths that exist only on one machine (a kernel image, an SDK) are read from
`boards/<board>/local.env`, which git ignores. `board.sh` documents the
names and fails with a clear message when they are missing.

## The root filesystem

Every board gets the same initramfs layout from `./ec`:

```
/init                tools/rootfs/init.sh: mounts, /dev nodes, waits for the display, runs the app
/bin/busybox         static busybox (built from source)
/bin/embedded-compose
/etc/ec.env          EC_DENSITY + the board's EC_ENV
/etc/board-init.sh   the board's BOARD_INIT, if any
/res/images/         minui backend: font.png (the board's FONT) and the app's images
```

Settings `/init` and the app understand (through `EC_ENV`):

| | |
|---|---|
| `EC_LOG=kmsg` | app output to the kernel log (devices without a console) |
| `EC_WAIT_INPUT=<name>` | also wait for an input device whose name matches (a slow touchscreen) |
| `EC_WAIT_SECS=<n>` | how long to wait for display/input (default 10) |
| `EC_POWER_REBOOT=1` | holding POWER for 3 s reboots (no other way out on a phone) |
| `GLIBC_TUNABLES=glibc.pthread.rseq=0` | kernels older than 4.18 (no rseq) |
| `EC_TRACE=1` | log frames, input, clicks and test-tag bounds (QEMU boards: `TRACE=1`) |
| `EC_DEV_CAPTURE=1` | dev: copy each flipped frame to `/tmp/ec-frame` on request, for tear-free screen capture |

## Adding a board

Copy the closest board. A new Linux device usually needs only:

1. A kernel. Use `dep_kernel` with a config fragment, or point to a vendor
   kernel through `local.env`.
2. `BACKEND_FLAGS` for its framebuffer's byte order. Check
   `/sys/class/graphics/fb0/` or the DRM driver's formats.
3. `DENSITY` and `FONT` for its screen.
4. `board_image`/`board_run` for how it boots: QEMU, `fastboot boot`, an SD
   card image, ...

A display that minui can't drive (an SPI panel with RGB565, an LED matrix) is
a new backend under `backends/`, not a board change. It implements the display
half of `core/hal/ec_hal.h` and reuses `backends/linux`.
