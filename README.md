# embedded-compose

Jetpack Compose's runtime on bare embedded Linux. There is no Android
framework, no JVM and **no Skia**: the UI is drawn straight into the kernel's
display device, **fbdev or DRM, whichever the device has**. The whole UI is
one ~3 MB static binary that boots from a 2.4 MB initramfs.

| Nexus 5X (real phone) | Nexus 5X kernel on QEMU | Generic arm64 QEMU (mainline Linux) |
|:---:|:---:|:---:|
| <img src="docs/nexus5x.gif" width="240" alt="Demo on a Nexus 5X"> | <img src="docs/nexus5x-qemu.gif" width="240" alt="Demo on the Nexus 5X kernel in QEMU"> | <img src="docs/qemu-virt.gif" width="240" alt="Demo on QEMU virt with mainline Linux"> |
| captured from the phone's framebuffer | 1080x1920, QEMU software CPU | 720x1280, QEMU software CPU |

<sub>Same binary and the same scripted tour on all three: counter, accent colour,
shapes, frame animation, `animateFloatAsState`, dragging. The QEMU GIFs run at
emulator speed, not the device's.</sub>

## How it works

```
@Composable UI          apps/demo          remember, mutableStateOf, LaunchedEffect, ...
androidx.compose.runtime                   Recomposer, snapshots, frame clock (official artifact)
toolkit                 core/ui            LayoutNode tree, Box/Row/Column/Text/Canvas/Button, Modifiers
platform contract       core/hal/ec_hal.h  fill, text, image, flip, input events, clock (C)
backend                 backends/minui     AOSP recovery's minui (vendored, unmodified)
kernel                  /dev/fb0 or DRM, /dev/input/event*
```

1. Kotlin/Native compiles the app, the Compose runtime and the toolkit to
   one static library. The Compose compiler plugin turns `@Composable`
   functions into calls on the runtime's `Composer`.
2. The toolkit plugs into the runtime as an `Applier`. Composition builds and
   updates a tree of `LayoutNode`s, which are measured, placed and drawn.
3. Drawing is spans of pixels. Shapes become scanlines (anti-aliased at the
   edges), sent to minui's `gr_fill`/`gr_blit`/`gr_text`. minui writes them
   into the framebuffer it found (ADF, DRM or fbdev) and flips.
4. A single-threaded event loop drives everything: recomposition,
   `withFrameNanos` frames at 60 Hz when something animates, and touch/keys
   from evdev. When nothing changes, it sleeps.

## How lean

Measured on the Nexus 5X (Snapdragon 808, 1080x1920) running the demo:

| | |
|---|---|
| UI binary | 2.9 MB (static, stripped: app + Compose runtime + minui + libpng/zlib) |
| Boot image ramdisk | 2.4 MB (binary + busybox + init + font) |
| App memory (RSS) | 7 MB idle after start, ~12 MB while animating |
| Whole system in use | ~140 MB of 1.8 GB, kernel included (no Android: init, the app, nothing else) |
| Animation | ~53 fps, 19 ms per frame (software rendering, one CPU core) |
| Boot to first frame | ~3 s after the kernel starts |

## Tested on

| Board | How | Result |
|---|---|---|
| **LG Nexus 5X**, physical | `fastboot boot` (RAM only, nothing flashed), LineageOS 15.1 kernel 3.10 | runs. Touch, keys and animation work, and the GIF above was captured from it |
| `nexus5x-qemu` | the phone's 3.10 kernel on QEMU, 1080x1920 | 10 min random input + 3 min animation: PASS |
| `qemu-virt` | mainline Linux 6.12 on QEMU, virtio-gpu, 720x1280 | 10 min random input + 3 min animation: PASS |

A test "PASS" means: no crash or restart, memory flat, and after a long run
of random taps, drags and keys, a replayed sequence gives a pixel-identical
screen.

## Second app: a Compose charging screen

<p align="center"><img src="docs/charger-nexus5x.gif" width="270" alt="Compose charging animation on a Nexus 5X"></p>

<sub>Recorded from the Nexus 5X's framebuffer, with the simulated battery to show a whole charge cycle.</sub>

`apps/charger` replaces Android's off-mode charging screen (`/sbin/charger`).
A glass orb fills with liquid (two waves, rising bubbles). Around it, a
glowing ring with a spark at its tip and an orbiting comet. Below, the level
in seven-segment digits, a pulsing bolt with energy flowing in, and the
current, voltage and temperature. Colour follows the level, from red to
green. It also does the stock charger's other jobs: it reads
`/sys/class/power_supply`, turns the screen off after 30 s and wakes on
touch or key, powers off 10 s after unplugging, and boots Android when
Power is held for 3 s.

