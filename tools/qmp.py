#!/usr/bin/env python3
"""Drives a running emulated board over QMP: screenshots, touch and keys.
Works for any emulated board and screen size (see tools/lib/qemu.sh).

  qmp.py --out out/<board> shot [file.png]   screenshot (default <out>/shots/latest.png)
  qmp.py --out out/<board> tap X Y           tap at screen pixel X,Y (virtio tablet)
  qmp.py --out out/<board> key NAME          press+release a key (QEMU qcode: down, ret, tab, ...)

(./ec shot <board> is the short form of the first one.)
"""
import argparse, json, os, socket, tempfile, time

TABLET_MAX = 0x7fff  # QEMU's absolute axis range


class Qmp:
    def __init__(self, out_dir):
        self.out = out_dir
        self.s = socket.socket(socket.AF_UNIX)
        self.s.connect(os.path.join(out_dir, "qmp.sock"))
        self.f = self.s.makefile("rw")
        self.f.readline()
        self.cmd("qmp_capabilities")
        self._size = None

    def cmd(self, name, **args):
        self.f.write(json.dumps({"execute": name, "arguments": args}) + "\n")
        self.f.flush()
        while True:
            r = json.loads(self.f.readline())
            if "return" in r:
                return r["return"]
            if "error" in r:
                raise RuntimeError(r["error"])

    # ---- screen ----
    def shot(self, path=None):
        """The display as QEMU shows it (any display device), as a PIL image."""
        from PIL import Image
        shots = os.path.join(self.out, "shots")
        os.makedirs(shots, exist_ok=True)
        path = path or os.path.join(shots, "latest.png")
        with tempfile.NamedTemporaryFile(dir=self.out, suffix=".ppm") as ppm:
            self.cmd("screendump", filename=ppm.name)
            img = Image.open(ppm.name).convert("RGB")
            img.load()
        img.save(path)
        self._size = img.size
        return img

    @property
    def size(self):
        if self._size is None:
            self.shot(os.path.join(self.out, "shots", "size-probe.png"))
        return self._size

    # ---- input ----
    def _abs(self, x, y):
        w, h = self.size
        return [{"type": "abs", "data": {"axis": "x", "value": int(x * (TABLET_MAX + 1) / w)}},
                {"type": "abs", "data": {"axis": "y", "value": int(y * (TABLET_MAX + 1) / h)}}]

    def _btn(self, down):
        return [{"type": "btn", "data": {"down": down, "button": "left"}}]

    def move(self, x, y):
        self.cmd("input-send-event", events=self._abs(x, y))

    def press(self, x, y):
        self.cmd("input-send-event", events=self._abs(x, y))
        self.cmd("input-send-event", events=self._btn(True))

    def release(self, x, y):
        self.cmd("input-send-event", events=self._abs(x, y))
        self.cmd("input-send-event", events=self._btn(False))

    def tap(self, x, y, hold=0.08):
        self.press(x, y)
        time.sleep(hold)
        self.release(x, y)

    def drag(self, x0, y0, x1, y1, steps=12, dt=0.03):
        self.press(x0, y0)
        for i in range(1, steps + 1):
            time.sleep(dt)
            self.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        time.sleep(dt)
        self.release(x1, y1)

    def key(self, qcode, hold=0.05):
        ev = lambda down: [{"type": "key", "data": {"down": down, "key": {"type": "qcode", "data": qcode}}}]
        self.cmd("input-send-event", events=ev(True))
        time.sleep(hold)
        self.cmd("input-send-event", events=ev(False))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="the board's out directory (holds qmp.sock)")
    ap.add_argument("what", choices=["shot", "tap", "key"])
    ap.add_argument("args", nargs="*")
    a = ap.parse_args()
    q = Qmp(a.out)
    if a.what == "shot":
        path = a.args[0] if a.args else None
        q.shot(path)
        print(path or os.path.join(a.out, "shots", "latest.png"))
    elif a.what == "tap":
        q.tap(float(a.args[0]), float(a.args[1]))
    elif a.what == "key":
        q.key(a.args[0])
