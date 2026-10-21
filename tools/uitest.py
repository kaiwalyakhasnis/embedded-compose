#!/usr/bin/env python3
"""Reliability test of the demo app on an emulated board (run through ./ec):

  ./ec test <board> [--minutes 10] [--seed 1] [--soak MINUTES]

Boots the board headless with tracing on and drives it over QMP. Positions
come from the app's test tags (Modifier.testTag, logged with their screen
bounds), so the same test runs at any resolution and density.

  1. reference: Basics tab, first accent, counter reset then +5 -> screenshot
  2. stress: random taps / drags / keys / tab switches for --minutes
     (watching the console for crashes, restarts and memory growth)
  3. optional soak: the Motion tab's animation running for --soak minutes
  4. replay the reference sequence -> exactly 5 '+' clicks logged, and the
     screen must be pixel-identical to step 1
"""
import argparse, os, random, re, shlex, signal, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qmp import Qmp  # noqa: E402

TAG = re.compile(r"embedded-compose: tag (\S+) (?:gone|(-?\d+) (-?\d+) (-?\d+) (-?\d+))")
STATS = re.compile(r"stats: (\d+) frames in (\d+) ms, render avg (\d+) us, rss (\d+) kB")


class Board:
    def __init__(self, out):
        self.log = os.path.join(out, "console.log")

    def console(self):
        try:
            return open(self.log, errors="replace").read()
        except FileNotFoundError:
            return ""

    def wait_for(self, pattern, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if re.search(pattern, self.console()):
                return True
            time.sleep(0.5)
        return False

    def check_alive(self):
        text = self.console()
        bad = [m for m in ("uncaught", "exited:", "=== start #2", "Segmentation", "Kernel panic",
                           "Comm: embedded-compose") if m in text]
        if bad:
            raise SystemExit(f"FAIL: console shows {bad}; see {self.log}")

    def stats(self):
        return [tuple(int(x) for x in m) for m in STATS.findall(self.console())]

    def clicks(self):
        return len(re.findall(r"click at", self.console()))

    def tags(self):
        """Current bounds of every test tag on screen: name -> (l, t, r, b)."""
        now = {}
        for m in TAG.finditer(self.console()):
            if m.group(2) is None:
                now.pop(m.group(1), None)
            else:
                now[m.group(1)] = tuple(int(m.group(i)) for i in range(2, 6))
        return now

    def center(self, tag, timeout=20):
        """Waits until [tag] is on screen; returns its center."""
        end = time.time() + timeout
        while time.time() < end:
            b = self.tags().get(tag)
            if b:
                return (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
            time.sleep(0.3)
        raise SystemExit(f"FAIL: test tag '{tag}' never appeared; see {self.log}")


def reference_sequence(q, b, shots, name):
    """Brings the app to a known state from any state, then screenshots it."""
    q.key("esc"); time.sleep(0.3)
    q.tap(*b.center("tab-basics")); time.sleep(1.2)
    q.tap(*b.center("accent-0")); time.sleep(1.0)
    q.tap(*b.center("reset")); time.sleep(1.0)
    start = b.clicks()
    plus = b.center("plus")
    for _ in range(5):
        q.tap(*plus); time.sleep(0.6)
    time.sleep(1.5)
    return q.shot(os.path.join(shots, f"ref-{name}.png")), b.clicks() - start


def stress(q, b, minutes, rng):
    w, h = q.size
    known = {}  # last bounds seen per tag, also of tabs not showing now
    end = time.time() + minutes * 60
    n = 0
    next_report = time.time() + 30
    while time.time() < end:
        known.update(b.tags())
        tabs = [t for t in known if t.startswith("tab-")]
        buttons = [t for t in ("minus", "plus", "reset", "pause", "grow", "bounds") if t in known]
        center = lambda t: ((known[t][0] + known[t][2]) / 2, (known[t][1] + known[t][3]) / 2)
        r = rng.random()
        if r < 0.30:
            q.tap(rng.uniform(0, w - 1), rng.uniform(0, h - 1), hold=rng.uniform(0.02, 0.3))
        elif r < 0.45 and tabs:
            q.tap(*center(rng.choice(tabs)))
        elif r < 0.60 and buttons:
            q.tap(*center(rng.choice(buttons)))
        elif r < 0.75:
            area = known.get("drag-area", (0, 0, w, h))
            x0, y0 = rng.uniform(area[0], area[2] - 1), rng.uniform(area[1], area[3] - 1)
            x1, y1 = rng.uniform(-50, w + 50), rng.uniform(-50, h + 50)   # sometimes off-screen
            q.drag(x0, y0, x1, y1, steps=rng.randint(2, 20), dt=0.02)
        elif r < 0.95:
            q.key(rng.choice(["tab", "tab", "down", "up", "left", "right", "ret", "spc", "esc"]))
        elif tabs:
            # Rapid-fire taps on the tabs while recomposition is still catching up.
            for _ in range(rng.randint(3, 10)):
                q.tap(*center(rng.choice(tabs)), hold=0.01)
        n += 1
        time.sleep(rng.uniform(0.0, 0.25))
        if time.time() > next_report:
            b.check_alive()
            s = b.stats()
            last = s[-1] if s else None
            print(f"  {n} actions, {int((end - time.time()) / 60)} min left, last stats: "
                  f"{last[0] if last else '-'} frames/5s, render {last[2] // 1000 if last else '-'} ms, "
                  f"rss {last[3] if last else '-'} kB", flush=True)
            next_report = time.time() + 30
    return n


def soak(q, b, minutes):
    """Motion tab with the frame animation running: a new frame every vsync, no input."""
    q.key("esc"); time.sleep(0.3)
    q.tap(*b.center("tab-motion")); time.sleep(6)
    s = b.stats()
    if not s or s[-1][0] < 10:          # animation paused (Pause/Play is random after stress)
        q.tap(*b.center("pause")); time.sleep(6)
    start = len(b.stats())
    end = time.time() + minutes * 60
    while time.time() < end:
        time.sleep(30)
        b.check_alive()
        s = b.stats()[start:]
        if s:
            print(f"  soak: {sum(x[0] for x in s)} frames so far, last {s[-1][0]} frames/5s, rss {s[-1][3]} kB",
                  flush=True)
    s = b.stats()[start:]
    if not s or min(x[0] for x in s[1:] or s) < 5:
        raise SystemExit("FAIL: animation stalled during soak")
    return s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True, help="command that boots the board (set by ./ec)")
    ap.add_argument("--out", required=True, help="the board's out directory (set by ./ec)")
    ap.add_argument("--minutes", type=float, default=10)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--soak", type=float, default=0, help="minutes of continuous animation after the stress phase")
    args = ap.parse_args()
    shots = os.path.join(args.out, "shots")
    os.makedirs(shots, exist_ok=True)
    b = Board(args.out)
    for stale in ("console.log", "qmp.sock"):   # from an earlier run: don't read it as this one's
        if os.path.exists(os.path.join(args.out, stale)):
            os.remove(os.path.join(args.out, stale))

    env = dict(os.environ, HEADLESS="1", TRACE="1")
    qemu = subprocess.Popen(shlex.split(args.run), env=env,
                            stdout=open(os.path.join(args.out, "qemu.log"), "w"), stderr=subprocess.STDOUT,
                            start_new_session=True)
    try:
        print("booting ...", flush=True)
        if not b.wait_for(r"embedded-compose: frame 1\b", 180):
            raise SystemExit(f"FAIL: no first frame; see {b.log}")
        time.sleep(2)
        q = Qmp(args.out)
        print(f"screen {q.size[0]}x{q.size[1]}", flush=True)

        print("reference sequence", flush=True)
        ref, clicks = reference_sequence(q, b, shots, "before")
        if clicks != 5:
            raise SystemExit(f"FAIL: reference sequence logged {clicks} '+' clicks, expected 5")

        print(f"stress for {args.minutes} min (seed {args.seed})", flush=True)
        actions = stress(q, b, args.minutes, random.Random(args.seed))
        b.check_alive()

        if args.soak:
            print(f"animation soak for {args.soak} min", flush=True)
            sk = soak(q, b, args.soak)
            print(f"  soak rss first/last: {sk[0][3]}/{sk[-1][3]} kB")

        print("replaying reference sequence", flush=True)
        after, clicks2 = reference_sequence(q, b, shots, "after")
        b.check_alive()
        if clicks2 != 5:
            raise SystemExit(f"FAIL: replay logged {clicks2} '+' clicks, expected 5")
        diff = sum(1 for x, y in zip(ref.getdata(), after.getdata()) if x != y)

        s = b.stats()
        rss = [x[3] for x in s]
        print(f"actions: {actions}, frames rendered: {sum(x[0] for x in s)}, "
              f"rss first/max/last: {rss[0]}/{max(rss)}/{rss[-1]} kB")
        print(f"render time avg over run: {sum(x[2] for x in s) // max(len(s), 1) // 1000} ms (QEMU TCG)")
        if diff:
            raise SystemExit(f"FAIL: final screen differs from reference in {diff} pixels "
                             f"(see {shots}/ref-before.png, ref-after.png)")
        print("PASS: no crash/restart, final screen pixel-identical to reference")
    finally:
        os.killpg(qemu.pid, signal.SIGTERM)
        qemu.wait()


if __name__ == "__main__":
    main()
