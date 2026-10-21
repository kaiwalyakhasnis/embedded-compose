#!/usr/bin/env python3
"""Puts the embedded-compose charger into a copy of a LineageOS 15.1 boot.img:
Android's off-mode charging (androidboot.mode=charger -> init's "on charger"
-> service charger /charger -> /sbin/charger) then runs our app.

  lineage-charger.py <lineage boot.img> <charger binary> <out.img> [--charger-mode]

  <charger binary>  APP=charger ./ec build nexus5x -> out/nexus5x/native/embedded-compose
  --charger-mode    also add androidboot.mode=charger to the command line, so
                    `fastboot boot out.img` goes straight into off-mode
                    charging (RAM only; for testing)

In the ramdisk: /sbin/charger is replaced, /res/images/font.png (AOSP 18x32)
is added, and the charger service in init.bullhead.rc gets the app's
environment. The input image is not modified.
"""
import gzip, os, struct, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FONT = os.path.join(ROOT, "third_party/aosp/bootable/recovery/fonts/18x32.png")
SERVICE = b"service charger /charger\n"
ENV = (b"    setenv EC_POWER_REBOOT 1\n"                       # hold POWER 3 s: reboot into Android
       b"    setenv EC_DENSITY 2.625\n"
       b"    setenv GLIBC_TUNABLES glibc.pthread.rseq=0\n")    # 3.10 kernel: no rseq


def read_cpio(data):
    """newc entries as [name, header fields (13 ints), data]."""
    out, i = [], 0
    while True:
        assert data[i:i + 6] == b"070701", f"bad cpio header at {i}"
        f = [int(data[i + 6 + 8 * k:i + 14 + 8 * k], 16) for k in range(13)]
        namesize, filesize = f[11], f[6]
        name = data[i + 110:i + 110 + namesize - 1].decode()
        i = (i + 110 + namesize + 3) & ~3
        body = data[i:i + filesize]
        i = (i + filesize + 3) & ~3
        if name == "TRAILER!!!":
            return out
        out.append([name, f, body])


def write_cpio(entries):
    out = bytearray()
    for name, f, body in entries + [["TRAILER!!!", [0, 0, 0, 0, 1] + [0] * 8, b""]]:
        nb = name.encode() + b"\0"
        f = list(f)
        f[6], f[11] = len(body), len(nb)
        out += b"070701" + b"".join(b"%08X" % v for v in f) + nb
        out += b"\0" * (-len(out) % 4) + body
        out += b"\0" * (-len(out) % 4)
    return bytes(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 3:
        sys.exit(__doc__)
    src, binary, dst = args
    img = open(src, "rb").read()
    if img[:8] != b"ANDROID!":
        sys.exit(f"{src}: not an Android boot image")
    (ksize, kaddr, rsize, raddr, _ssize, _saddr, taddr, page) = struct.unpack_from("<8I", img, 8)
    cmdline = img[64:64 + 512].split(b"\0")[0].decode()
    os_version = struct.unpack_from("<I", img, 44)[0]  # keep: the bootloader passes it to keymaster
    kernel = img[page:page + ksize]
    roff = page + (ksize + page - 1) // page * page
    entries = read_cpio(gzip.decompress(img[roff:roff + rsize]))
    names = {e[0]: e for e in entries}

    charger = names.get("sbin/charger") or sys.exit("no sbin/charger in the ramdisk")
    charger[2] = open(binary, "rb").read()
    charger[1][1] = 0o100750  # regular file, as installed by the build

    rc = names.get("init.bullhead.rc") or sys.exit("no init.bullhead.rc in the ramdisk")
    if SERVICE not in rc[2]:
        sys.exit("init.bullhead.rc has no 'service charger /charger'")
    rc[2] = rc[2].replace(SERVICE, SERVICE + ENV, 1)

    if "res/images/font.png" not in names:
        ino = max(e[1][0] for e in entries) + 1
        entries.append(["res/images/font.png", [ino, 0o100644, 0, 0, 1] + [0] * 8, open(FONT, "rb").read()])

    if "--charger-mode" in sys.argv:
        cmdline += " androidboot.mode=charger"
    ramdisk = os.path.join(os.path.dirname(os.path.abspath(dst)), "ramdisk-charger.cpio.gz")
    kfile = os.path.join(os.path.dirname(os.path.abspath(dst)), "kernel-lineage")
    with open(ramdisk, "wb") as f:
        f.write(gzip.compress(write_cpio(entries), compresslevel=9, mtime=0))
    open(kfile, "wb").write(kernel)
    subprocess.run([sys.executable, os.path.join(HERE, "mkbootimg.py"), "--kernel", kfile, "--ramdisk", ramdisk,
                    "--cmdline", cmdline, "--base", "0", "--kernel_offset", hex(kaddr), "--ramdisk_offset", hex(raddr),
                    "--tags_offset", hex(taddr), "--pagesize", str(page), "--os_version", hex(os_version),
                    "-o", dst], check=True)
    print(f"cmdline: {cmdline}")


if __name__ == "__main__":
    main()
