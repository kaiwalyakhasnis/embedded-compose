# QEMU launcher shared by emulated boards. What every emulated board offers
# (and tools/uitest.py / tools/qmp.py rely on):
#   console on virtio-console hvc0: this terminal, or with HEADLESS=1
#                                   out/<board>/console.log
#   QMP socket                      out/<board>/qmp.sock (screenshots, input)
#   input                           virtio keyboard + tablet (mouse = touch)
#   TRACE=1                         app logs every frame, input, click, test tag
#
# qemu_boot <kernel> <extra kernel cmdline> <qemu machine args...>
#
# With EC_QEMU_SCRIPT=<dir>/run.sh it writes a standalone script instead of
# running QEMU, and copies the kernel, initramfs and any file argument (a
# .dtb) next to it (./ec package).
qemu_boot() {
  kernel=$1 append=$2
  shift 2
  case $ARCH in
    arm64) qemu=qemu-system-aarch64 ;;
    *) die "no QEMU for ARCH '$ARCH'" ;;
  esac
  if [ -n "$EC_QEMU_SCRIPT" ]; then
    qemu_script "$kernel" "$append" "$@"
    return
  fi
  rm -f "$BOARD_OUT/qmp.sock" "$BOARD_OUT/console.log"
  if [ -n "$HEADLESS" ]; then
    set -- "$@" -display none -chardev "file,id=con,path=$BOARD_OUT/console.log"
  else
    set -- "$@" -display gtk,zoom-to-fit=on -chardev stdio,id=con,mux=on,signal=off
  fi
  exec $qemu "$@" -serial null -monitor none \
    -device virtio-keyboard-device -device virtio-tablet-device \
    -device virtio-serial-device -device virtconsole,chardev=con \
    -qmp "unix:$BOARD_OUT/qmp.sock,server=on,wait=off" \
    -kernel "$kernel" -initrd "$INITRAMFS" \
    -append "console=hvc0 rdinit=/init ec.trace=${TRACE:-0} $append"
}

qemu_script() {
  kernel=$1 append=$2
  shift 2
  dir=$(dirname "$EC_QEMU_SCRIPT")
  cp "$kernel" "$dir/Image"
  cp "$INITRAMFS" "$dir/initramfs.cpio.gz"
  args=
  for a in "$@"; do
    if [ -f "$a" ]; then cp "$a" "$dir/"; a=${a##*/}; fi
    args="$args $a"
  done
  cat > "$EC_QEMU_SCRIPT" <<EOF
#!/bin/sh
# embedded-compose on $BOARD: $BOARD_DESC
# Needs qemu-system-arm (QEMU 8.2 tested). Mouse = touch; arrows/Tab move
# focus, Enter/Space click. The console (kernel + app log) is this terminal.
#   HEADLESS=1 ./run.sh   no window, console only
cd "\$(dirname "\$0")"
display="-display gtk,zoom-to-fit=on"
[ -n "\$HEADLESS" ] && display="-display none"
exec $qemu$args \$display -serial null -monitor none \\
  -chardev stdio,id=con,mux=on,signal=off \\
  -device virtio-keyboard-device -device virtio-tablet-device \\
  -device virtio-serial-device -device virtconsole,chardev=con \\
  -kernel Image -initrd initramfs.cpio.gz \\
  -append "console=hvc0 rdinit=/init ec.trace=0 $append"
EOF
  chmod +x "$EC_QEMU_SCRIPT"
}
