#!/usr/bin/env python3
"""Minimal Android boot image (header v0) writer, written out so you can see
exactly what the Nexus 5X bootloader (LK/aboot) parses.

  mkbootimg.py --kernel K --ramdisk R [--cmdline ...] -o boot.img
  mkbootimg.py --extract-kernel boot.img kernel   (the reverse, kernel only)

Layout (every section padded to page_size):
  page 0      : header (below)
  N pages     : kernel   (Image.gz with the .dtb appended – LK finds it there)
  M pages     : ramdisk  (our initramfs.cpio.gz)
"""
import argparse, hashlib, struct, sys

def pad(data, page):
    return data + b"\0" * (-len(data) % page)

if len(sys.argv) == 4 and sys.argv[1] == "--extract-kernel":
    d = open(sys.argv[2], "rb").read()
    if d[:8] != b"ANDROID!":
        sys.exit(f"{sys.argv[2]}: not an Android boot image")
    size, page = struct.unpack_from("<I", d, 8)[0], struct.unpack_from("<I", d, 36)[0]
    open(sys.argv[3], "wb").write(d[page:page + size])
    print(f"{sys.argv[3]}: kernel {size} B from {sys.argv[2]}")
    sys.exit(0)

p = argparse.ArgumentParser()
p.add_argument("--kernel", required=True)
p.add_argument("--ramdisk", required=True)
p.add_argument("--cmdline", default="")
p.add_argument("--base", type=lambda x: int(x, 0), default=0x00000000)
p.add_argument("--kernel_offset", type=lambda x: int(x, 0), default=0x00008000)
p.add_argument("--ramdisk_offset", type=lambda x: int(x, 0), default=0x02000000)
p.add_argument("--tags_offset", type=lambda x: int(x, 0), default=0x01e00000)
p.add_argument("--pagesize", type=int, default=4096)
p.add_argument("-o", "--output", required=True)
a = p.parse_args()

kernel = open(a.kernel, "rb").read()
ramdisk = open(a.ramdisk, "rb").read()
cmd = a.cmdline.encode()
assert len(cmd) < 512, "cmdline too long for v0 header"

# id = SHA1(kernel, len, ramdisk, len, second, len, dt, len) – same as AOSP;
# LK only checks it on a locked bootloader.
sha = hashlib.sha1()
for blob in (kernel, ramdisk, b"", b""):   # second, dt: empty
    sha.update(blob)
    sha.update(struct.pack("<I", len(blob)))

hdr = struct.pack(
    "<8s10I16s512s32s1024s",
    b"ANDROID!",
    len(kernel), a.base + a.kernel_offset,
    len(ramdisk), a.base + a.ramdisk_offset,
    0, a.base + 0x00f00000,          # second stage: unused
    a.base + a.tags_offset,          # where LK puts the DTB for the kernel
    a.pagesize,
    0, 0,                            # header_version 0, os_version
    b"embedded-compose",
    cmd, sha.digest(), b"")

with open(a.output, "wb") as f:
    f.write(pad(hdr, a.pagesize) + pad(kernel, a.pagesize) + pad(ramdisk, a.pagesize))
print(f"{a.output}: kernel {len(kernel)} B, ramdisk {len(ramdisk)} B")
