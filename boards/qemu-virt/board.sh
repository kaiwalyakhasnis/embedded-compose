# Generic arm64 QEMU "virt" machine with a mainline kernel built from
# kernel.org: the reference board for development and UI tests. Nothing in it
# is specific to any phone.
BOARD_DESC="QEMU arm64 virt, mainline Linux, virtio-gpu 720x1280 (emulated)"
BOARD_EMULATED=1

ARCH=arm64
BACKEND=minui
# virtio-gpu's fbdev is XRGB8888 (bytes B,G,R,X): minui's BGRA order.
BACKEND_FLAGS=-DRECOVERY_BGRA
SCREEN_W=720
SCREEN_H=1280
DENSITY=1.75          # px per dp: 720 px = 411 dp, a phone-sized layout
FONT=12x22
BOARD_INIT=board-init.sh

KERNEL_VERSION=6.12.112
KERNEL_SHA256=164dc9d1f6c93c61a15e1f071c48379b467f2b17c469cce7223471968208ed03

board_kernel() {
  dep_kernel $KERNEL_VERSION $KERNEL_SHA256 "$BOARD_DIR/kernel.config" "$BOARD_OUT/kernel"
}

# The "image" is the kernel + initramfs that QEMU boots directly.
board_image() {
  board_kernel >/dev/null
}

# force-legacy=false: QEMU's virtio-mmio is legacy (v1) by default, and
# mainline's virtio-gpu and virtio-input drivers only bind to modern devices.
board_run() {
  qemu_boot "$(board_kernel)" "" \
    -M virt -cpu cortex-a57 -smp 4 -m 1024 -global virtio-mmio.force-legacy=false \
    -device "virtio-gpu-device,xres=$SCREEN_W,yres=$SCREEN_H"
}
