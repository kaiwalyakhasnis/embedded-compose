#!/usr/bin/env python3
"""Writes a gzipped initramfs (cpio "newc") from a file list in the format of
the kernel's usr/gen_init_cpio, so no kernel tree is needed:

    dir   <name> <mode> <uid> <gid>
    file  <name> <source> <mode> <uid> <gid>
    nod   <name> <mode> <uid> <gid> <c|b> <major> <minor>
    slink <name> <target> <mode> <uid> <gid>

Usage: mkcpio.py <list> <out.cpio.gz>
Timestamps are zero, so the same inputs give the same bytes.
"""
import gzip, stat, sys


def entry(out, ino, name, mode, uid, gid, data=b"", nlink=1, rmaj=0, rmin=0):
    name_b = name.lstrip("/").encode() + b"\0"
    fields = (ino, mode, uid, gid, nlink, 0, len(data), 0, 0, rmaj, rmin, len(name_b), 0)
    out.write(b"070701" + b"".join(b"%08X" % f for f in fields))
    out.write(name_b + b"\0" * (-(110 + len(name_b)) % 4))
    out.write(data + b"\0" * (-len(data) % 4))


def main(list_path, out_path):
    with open(out_path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, compresslevel=9) as out:
        ino = 1
        for n, line in enumerate(open(list_path), 1):
            f = line.split()
            if not f or f[0].startswith("#"):
                continue
            kind = f[0]
            try:
                if kind == "dir":
                    entry(out, ino, f[1], stat.S_IFDIR | int(f[2], 8), int(f[3]), int(f[4]), nlink=2)
                elif kind == "file":
                    data = open(f[2], "rb").read()
                    entry(out, ino, f[1], stat.S_IFREG | int(f[3], 8), int(f[4]), int(f[5]), data)
                elif kind == "nod":
                    t = stat.S_IFCHR if f[5] == "c" else stat.S_IFBLK
                    entry(out, ino, f[1], t | int(f[2], 8), int(f[3]), int(f[4]), rmaj=int(f[6]), rmin=int(f[7]))
                elif kind == "slink":
                    entry(out, ino, f[1], stat.S_IFLNK | int(f[3], 8), int(f[4]), int(f[5]), f[2].encode())
                else:
                    raise ValueError(f"unknown entry type {kind!r}")
            except (IndexError, ValueError, OSError) as e:
                sys.exit(f"{list_path}:{n}: {e}")
            ino += 1
        entry(out, 0, "TRAILER!!!", 0, 0, 0, nlink=1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
