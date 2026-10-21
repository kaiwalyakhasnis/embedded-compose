#!/usr/bin/env python3
"""Talks to the phone over the dev USB link (image built with EC_DEV_USB=1):
root telnet at 172.16.42.1, the PC at 172.16.42.2.

  boards/nexus5x/phone.py sh 'CMD'          run a shell command
  boards/nexus5x/phone.py log               the app's log (from dmesg)
  boards/nexus5x/phone.py mem               system and app memory
  boards/nexus5x/phone.py shot out.png      screenshot from the framebuffer
  boards/nexus5x/phone.py tour out.gif      the demo tour (tools/tour.py) as a GIF

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
    """Runs cmd in the phone's shell, returns its output (raw socket, no telnet options)."""
    s = socket.create_connection((PHONE, 23), timeout=10)
    end = "__EC_END__"
    s.sendall(f"stty -echo; {cmd}; echo __EC''_END__\n".encode())  # the echoed line never contains end
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
        if end.encode() in buf:
            break
    s.close()
    out = buf.decode(errors="replace").replace("\r", "").split(end, 1)[0]
    return out.rsplit("''_END__", 1)[-1].lstrip("\n")  # drop the echoed command (sent twice)


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
        stride = int(sh("cat /sys/class/graphics/fb0/stride").split()[-1])
        frame = stride * H
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
                while len(buf) >= 16 + frame:
                    if buf[:4] != b"ECF ":  # a short frame: skip to the next header
                        i = buf.find(b"ECF ", 1)
                        del buf[:i if i > 0 else len(buf)]
                        continue
                    t = float(buf[4:16])
                    img = Image.frombuffer("RGBX", (stride // 4, H), bytes(buf[16:16 + frame]), "raw", "RGBX", 0, 1)
                    self.frames.append((t, img.crop((0, 0, W, H)).convert("RGB")))
                    del buf[:16 + frame]
            srv.close()
            self.done.set()

        threading.Thread(target=recv, daemon=True).start()
        # Each frame: "ECF <uptime>" then the frame on screen, gzip'd on the
        # phone (USB networking moves ~2.5 MB/s). The app copies it right
        # after a flip (EC_DEV_CAPTURE, backends/minui/display_minui.cpp);
        # with no new frame in 0.3 s the screen is still and fb0 is read here.
        loop = ("while [ ! -f /tmp/cap.stop ]; do rm -f /tmp/ec-frame; touch /tmp/ec-capture; n=0; "
                "while [ -e /tmp/ec-capture ] && [ $n -lt 30 ]; do usleep 10000; n=$((n + 1)); done; "
                "t=$(cut -d' ' -f1 /proc/uptime); "
                "if [ ! -e /tmp/ec-frame ]; then rm -f /tmp/ec-capture; y=$(cut -d, -f2 /sys/class/graphics/fb0/pan); "
                f"dd if=/dev/graphics/fb0 of=/tmp/ec-frame bs={stride} skip=$y count={H} 2>/dev/null; fi; "
                f"printf 'ECF %-12s' $t; cat /tmp/ec-frame; done | gzip -1 | nc {PC} {PORT}")
        put("/tmp/cap.sh", loop.encode())
        sh("rm -f /tmp/cap.stop; nohup sh /tmp/cap.sh >/tmp/cap.log 2>&1 & echo started")

    def stop_capture(self):
        sh("touch /tmp/cap.stop")
        self.done.wait(120)  # frames still queued on the phone arrive first
        return self.frames


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
