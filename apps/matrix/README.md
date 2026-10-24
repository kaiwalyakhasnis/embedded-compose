# matrix: an app for LED panels

<img src="../../docs/hub75-qemu-rpi3.png" width="420" alt="The matrix app's four pages" align="right">

Made for one 64x32 HUB75 panel, with one dp per LED. It is laid out from
the screen size, so bigger matrices work too, and strips 8 LEDs high (MAX7219
modules, 32x8) get one line of text: the clock without the date, the
marquee without its waves. Four pages:

- **Clock:** HH:MM with a blinking colon, the date, and a seconds bar. It
  redraws once a second and idles in between.
- **Plasma:** four sine fields per LED, through a cycling palette.
- **Life:** Conway's Game of Life, coloured by age. It starts over when it
  settles.
- **Marquee:** a message in rainbow letters between two waves.

The scrolling message and the page dots are `ui.led.Marquee` and
`ui.led.PageDots`, from `core/led`.

**Left/Right** (or Up/Down, or the volume keys) change the page, and
**Enter** changes the page's style: colours, palette, a new Life pattern,
or the marquee's speed. Any evdev input works: a USB keyboard, or buttons
on GPIO (see [rpi-hub75](../../boards/rpi-hub75/README.md#buttons)).

It is the default app on [rpi-hub75](../../boards/rpi-hub75/README.md),
[rpi-max7219](../../boards/rpi-max7219/README.md) and
[qemu-rpi3](../../boards/qemu-rpi3/README.md). Text uses the LED backend's
built-in 5x7 font.
