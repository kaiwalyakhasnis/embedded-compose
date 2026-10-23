#!/bin/sh
# Runs embedded-compose on a board's own Linux (a bundle from ./ec build, for
# boards with BOARD_OS=1), as /init does on our own images: the board's
# environment and hook, then the app. In the foreground, as root (the app
# drives the display and reads /dev/input directly); Ctrl-C stops it.
here=$(cd "$(dirname "$0")" && pwd)
[ "$(id -u)" = 0 ] || exec sudo "$here/run.sh" "$@"
. "$here/ec.env"
export EC_RES="$here/res/images"   # the app's images
[ -f "$here/board-init.sh" ] && . "$here/board-init.sh"
exec "$here/embedded-compose"
