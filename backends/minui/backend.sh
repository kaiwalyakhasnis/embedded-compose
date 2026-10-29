# What ./ec needs to know about the minui backend (sourced with the board).
#
# backend_rootfs: its files in the root filesystem, as mkcpio list lines.
# minui loads its font and every image from /res/images.
#   FONT        board setting: 12x22 or 18x32 (AOSP recovery picks 18x32 for
#               xxhdpi screens and 12x22 below)
#   APP_IMAGES  the app's PNGs (apps/<app>/app.env), paths relative to the repo
#   APP_FONTS   the app's TrueType fonts, put in /res/fonts (ui.material's Roboto)
backend_rootfs() {
  fonts=$EC_ROOT/third_party/aosp/bootable/recovery/fonts
  [ -f "$fonts/$FONT.png" ] || die "no minui font '$FONT' in third_party/aosp/bootable/recovery/fonts"
  echo "dir /res 0755 0 0"
  echo "dir /res/images 0755 0 0"
  echo "file /res/images/font.png $fonts/$FONT.png 0644 0 0"
  for img in $APP_IMAGES; do
    echo "file /res/images/${img##*/} $EC_ROOT/$img 0644 0 0"
  done
  [ -n "$APP_FONTS" ] && echo "dir /res/fonts 0755 0 0"
  for f in $APP_FONTS; do
    echo "file /res/fonts/${f##*/} $EC_ROOT/$f 0644 0 0"
  done
}
