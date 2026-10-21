#!/bin/sh
# embedded-compose build/run driver. One app, many boards:
#
#   ./ec boards                     list boards
#   ./ec build <board>              app + backend -> binary -> initramfs -> board image
#   ./ec run   <board>              boot it (QEMU window, or the board's deploy, e.g. fastboot boot)
#   ./ec test  <board> [args]       UI test on an emulated board (tools/uitest.py --help)
#   ./ec shot  <board> [file.png]   screenshot of a running emulated board
#   ./ec record <board> [file.gif]  scripted tour of the demo as a GIF (emulated boards)
#   ./ec package <board>            standalone release archive in out/<board>/
#   ./ec clean [<board>]            delete out/<board> (or all of out/)
#
# Environment: APP=<name> (apps/<name>, default demo), HEADLESS=1, TRACE=1,
# JOBS=<n>. Machine-specific paths go in boards/<board>/local.env (not in git).
set -e
EC_ROOT=$(cd "$(dirname "$0")" && pwd)
. "$EC_ROOT/tools/lib/common.sh"
. "$EC_ROOT/tools/lib/deps.sh"
. "$EC_ROOT/tools/lib/qemu.sh"

APP=${APP:-demo}

usage() { sed -n '2,15s/^# \{0,1\}//p' "$0"; exit "${1:-0}"; }

# Loads boards/<name>/board.sh (+ its local.env) into this shell.
load_board() {
  BOARD=$1
  [ -n "$BOARD" ] || usage 1
  BOARD_DIR=$EC_ROOT/boards/$BOARD
  [ -f "$BOARD_DIR/board.sh" ] || die "no board '$BOARD' (see ./ec boards)"
  BOARD_OUT=$EC_OUT/$BOARD
  BOARD_EMULATED=
  BACKEND_FLAGS= EC_ENV= BOARD_INIT=
  [ -f "$EC_ROOT/local.env" ] && . "$EC_ROOT/local.env"
  [ -f "$BOARD_DIR/local.env" ] && . "$BOARD_DIR/local.env"
  . "$BOARD_DIR/board.sh"
  for v in ARCH BACKEND FONT DENSITY; do
    eval "[ -n \"\$$v\" ]" || die "boards/$BOARD/board.sh does not set $v"
  done
  . "$EC_ROOT/backends/$BACKEND/rootfs.sh"
  APP_DIR=$EC_ROOT/apps/$APP
  [ -d "$APP_DIR" ] || die "no app '$APP' in apps/"
  APP_IMAGES=
  [ -f "$APP_DIR/app.env" ] && . "$APP_DIR/app.env"
  mkdir -p "$BOARD_OUT"
}

# Kotlin static library of the app, for the board's architecture.
build_app() {
  target=$(kotlin_target_for "$ARCH")
  find_java
  note "app '$APP' ($target)"
  (cd "$EC_ROOT" && ./gradlew -q --console=plain ":apps:$APP:linkReleaseStatic$(echo "$target" | sed 's/^./\U&/')")
  KLIB=$EC_OUT/gradle/apps/$APP/bin/$target/releaseStatic/libapp.a
  [ -f "$KLIB" ] || die "missing $KLIB"
}

# One static executable: app + backend, with the board's backend flags.
build_binary() {
  note "binary ($BACKEND backend${BACKEND_FLAGS:+, $BACKEND_FLAGS})"
  make -f "$EC_ROOT/backends/Makefile" -j"$JOBS" --no-print-directory -s \
    BACKEND="$BACKEND" CROSS="$(cross_for "$ARCH")" KLIB="$KLIB" OUT="$BOARD_OUT/native" \
    BACKEND_FLAGS="$BACKEND_FLAGS"
  APP_BIN=$BOARD_OUT/native/embedded-compose
}

