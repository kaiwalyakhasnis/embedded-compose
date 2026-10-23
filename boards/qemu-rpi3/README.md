# qemu-rpi3: the HUB75 build on QEMU's Raspberry Pi 3

The [rpi-hub75](../rpi-hub75/README.md) binary, unchanged, on QEMU's
Raspberry Pi 3 model (`-M raspi3b`). The real rpi-rgb-led-matrix drives the
emulated BCM2837 GPIO registers. QEMU has no LED panel, so this checks
everything up to the pins without hardware: the library starts its refresh
thread, frames swap, keys arrive, and SIGTERM turns the panel off.

Every 5 s the console prints what a panel would get:

- `panel GPIO levels: …`: the pins the library's refresh thread drives.
  They change between samples while it scans.
- The frame the app handed to the library, as coloured text, two LED rows
  per line. The picture in the [main README](../../README.md) was made from
  these.

## Run it

**Prebuilt:** `embedded-compose-qemu-rpi3.tar.gz` from
[Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases)
needs only `qemu-system-arm`:

```sh
tar xzf embedded-compose-qemu-rpi3.tar.gz
embedded-compose-qemu-rpi3/run.sh             # HEADLESS=1 for console only
```

**From source:**

```sh
./ec build qemu-rpi3
./ec run qemu-rpi3               # console here
```

The console stays in the terminal. Keys typed into the (black) QEMU window
go to the Pi's USB keyboard: Left/Right change the page, Enter the style.
Hold each key for a moment, because the emulated keyboard misses very short
presses.

## Differences from a real Pi

- `--led-no-hardware-pulse`: QEMU doesn't emulate the PWM block that times
  the panel on a real Pi, so that path is untested here.
- QEMU provides no board revision, so the library reports "Could not
  determine Pi model" and assumes a Pi 3, which is correct here.
- The kernel is [qemu-virt](../qemu-virt/README.md)'s mainline Linux with
  the Pi 3 B device tree, not Raspberry Pi OS. The emulated Pi has no clock
  chip, so `run.sh` passes the host's time in at boot.
