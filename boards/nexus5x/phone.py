#!/usr/bin/env python3
"""Talks to the phone over the dev USB link (image built with EC_DEV_USB=1):
root telnet at 172.16.42.1, the PC at 172.16.42.2.

  boards/nexus5x/phone.py sh 'CMD'          run a shell command
  boards/nexus5x/phone.py log               the app's log (from dmesg)
  boards/nexus5x/phone.py mem               system and app memory
  boards/nexus5x/phone.py shot out.png      screenshot from the framebuffer
  boards/nexus5x/phone.py tour out.gif      the demo tour (tools/tour.py) as a GIF
  boards/nexus5x/phone.py record out.mp4 S  S seconds of the screen, any app (.mp4 or .gif)

Frames are read from /dev/graphics/fb0 (the half being shown) and streamed
back with nc; touches are injected into the touchscreen's evdev node.
"""
import base64, os, re, socket, struct, sys, threading, time, zlib

from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools"))
from tour import make_gif, tour  # noqa: E402

PHONE, PC, PORT = "172.16.42.1", "172.16.42.2", 5555
W, H = 1080, 1920
EV_SYN, EV_ABS = 0, 3
ABS_MT_SLOT, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TRACKING_ID = 0x2F, 0x35, 0x36, 0x39
TAG = re.compile(r"embedded-compose: tag (\S+) (?:gone|(-?\d+) (-?\d+) (-?\d+) (-?\d+))")


def sh(cmd, timeout=20):
    """Runs cmd in the phone's shell, returns its output (raw socket, no telnet
    options). The output is cut out between two markers; the echoed command
    line can't match them (they are split by '' there), however it wraps."""
    s = socket.create_connection((PHONE, 23), timeout=10)
    s.sendall(f"stty -echo; echo __EC''_BEGIN__; {cmd}; echo __EC''_END__\n".encode())
    buf, deadline = b"", time.time() + timeout
    while time.time() < deadline:
        try:
            d = s.recv(65536)
        except socket.timeout:
            break
        if not d:
            break
        while b"\xff" in d:  # drop telnet negotiation (IAC x y)
            i = d.index(b"\xff")
            d = d[:i] + d[i + 3:]
        buf += d
        if b"__EC_END__" in buf:
            break
    s.close()
    out = buf.decode(errors="replace").replace("\r", "")
    return out.split("__EC_BEGIN__\n", 1)[-1].split("__EC_END__", 1)[0]


def put(path, data):
    """Writes a file on the phone in small base64 pieces (long telnet lines get mangled)."""
    b64 = base64.b64encode(data).decode()
    sh(f"rm -f {path}.b64")
    for i in range(0, len(b64), 400):
        sh(f"echo -n {b64[i:i + 400]} >> {path}.b64")
    sh(f"base64 -d {path}.b64 > {path}; rm {path}.b64")