# initramfs: busybox, the generic init, the app, the backend's runtime files,
# and the board's environment and init hook.
build_initramfs() {
  busybox=$(dep_busybox)
  {
    echo "EC_DENSITY=$DENSITY"
    echo "$EC_ENV"
  } | sed '/^$/d; s/^/export /' > "$BOARD_OUT/ec.env"
  list=$BOARD_OUT/initramfs.list
  {
    for d in /dev /proc /sys /tmp /bin /etc; do echo "dir $d 0755 0 0"; done
    echo "nod /dev/console 0600 0 0 c 5 1"
    echo "file /init $EC_ROOT/tools/rootfs/init.sh 0755 0 0"
    echo "file /bin/busybox $busybox 0755 0 0"
    echo "file /bin/embedded-compose $APP_BIN 0755 0 0"
    echo "file /etc/ec.env $BOARD_OUT/ec.env 0644 0 0"
    [ -n "$BOARD_INIT" ] && echo "file /etc/board-init.sh $BOARD_DIR/$BOARD_INIT 0644 0 0"
    backend_rootfs
  } > "$list"
  INITRAMFS=$BOARD_OUT/initramfs.cpio.gz
  python3 "$EC_ROOT/tools/mkcpio.py" "$list" "$INITRAMFS"
}

cmd_build() {
  load_board "$1"
  note "board $BOARD: $BOARD_DESC"
  build_app
  build_binary
  build_initramfs
  board_image
  note "done: $(ls -l "$APP_BIN" | awk '{print $5}') byte binary; image in out/$BOARD/"
}

cmd_run() {
  load_board "$1"
  [ -f "$BOARD_OUT/initramfs.cpio.gz" ] || die "build it first: ./ec build $BOARD"
  INITRAMFS=$BOARD_OUT/initramfs.cpio.gz
  board_run
}

cmd_test() {
  load_board "$1"; shift
  [ -n "$BOARD_EMULATED" ] || die "board '$BOARD' is real hardware; UI tests run on emulated boards"
  [ -f "$BOARD_OUT/initramfs.cpio.gz" ] || die "build it first: ./ec build $BOARD"
  exec python3 "$EC_ROOT/tools/uitest.py" --run "$EC_ROOT/ec run $BOARD" --out "$BOARD_OUT" "$@"
}

cmd_record() {
  load_board "$1"
  [ -n "$BOARD_EMULATED" ] || die "board '$BOARD' is real hardware (see its directory for capture tools)"
  [ -f "$BOARD_OUT/initramfs.cpio.gz" ] || die "build it first: ./ec build $BOARD"
  exec python3 "$EC_ROOT/tools/tour.py" --run "$EC_ROOT/ec run $BOARD" --out "$BOARD_OUT" "${2:-$BOARD_OUT/tour.gif}"
}

# A board's built image as a self-contained archive: QEMU boards get their
# kernel, initramfs and a run.sh; other boards provide board_package.
cmd_package() {
  load_board "$1"
  [ -f "$BOARD_OUT/initramfs.cpio.gz" ] || die "build it first: ./ec build $BOARD"
  INITRAMFS=$BOARD_OUT/initramfs.cpio.gz
  name=embedded-compose-$BOARD
  pkg=$BOARD_OUT/package/$name
  rm -rf "$BOARD_OUT/package"
  mkdir -p "$pkg"
  if [ -n "$BOARD_EMULATED" ]; then
    EC_QEMU_SCRIPT=$pkg/run.sh board_run
  else
    board_package "$pkg"
  fi
  (cd "$pkg" && sha256sum $(ls | grep -v SHA256SUMS) > SHA256SUMS)
  tar -C "$BOARD_OUT/package" -czf "$BOARD_OUT/$name.tar.gz" "$name"
  note "$(ls -l "$BOARD_OUT/$name.tar.gz" | awk '{print $5}') bytes: out/$BOARD/$name.tar.gz"
}

cmd_shot() {
  load_board "$1"
  exec python3 "$EC_ROOT/tools/qmp.py" --out "$BOARD_OUT" shot ${2:+"$2"}
}

cmd_boards() {
  for b in "$EC_ROOT"/boards/*/board.sh; do
    d=$(sed -n 's/^BOARD_DESC="\(.*\)"/\1/p' "$b")
    printf '  %-14s %s\n' "$(basename "$(dirname "$b")")" "$d"
  done
}

cmd_clean() {
  if [ -n "$1" ]; then rm -rf "$EC_OUT/$1"; else rm -rf "$EC_OUT"; fi
}

case $1 in
  build|run|test|shot|record|package|clean|boards) c=$1; shift; "cmd_$c" "$@" ;;
  -h|--help|help|"") usage ;;
  *) usage 1 ;;
esac
