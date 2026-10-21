#!/usr/bin/env python3
"""A scripted tour of the demo app, recorded as a GIF with real frame timing.

  ./ec record <board> [out.gif]       emulated boards (boots it headless)
  boards/nexus5x/phone.py tour out.gif  the phone (over its dev USB link)

The tour finds its targets by test tag, so it runs on any screen size.
A driver provides: center(tag), tap(x, y), drag(x0, y0, x1, y1), and a
frame source that keeps (seconds, PIL image) pairs while the tour runs.
"""
import argparse, os, shlex, signal, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def tour(d):
    """Basics (counter, accent), Shapes, Motion (animation, drag), back."""
    time.sleep(2)
    plus = d.center("plus")
    for _ in range(3):
        d.tap(*plus); time.sleep(0.7)
    d.tap(*d.center("accent-3")); time.sleep(1.5)
    d.tap(*d.center("tab-shapes")); time.sleep(3)
    d.tap(*d.center("bounds")); time.sleep(2.5)
    d.tap(*d.center("tab-motion")); time.sleep(3)
    d.tap(*d.center("grow")); time.sleep(2)
    area = d.bounds("drag-area")
    y = (area[1] + area[3]) / 2
    # The ball starts near the left edge of the area (x = 120 px into it).
    d.drag(area[0] + 120, area[1] + 110, area[2] - 150, y)
    time.sleep(2)
    d.tap(*d.center("tab-basics")); time.sleep(2)


def make_gif(frames, path, width=360):
    """frames: [(seconds, PIL image)] -> GIF at [width], repeats merged, real timing."""
    from PIL import Image
    out, durs, last = [], [], None
    for i, (t, img) in enumerate(frames):
        small = img.resize((width, img.height * width // img.width), Image.LANCZOS)
        dur = int(((frames[i + 1][0] if i + 1 < len(frames) else t + 1.5) - t) * 1000)
        if last is not None and small.tobytes() == last.tobytes():
            durs[-1] += dur
            continue
        out.append(small)
        durs.append(max(dur, 20))
        last = small
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    out[0].save(path, save_all=True, append_images=out[1:], duration=durs, loop=0, optimize=True)
    print(f"{path}: {len(out)} frames, {sum(durs) / 1000:.1f} s")


class Emulated:
    """Tour driver for a board booted with ./ec run (QMP + console log)."""

    def __init__(self, out):
        from qmp import Qmp
        from uitest import Board
        self.board, self.q = Board(out), Qmp(out)
        self.lock, self.frames, self.stop = threading.Lock(), [], threading.Event()

    def bounds(self, tag):
        self.board.center(tag)
        return self.board.tags()[tag]

    def center(self, tag):
        return self.board.center(tag)

    def tap(self, x, y):
        with self.lock:
            self.q.press(x, y)
        time.sleep(0.08)
        with self.lock:
            self.q.release(x, y)

    def drag(self, x0, y0, x1, y1, seconds=2.5, steps=30):
        with self.lock:
            self.q.press(x0, y0)
        for i in range(1, steps + 1):
            time.sleep(seconds / steps)
            with self.lock:
                self.q.move(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        with self.lock:
            self.q.release(x1, y1)

    def capture(self):
        while not self.stop.is_set():
            with self.lock:
                img = self.q.shot(os.path.join(self.q.out, "shots", "tour.png"))
            self.frames.append((time.time(), img))
            time.sleep(0.05)


def record_emulated(run, out, gif):
    for stale in ("console.log", "qmp.sock"):
        if os.path.exists(os.path.join(out, stale)):
            os.remove(os.path.join(out, stale))
    env = dict(os.environ, HEADLESS="1", TRACE="1")
    qemu = subprocess.Popen(shlex.split(run), env=env, stdout=open(os.path.join(out, "qemu.log"), "w"),
                            stderr=subprocess.STDOUT, start_new_session=True)
    try:
        from uitest import Board
        if not Board(out).wait_for(r"embedded-compose: frame 1\b", 180):
            raise SystemExit("FAIL: no first frame")
        time.sleep(2)
        d = Emulated(out)
        t = threading.Thread(target=d.capture, daemon=True)
        t.start()
        tour(d)
        d.stop.set()
        t.join()
        make_gif(d.frames, gif)
    finally:
        os.killpg(qemu.pid, signal.SIGTERM)
        qemu.wait()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("gif")
    a = ap.parse_args()
    record_emulated(a.run, a.out, a.gif)
