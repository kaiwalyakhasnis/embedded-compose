# QEMU launcher shared by emulated boards. What every emulated board offers
# (and tools/uitest.py / tools/qmp.py rely on):
#   console on virtio-console hvc0: this terminal, or with HEADLESS=1
#                                   out/<board>/console.log
#   QMP socket                      out/<board>/qmp.sock (screenshots, input)
#   input                           virtio keyboard + tablet (mouse = touch)
#   TRACE=1                         app logs every frame, input, click, test tag
#
# qemu_boot <kernel> <extra kernel cmdline> <qemu machine args...>
qemu_boot() {
  kernel=$1 append=$2
  shift 2
  case $ARCH in
    arm64) qemu=qemu-system-aarch64 ;;
    *) die "no QEMU for ARCH '$ARCH'" ;;
  esac
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