class Phone:
    """Tour driver (see tools/tour.py)."""

    def __init__(self):
        name = sh("grep -il synaptics /sys/class/input/event*/device/name | head -1")
        self.touch = "/dev/input/" + name.strip().split("/")[4]
        self.frames, self.stop, self.tid = [], threading.Event(), 1

    def tags(self):
        now = {}
        for m in TAG.finditer(sh("dmesg | grep 'embedded-compose: tag '")):
            if m.group(2) is None:
                now.pop(m.group(1), None)
            else:
                now[m.group(1)] = tuple(int(m.group(i)) for i in range(2, 6))
        return now

    def bounds(self, tag):
        for _ in range(20):
            b = self.tags().get(tag)
            if b:
                return b
            time.sleep(0.5)
        raise SystemExit(f"test tag '{tag}' not on screen")

    def center(self, tag):
        b = self.bounds(tag)
        return (b[0] + b[2]) / 2, (b[1] + b[3]) / 2

    def _inject(self, points):
        """One finger down at points[0], through the rest, up at the last. The
        input core drops a coordinate equal to the slot's previous one, so
        each touch starts 2 px off."""
        ev = lambda t, c, v: struct.pack("<qqHHi", 0, 0, t, c, v)  # struct input_event, arm64
        points = [(points[0][0] + 2, points[0][1] + 2)] + list(points)
        self.tid += 1
        evs = [ev(EV_ABS, ABS_MT_SLOT, 0), ev(EV_ABS, ABS_MT_TRACKING_ID, self.tid)]
        for x, y in points:
            evs += [ev(EV_ABS, ABS_MT_POSITION_X, int(x)), ev(EV_ABS, ABS_MT_POSITION_Y, int(y)), ev(EV_SYN, 0, 0)]
        evs += [ev(EV_ABS, ABS_MT_TRACKING_ID, -1), ev(EV_SYN, 0, 0)]
        data = b"".join(evs)
        if len(data) <= 480:
            sh(f"echo {base64.b64encode(data).decode()} | base64 -d > {self.touch}")
        else:
            put("/tmp/ev.bin", data)
            sh(f"cat /tmp/ev.bin > {self.touch}")

    def tap(self, x, y):
        self._inject([(x, y), (x, y)])

    def drag(self, x0, y0, x1, y1, steps=24):
        self._inject([(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps) for i in range(steps + 1)])

    # ---- frames ----
    def start_capture(self):
        """Streams frames until stop_capture(). The app copies each frame right
        after a flip (EC_DEV_CAPTURE, backends/minui/display_minui.cpp): rows
        packed, at 1/scale size. With no flip within 0.3 s the screen is still:
        the last frame is sent again, or fb0 is read directly before the first
        ("ECR": full size, fb stride)."""
        env = sh("cat /etc/ec.env")
        scale = int((re.findall(r"EC_DEV_CAPTURE=(\d+)", env) or ["1"])[-1]) or 1
        stride = int(sh("cat /sys/class/graphics/fb0/stride").split()[-1])
        w, h = W // scale, H // scale
        packed, raw = w * h * 4, stride * H
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", PORT))
        srv.listen(1)
        self.done = threading.Event()

        def recv():
            conn, _ = srv.accept()
            buf, z = bytearray(), zlib.decompressobj(31)
            while True:
                chunk = conn.recv(1 << 20)
                if not chunk:
                    break
                buf += z.decompress(chunk)
                while len(buf) >= 16:
                    kind = bytes(buf[:4])
                    if kind not in (b"ECF ", b"ECR "):  # out of step: skip to the next header
                        k = min((x for x in (buf.find(b"ECF ", 1), buf.find(b"ECR ", 1)) if x > 0), default=len(buf))
                        del buf[:k]
                        continue
                    n = packed if kind == b"ECF " else raw
                    if len(buf) < 16 + n:
                        break
                    t = float(buf[4:16])
                    if kind == b"ECF ":
                        img = Image.frombuffer("RGBX", (w, h), bytes(buf[16:16 + n]), "raw", "RGBX", 0, 1)
                    else:
                        img = Image.frombuffer("RGBX", (stride // 4, H), bytes(buf[16:16 + n]), "raw", "RGBX", 0, 1)
                        img = img.crop((0, 0, W, H)).resize((w, h))
                    self.frames.append((t, img.convert("RGB")))
                    del buf[:16 + n]
            srv.close()
            self.done.set()

        threading.Thread(target=recv, daemon=True).start()
        loop = ("while [ ! -f /tmp/cap.stop ]; do touch /tmp/ec-capture; n=0; "
                "while [ -e /tmp/ec-capture ] && [ $n -lt 30 ]; do usleep 10000; n=$((n + 1)); done; "
                "t=$(cut -d' ' -f1 /proc/uptime); "
                "if [ -e /tmp/ec-frame ]; then rm -f /tmp/ec-capture; printf 'ECF %-12s' $t; cat /tmp/ec-frame; "
                "else rm -f /tmp/ec-capture; y=$(cut -d, -f2 /sys/class/graphics/fb0/pan); printf 'ECR %-12s' $t; "
                f"dd if=/dev/graphics/fb0 bs={stride} skip=$y count={H} 2>/dev/null; fi; "
                f"done | gzip -1 | nc {PC} {PORT}")
        put("/tmp/cap.sh", loop.encode())
        sh("rm -f /tmp/cap.stop /tmp/ec-frame; nohup sh /tmp/cap.sh >/tmp/cap.log 2>&1 & echo started")

    def stop_capture(self):
        sh("touch /tmp/cap.stop")
        self.done.wait(120)  # frames still queued on the phone arrive first
        return self.frames


def make_mp4(frames, path):
    """frames: [(seconds, PIL image)] -> H.264 MP4 at 30 fps with the real timing."""
    import shutil, subprocess, tempfile
    tmp = tempfile.mkdtemp()
    try:
        with open(os.path.join(tmp, "list.txt"), "w") as f:
            for i, (t, img) in enumerate(frames):
                name = f"f{i:05d}.png"
                img.save(os.path.join(tmp, name))
                dur = (frames[i + 1][0] - t) if i + 1 < len(frames) else 1.0
                f.write(f"file '{name}'\nduration {max(dur, 0.01):.3f}\n")
            f.write(f"file 'f{len(frames) - 1:05d}.png'\n")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", "list.txt",
                        "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-crf", "18", os.path.abspath(path)],
                       cwd=tmp, check=True)
    finally:
        shutil.rmtree(tmp)
    print(f"{path}: {len(frames)} frames, {frames[-1][0] - frames[0][0]:.1f} s")


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else ""
    if what == "sh":
        print(sh(" ".join(sys.argv[2:])), end="")
    elif what == "log":
        print(sh("dmesg | grep 'embedded-compose' | grep -v ': frame [0-9]\\|: input type=\\|: tag '"), end="")
    elif what == "mem":
        print(sh("free -k; grep -E 'MemTotal|MemFree|MemAvailable|Cached|Shmem' /proc/meminfo; "
                 "p=$(pidof embedded-compose); grep -E 'VmRSS|VmHWM' /proc/$p/status; "
                 "dmesg | grep 'embedded-compose: stats' | tail -2"), end="")
    elif what == "record" and len(sys.argv) == 4:
        p = Phone()
        p.start_capture()
        time.sleep(float(sys.argv[3]))
        frames = p.stop_capture()
        out = sys.argv[2]
        if out.endswith(".gif"):
            make_gif(frames, out)
        else:
            make_mp4(frames, out)
    elif what in ("shot", "tour") and len(sys.argv) == 3:
        p = Phone()
        p.start_capture()
        if what == "shot":
            while not p.frames:
                time.sleep(0.2)
            p.stop_capture()[-1][1].save(sys.argv[2])
            print(sys.argv[2])
        else:
            time.sleep(1)
            tour(p)
            make_gif(p.stop_capture(), sys.argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
