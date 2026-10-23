# qemu-virt: generic arm64 QEMU

<img src="../../docs/qemu-virt.gif" width="240" alt="Demo on QEMU virt with mainline Linux" align="right">

QEMU's arm64 `virt` machine with a mainline kernel (Linux 6.12.112 from
kernel.org, arm64 defconfig plus `kernel.config`) and a virtio-gpu screen
of 720x1280. Nothing in it is specific to any phone: it is the reference
board for development and UI tests, and needs no hardware.

**Test result:** 10 minutes of random taps, drags and keys, then 3 minutes
of animation: PASS. That means no crash or restart, flat memory, and a
pixel-identical screen when a reference sequence is replayed.

## Run it

**Prebuilt:** `embedded-compose-qemu-virt.tar.gz` from
[Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases)
needs only `qemu-system-arm`:

```sh
tar xzf embedded-compose-qemu-virt.tar.gz
embedded-compose-qemu-virt/run.sh             # HEADLESS=1 for console only
```

**From source:**

```sh
./ec build qemu-virt              # app, minui, busybox and the kernel (first time ~15 min)
./ec run qemu-virt                # window: mouse = touch, arrows/Tab/Enter = keys
./ec test qemu-virt --minutes 10  # headless reliability test
./ec record qemu-virt my.gif      # the demo tour as a GIF
APP=charger ./ec build qemu-virt  # the charging screen, with a simulated battery
```

## Notes

- virtio-gpu's fbdev is XRGB8888 (bytes B, G, R, X), so the board builds
  minui with `-DRECOVERY_BGRA`.
- `board-init.sh` hides `/dev/dri`: virtio-gpu's DRM planes only scan out
  XRGB8888, which AOSP 8.1's minui can't request, so minui uses the fbdev
  emulation instead.
- The console is on virtio-console (`hvc0`), and the QMP socket in
  `out/qemu-virt/` serves `./ec shot`, `./ec test` and `./ec record`.
