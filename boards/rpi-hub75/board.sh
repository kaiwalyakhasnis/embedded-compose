# Raspberry Pi with HUB75 LED panels on its GPIO header, running Raspberry Pi
# OS. The app is a program there (BOARD_OS=1: ./ec build makes a bundle, not
# an initramfs) and draws through hzeller's rpi-rgb-led-matrix
# (backends/hub75). Pi 3, 4, 5 or Zero 2 W with the 64-bit OS (Lite is
# enough): Kotlin/Native and the Compose runtime have no 32-bit ARM target.
#
# In boards/rpi-hub75/local.env:
#   PI=<user@host>      ssh login of the Pi: ./ec run copies the bundle there
#                       and runs it, ./ec shot fetches what the panel shows
#   HUB75_FLAGS='...'   panel and wiring as the library's --led- flags
#                       (default: one 64x32 panel, "regular" wiring)
BOARD_DESC="Raspberry Pi + HUB75 LED matrix, on Raspberry Pi OS 64-bit (deploy over ssh)"
BOARD_OS=1
BOARD_APP=matrix

ARCH=arm64
BACKEND=hub75
DENSITY=1                       # a dp is an LED
FONT=5x7
HUB75_FLAGS=${HUB75_FLAGS:---led-rows=32 --led-cols=64 --led-gpio-mapping=regular --led-slowdown-gpio=2}
EC_ENV="
EC_HUB75='$HUB75_FLAGS'
EC_DEV_CAPTURE=1
"
# EC_DEV_CAPTURE=1: the frame on the panel can be fetched (./ec shot).

board_image() {
  cat > "$BUNDLE/README.txt" <<EOF
embedded-compose: $BOARD_DESC

Needs Raspberry Pi OS (64-bit; Lite is enough) on a Pi 3, 4, 5 or Zero 2 W,
and a HUB75 panel on the GPIO header, wired directly or through a HAT or
bonnet: https://github.com/hzeller/rpi-rgb-led-matrix/blob/master/wiring.md

Run it (as root; Ctrl-C stops it and turns the panel off):
  ./run.sh

Panel and wiring: EC_HUB75 in ec.env, as the library's flags. Now:
  $HUB75_FLAGS
  Adafruit HAT/bonnet: --led-gpio-mapping=adafruit-hat; two chained panels:
  --led-chain=2; flicker or garbage: raise --led-slowdown-gpio (Pi 4: 2-4).

Required: dtparam=audio=off in /boot/firmware/config.txt, then reboot.
  The Pi's sound driver uses the PWM hardware that times the panel, and the
  library exits at start while snd_bcm2835 is loaded (or add
  --led-no-hardware-pulse to the flags, at the cost of more flicker).

Recommended: isolcpus=3 at the end of /boot/firmware/cmdline.txt: a core
  of its own for the refresh thread, less flicker.

Input: any evdev device. A USB keyboard works; buttons on GPIO pins the
panel leaves free need the gpio-key overlay, e.g. in config.txt:
  dtoverlay=gpio-key,gpio=<pin>,keycode=106,label=RIGHT
The matrix app: Left/Right change the page, Enter changes the page's style.

Start at boot: see embedded-compose.service. Stop that service before
using ./run.sh, as both would drive the panel.

The binary includes rpi-rgb-led-matrix (GPL-2.0; source:
https://github.com/hzeller/rpi-rgb-led-matrix/tree/$RGBMATRIX_COMMIT),
so the binary as a whole is distributed under the GPL-2.0.
EOF
}

need_pi() {
  [ -n "$PI" ] || die "set PI=<user@host> (ssh login of the Pi) in boards/$BOARD/local.env"
}

board_run() {
  need_pi
  note "copying the bundle to $PI:embedded-compose/"
  tar -C "$BUNDLE/.." -czf - embedded-compose | ssh "$PI" 'rm -rf embedded-compose && tar -xzf -'
  note "running it on $PI (Ctrl-C stops it)"
  exec ssh -t "$PI" ./embedded-compose/run.sh
}

# What the panel shows (EC_DEV_CAPTURE), as a PNG with 8x8 screen pixels per
# LED. The app writes the next frame it shows; a still screen keeps the last.
board_shot() {
  need_pi
  mkdir -p "$(dirname "$1")"
  ssh "$PI" 'touch /tmp/ec-capture; i=0
    while [ -e /tmp/ec-capture ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
    cat /tmp/ec-frame.ppm' > "$BOARD_OUT/frame.ppm" || die "no frame from $PI (is the app running?)"
  python3 -c 'import sys; from PIL import Image
im = Image.open(sys.argv[1]); im.resize((im.width * 8, im.height * 8), Image.NEAREST).save(sys.argv[2])' \
    "$BOARD_OUT/frame.ppm" "$1"
  echo "$1"
}

board_package() {
  cp -r "$BUNDLE/." "$1/"
}