On the phone it renders at ~30 fps (33 ms per frame). Large areas use
opaque, pre-mixed colours, because minui draws straight into uncached
framebuffer memory, where every translucent pixel costs a slow read.

```sh
APP=charger ./ec build qemu-virt && ./ec run qemu-virt     # simulated battery: Up/Down, Enter = plug
APP=charger ./ec build nexus5x && ./ec run nexus5x         # real battery, our minimal system
```

**In LineageOS's real off-mode charging**, tested on the Nexus 5X:
`boards/nexus5x/lineage-charger.py` makes a patched **copy** of a LineageOS 15.1
`boot.img`. It replaces `/sbin/charger`, adds the font, and sets the app's
environment in the `charger` service. With `--charger-mode` it also adds
`androidboot.mode=charger`, so a `fastboot boot` goes straight into
charging mode, RAM only:

```sh
APP=charger ./ec build nexus5x
boards/nexus5x/lineage-charger.py lineage/boot.img out/nexus5x/native/embedded-compose boot-charger.img --charger-mode
fastboot boot boot-charger.img      # hold Power 3 s: boots LineageOS
```

Without `--charger-mode`, the image is meant for flashing to `boot`, so it
shows up whenever the phone is charged while off. That's your decision to
make; nothing here flashes.

## Try it

**Prebuilt:** [Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases) has a
`fastboot boot` image for the Nexus 5X and ready-to-run archives for both QEMU
boards (`./run.sh`, needs only `qemu-system-arm`).

**From source.** Requirements (Ubuntu/Debian):

```sh
sudo apt install g++-aarch64-linux-gnu qemu-system-arm python3-pil openjdk-17-jdk-headless \
                 curl make bc bison flex libssl-dev   # the last ones build the kernel
sudo apt install adb fastboot                          # only for the Nexus 5X
```

Gradle downloads Kotlin/Native and the Compose runtime on first use.

**In QEMU (no hardware needed):**

```sh
./ec build qemu-virt              # app, minui, busybox and a mainline kernel (first time ~15 min)
./ec run qemu-virt                # window: mouse = touch, arrows/Tab/Enter = keys
./ec test qemu-virt --minutes 10  # headless reliability test
./ec record qemu-virt my.gif      # the tour above as a GIF
```

**On a Nexus 5X** (bootloader unlocked). Nothing is written to the phone,
and a reboot returns it to its own system:

```sh
./ec build nexus5x                # also builds LineageOS 15.1's kernel from source
adb reboot bootloader
./ec run nexus5x                  # fastboot boot
```

`EC_DEV_USB=1 ./ec build nexus5x` adds a USB network shell for development.

Hold **Power for 3 s** to leave. With `EC_DEV_USB=1`, `boards/nexus5x/phone.py`
gives you the log, memory stats, screenshots and the GIF tour over USB.

**On another device:** add a board. `boards/<name>/board.sh` sets the kernel,
pixel format, density and font, and says how to boot it. See
[boards/README.md](boards/README.md). A display that minui can't drive (a 16-bit
SPI panel, an LED matrix) needs a new backend: implement the display half of
`core/hal/ec_hal.h`.

## What the toolkit supports

Compose-style API: `Box`, `Row`, `Column`, `Spacer`, `Text`, `Canvas`, `Image`, `Button`.
Modifiers: `padding`, `size`, `fillMax*`, `weight`, `offset`, `background`,
`border`, `clickable`, `pointerInput`, `drawBehind`, `testTag`. Canvas: rects,
round rects, circles, ovals, lines, polygons and arcs, filled or stroked,
with alpha. The whole Compose runtime is available: state, effects,
`withFrameNanos`, `animateFloatAsState`.

Not yet: scrolling, clipping, text input, scalable fonts, transforms,
Material components. This is Compose's runtime with a small toolkit, not
Compose UI.

## Layout

```
ec                 build/run/test driver (./ec help)
apps/demo/         the demo app
apps/charger/      off-mode charging screen (APP=charger)
core/ui/           toolkit (Kotlin)          core/hal/  platform contract (C)
backends/          linux/ (evdev, clock, main), minui/ (display)
boards/            qemu-virt/, nexus5x/, nexus5x-qemu/: everything device-specific
tools/             init, cpio writer, QEMU launcher, UI test, GIF tour
third_party/aosp/  minui + libpng/zlib/libdrm from AOSP android-8.1.0_r52, unmodified
```

Third-party code keeps its own licences: AOSP sources under Apache 2.0 and
their own notices (`third_party/aosp/`), and the fonts under OFL. Busybox and
Linux are downloaded and built at build time, not included.
