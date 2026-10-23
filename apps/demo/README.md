# demo

The default app on phone-style boards. It shows what the toolkit does, in
three tabs:

- **Basics:** a counter and buttons, and an accent colour that the whole app
  follows.
- **Shapes:** rects, rounded rects, circles, ovals, polygons and arcs,
  filled and stroked, with alpha.
- **Motion:** a frame animation (`withFrameNanos`), `animateFloatAsState`,
  and a ball you can drag.

It runs on [nexus5x](../../boards/nexus5x/README.md),
[nexus5x-qemu](../../boards/nexus5x-qemu/README.md) and
[qemu-virt](../../boards/qemu-virt/README.md). Its elements carry test
tags, so `./ec test` (random input, then a pixel-identical replay) and
`./ec record` (the scripted tour) find them at any screen size.
