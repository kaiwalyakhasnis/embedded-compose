# Shared by the LED-matrix backends' backend.sh (hub75).
#
# backend_rootfs: the app's PNGs in /res/images (apps/<app>/app.env:
# APP_IMAGES). The font is built in: FONT must be 5x7.
backend_rootfs() {
  [ "$FONT" = 5x7 ] || die "the $BACKEND backend has one font: set FONT=5x7 in boards/$BOARD/board.sh"
  echo "dir /res 0755 0 0"
  echo "dir /res/images 0755 0 0"
  for img in $APP_IMAGES; do
    echo "file /res/images/${img##*/} $EC_ROOT/$img 0644 0 0"
  done
}
