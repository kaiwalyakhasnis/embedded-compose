# rpi-hub75: HUB75 LED panels on a Raspberry Pi

<img src="../../docs/hub75-qemu-rpi3.png" width="420" alt="The matrix app's four pages on a 64x32 LED matrix" align="right">

A 64x32 RGB LED panel (HUB75) on a Raspberry Pi's GPIO header, running the
[`matrix`](../../apps/matrix/README.md) app. The `hub75` backend draws each
frame into a buffer in RAM and hands it to
[rpi-rgb-led-matrix](https://github.com/hzeller/rpi-rgb-led-matrix), whose
real-time thread refreshes the panel. Frames swap at the panel's vertical
sync. The app runs as a program on Raspberry Pi OS, from a bundle that
`./ec` copies over ssh.

**Status:** builds, and the same binary runs on
[QEMU's Raspberry Pi 3](../qemu-rpi3/README.md) (the picture comes from
there). **Not yet tried on a real Pi and panel.**

## Hardware

| Part | Notes |
|---|---|
| Raspberry Pi 3, 4, 5 or Zero 2 W | 64-bit Raspberry Pi OS needed: there is no 32-bit build |
| 64x32 HUB75 panel | indoor, P3 or P4 pitch |
| An adapter board (e.g. Adafruit RGB Matrix Bonnet/HAT) | Recommended: it shifts the Pi's 3.3 V to the panel's 5 V. Direct wiring also works; see the library's [wiring.md](https://github.com/hzeller/rpi-rgb-led-matrix/blob/master/wiring.md) |
| 5 V, 4 A supply for the panel | the Pi can't power the panel |
| Buttons (optional) | a USB keyboard works too |

## Set up the Pi

1. Install Raspberry Pi OS **Lite (64-bit)** with Raspberry Pi Imager, with
   SSH enabled.
2. In `/boot/firmware/config.txt`, set **`dtparam=audio=off`**, then
   reboot. This is required: the sound driver uses the PWM hardware that
   times the panel, and the library refuses to start while it is loaded.
   Optionally, add ` isolcpus=3` to `/boot/firmware/cmdline.txt` for less
   flicker.
3. Check the panel and its flags with the library's own demo first. This
   keeps wiring problems apart from ours:
   ```sh
   git clone https://github.com/hzeller/rpi-rgb-led-matrix && cd rpi-rgb-led-matrix
   make -C examples-api-use
   sudo examples-api-use/demo -D0 --led-rows=32 --led-cols=64 --led-gpio-mapping=adafruit-hat
   ```

   | Symptom | Flag to try |
   |---|---|
   | garbage, sparkles | `--led-slowdown-gpio=2`, then 3, 4 |
   | half the rows, scrambled rows | `--led-multiplexing=…`, `--led-row-addr-type=…` |
   | colours swapped | `--led-rgb-sequence=RBG` (or another order) |
   | blank on some panels | `--led-panel-type=FM6126A` |

## Run it

**Prebuilt:** copy `embedded-compose-rpi-hub75.tar.gz` from
[Releases](https://github.com/kaiwalyakhasnis/embedded-compose/releases)
to the Pi:

```sh
tar xzf embedded-compose-rpi-hub75.tar.gz && cd embedded-compose-rpi-hub75
nano ec.env      # EC_HUB75: the flags that worked with the demo
./run.sh         # as root (asks for sudo); Ctrl-C stops it and turns the panel off
```

**From source**, deployed from your PC. In `boards/rpi-hub75/local.env`:

```sh
PI=pi@raspberrypi.local                       # ssh login of the Pi
HUB75_FLAGS='--led-rows=32 --led-cols=64 --led-gpio-mapping=adafruit-hat'
```

```sh
./ec build rpi-hub75             # the bundle: out/rpi-hub75/bundle/embedded-compose/
./ec run rpi-hub75               # copies it to the Pi over ssh and runs it
./ec shot rpi-hub75              # PNG of what the panel shows
```

To start it at boot, install `embedded-compose.service` from the bundle;
the file explains how.

## Buttons

Any evdev input device works. A USB keyboard needs no setup. For push
buttons, wire each one between a free GPIO pin and GND, and let Raspberry
Pi OS's `gpio-key` overlay turn it into a key, in `config.txt`:

```
dtoverlay=gpio-key,gpio=19,keycode=105,label="LEFT"
dtoverlay=gpio-key,gpio=26,keycode=106,label="RIGHT"
dtoverlay=gpio-key,gpio=16,keycode=28,label="ENTER"
```

Use pins the panel doesn't (one chain):

| `--led-gpio-mapping` | Pins the panel uses | Free for buttons |
|---|---|---|
| `regular` | 4, 7–11, 17, 18, 22–25, 27 (+15 for 64-row panels) | 5, 6, 12, 13, 16, 19, 20, 21, 26 |
| `adafruit-hat` | 4–6, 12, 13, 16, 17, 20–23, 26, 27 (+24 for 64-row panels) | 19, 25; 7–11 if SPI is off |

## Licence

rpi-rgb-led-matrix (GPL-2.0) is downloaded and built by
`backends/hub75/backend.sh`, at a pinned commit, and linked into the
binary. A binary built for this board is therefore covered by the GPL-2.0.
