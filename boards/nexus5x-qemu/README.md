# nexus5x-qemu: the Nexus 5X kernel on QEMU

<img src="../../docs/nexus5x-qemu.gif" width="240" alt="Demo on the Nexus 5X kernel in QEMU" align="right">

The phone's own kernel (LineageOS 15.1, 3.10, with QEMU-only patches) on
QEMU's arm64 `virt` machine, with a 1080x1920 framebuffer like the phone's
panel. It catches what the generic [qemu-virt](../qemu-virt/README.md)
board can't: the phone's old kernel (no `rseq`, no `clone3`) at the
phone's resolution, without the phone.

**Test result:** 10 minutes of random taps, drags and keys, then 3 minutes
of animation: PASS. That means no crash or restart, flat memory, and a
pixel-identical screen when a reference sequence is replayed.

## Run it

**Prebuilt:** `embedded-compose-nexus5x-qemu.tar.gz` from
[Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases)
needs only `qemu-system-arm`:

```sh
tar xzf embedded-compose-nexus5x-qemu.tar.gz
embedded-compose-nexus5x-qemu/run.sh          # HEADLESS=1 for console only
```

**From source:** needs the QEMU kit of
[nexus5x-recovery-roadfighter](https://github.com/kaiwalyakhasnis/nexus5x-recovery-roadfighter).
Its `qemu/setup.sh` builds the patched kernel from a LineageOS 15.1 tree
into a WORK directory; then run `SMP=6 MEM=2048 qemu/gen-dtb.sh`. Point to
that directory in `boards/nexus5x-qemu/local.env`:

```sh
echo 'NEXUS5X_QEMU_KIT=<WORK directory>' > boards/nexus5x-qemu/local.env
./ec build nexus5x-qemu
./ec run nexus5x-qemu                         # mouse = touch
./ec test nexus5x-qemu --minutes 10
```
