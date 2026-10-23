# nexus5x: LG Nexus 5X (bullhead)

<img src="../../docs/nexus5x.gif" width="240" alt="Demo on a Nexus 5X" align="right">

The demo on a real Nexus 5X. The phone boots our image with
`fastboot boot`, from RAM only: nothing is flashed, and a reboot returns it
to its own system. The kernel is LineageOS 15.1's (3.10), built from
source; the rest of the system is busybox, our init and the app.

Touch works. The volume keys move focus and a short press of Power clicks.
**Hold Power for 3 s** to leave: the phone reboots into its installed
system.

## Run it

Needs an unlocked bootloader, and `adb` and `fastboot`
(`sudo apt install adb fastboot`).

**Prebuilt:** `embedded-compose-nexus5x-boot.img` from
[Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases):

```sh
adb reboot bootloader            # or hold Vol-Down + Power
fastboot boot embedded-compose-nexus5x-boot.img
```

**From source:**

```sh
./ec build nexus5x               # also builds LineageOS 15.1's kernel from source
adb reboot bootloader
./ec run nexus5x                 # fastboot boot
```

The kernel is LineageOS/android_kernel_lge_bullhead at `65a266ae`
(lineage-15.1), plus `patches/` (a host build fix), built with AOSP's
aarch64-linux-android-4.9 GCC. To use another kernel, set `BOOT_IMG` (a
LineageOS boot.img or recovery.img, whose kernel is extracted) or `KERNEL`
(an `Image.gz-dtb`) in `boards/nexus5x/local.env`.

## Development over USB

`EC_DEV_USB=1 ./ec build nexus5x` adds a USB network link (RNDIS,
172.16.42.1) with a root shell, tear-free screen capture and test-tag
tracing. Then `boards/nexus5x/phone.py` works over that link:

```sh
boards/nexus5x/phone.py sh 'CMD'           # run a shell command
boards/nexus5x/phone.py log                # the app's log (from dmesg)
boards/nexus5x/phone.py mem                # system and app memory
boards/nexus5x/phone.py shot out.png       # screenshot from the framebuffer
boards/nexus5x/phone.py tour out.gif       # the demo tour as a GIF
boards/nexus5x/phone.py record out.mp4 30  # 30 s of the screen, any app
```

## How lean

Measured on the phone (Snapdragon 808, 1080x1920) running the demo:

| | |
|---|---|
| UI binary | 2.9 MB (static, stripped: app + Compose runtime + minui + libpng/zlib) |
| Boot image ramdisk | 2.4 MB (binary + busybox + init + font) |
| App memory (RSS) | 7 MB idle after start, ~12 MB while animating |
| Whole system in use | ~140 MB of 1.8 GB, kernel included |
| Animation | ~53 fps, 19 ms per frame (software rendering, one CPU core) |
| Boot to first frame | ~3 s after the kernel starts |

## The charging screen in LineageOS

[`apps/charger`](../../apps/charger/README.md) can replace LineageOS's own
off-mode charging screen. This was tested on the phone.
`lineage-charger.py` makes a patched **copy** of a LineageOS 15.1
`boot.img`: it replaces `/sbin/charger`, adds the font, and sets the app's
environment in the `charger` service. With `--charger-mode` it also adds
`androidboot.mode=charger`, so a `fastboot boot` goes straight into
charging mode, RAM only:

```sh
APP=charger ./ec build nexus5x
boards/nexus5x/lineage-charger.py lineage/boot.img out/nexus5x/native/embedded-compose boot-charger.img --charger-mode
fastboot boot boot-charger.img   # hold Power 3 s: boots LineageOS
```
