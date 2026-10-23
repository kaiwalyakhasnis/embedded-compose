# Raspberry Pi with MAX7219 LED modules (a "4 in 1" 32x8 board, FC-16) on
# its SPI pins, running Raspberry Pi OS. The app is a program there
# (BOARD_OS=1: ./ec build makes a bundle, not an initramfs) and sends its
# frames through the kernel's spidev driver (backends/max7219). Pi 3, 4, 5
# or Zero 2 W with the 64-bit OS (Lite is enough): Kotlin/Native and the
# Compose runtime have no 32-bit ARM target.
#
# In boards/rpi-max7219/local.env:
#   PI=<user@host>        ssh login of the Pi: ./ec run copies the bundle
#                         there and runs it, ./ec shot fetches what it shows
#   MAX7219_FLAGS='...'   the modules, as EC_MAX7219 (backends/max7219/max7219.cpp)
#                         (default: one 4-in-1 board on /dev/spidev0.0)
BOARD_DESC="Raspberry Pi + MAX7219 LED modules (32x8), on Raspberry Pi OS 64-bit (deploy over ssh)"
BOARD_OS=1
BOARD_APP=matrix

ARCH=arm64
BACKEND=max7219
DENSITY=1                       # a dp is an LED
FONT=5x7
MAX7219_FLAGS=${MAX7219_FLAGS:-dev=/dev/spidev0.0 modules=4 intensity=2}
EC_ENV="
EC_MAX7219='$MAX7219_FLAGS'
EC_DEV_CAPTURE=1
"
# EC_DEV_CAPTURE=1: the frame can be fetched (./ec shot).

board_image() {
  cat > "$BUNDLE/README.txt" <<EOT
embedded-compose: $BOARD_DESC

Needs Raspberry Pi OS (64-bit; Lite is enough) on a Pi 3, 4, 5 or Zero 2 W,
with SPI on: dtparam=spi=on in /boot/firmware/config.txt (or
raspi-config > Interface Options > SPI), then reboot.

Wiring (module -> Pi header):
  VCC -> 5V (pin 2)      GND -> GND (pin 6)
  DIN -> MOSI, GPIO10 (pin 19)
  CS  -> CE0, GPIO8 (pin 24)
  CLK -> SCLK, GPIO11 (pin 23)

Run it (as root; Ctrl-C stops it and turns the LEDs off):
  ./run.sh

The modules: EC_MAX7219 in ec.env. Now:
  $MAX7219_FLAGS
  modules=<n>    8x8 modules in the chain (a 4-in-1 board: 4)
  intensity=<n>  brightness, 0-15
  threshold=<n>  an LED is on where the brightest colour channel is at
                 least this (1-255, default 128)
  flip=1         mounted with the pins on the right
  block=<deg>    90, 180 or 270 if letters are turned within each 8x8

Input: any evdev device. A USB keyboard works; buttons on GPIO pins need
the gpio-key overlay, e.g. in config.txt:
  dtoverlay=gpio-key,gpio=<pin>,keycode=106,label=RIGHT
The matrix app: Left/Right change the page, Enter changes the page's style.

Start at boot: see embedded-compose.service. Stop that service before
using ./run.sh, as both would drive the modules.
EOT
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

# What the modules show (EC_DEV_CAPTURE), as a PNG with 8x8 screen pixels
# per LED: the app's frame, lit where it reaches the threshold. The app
# writes the next frame it shows; a still screen keeps the last.
board_shot() {
  need_pi
  mkdir -p "$(dirname "$1")"
  ssh "$PI" 'touch /tmp/ec-capture; i=0
    while [ -e /tmp/ec-capture ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
    cat /tmp/ec-frame.ppm' > "$BOARD_OUT/frame.ppm" || die "no frame from $PI (is the app running?)"
  threshold=$(echo "$MAX7219_FLAGS" | sed -n 's/.*threshold=\([0-9]*\).*/\1/p')
  python3 -c 'import sys; from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); t = int(sys.argv[3])
im = im.point(lambda v: 255 if v >= t else 0).convert("L").point(lambda v: 255 if v else 0)
red = Image.merge("RGB", (im, im.point(lambda v: v // 6), im.point(lambda v: v // 8)))
red.resize((red.width * 8, red.height * 8), Image.NEAREST).save(sys.argv[2])' \
    "$BOARD_OUT/frame.ppm" "$1" "${threshold:-128}"
  echo "$1"
}

board_package() {
  cp -r "$BUNDLE/." "$1/"
}
