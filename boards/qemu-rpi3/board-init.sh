# Sourced by /init before the app starts. QEMU's Raspberry Pi 3 has GPIO
# registers but no panel, so every 5 s this prints what a panel would get:
# the GPIO levels the refresh thread drives (they change between samples
# while it runs), and the frame the app last showed (EC_DEV_CAPTURE), in
# coloured half-block characters, two LED rows per line.
t=$(sed -n 's/.*ec\.time=\([0-9]*\).*/\1/p' /proc/cmdline)   # no RTC: the host's time
[ -n "$t" ] && date -s "@$t" > /dev/null

show_frame() {
  od -An -v -tu1 /tmp/ec-frame.ppm | awk '
    { for (i = 1; i <= NF; i++) b[n++] = $i }
    END {
      # "P6\n<w> <h>\n255\n", then R G B per pixel
      for (i = 0; nl < 3; i++) { if (b[i] == 10) nl++; else if (nl == 1) s = s sprintf("%c", b[i]) }
      split(s, d, " "); w = d[1]; h = d[2]
      for (y = 0; y + 1 < h; y += 2) {
        line = ""
        for (x = 0; x < w; x++) {
          t = i + (y * w + x) * 3; u = t + w * 3
          line = line sprintf("\033[38;2;%d;%d;%dm\033[48;2;%d;%d;%dm\342\226\200",
                              b[t], b[t + 1], b[t + 2], b[u], b[u + 1], b[u + 2])
        }
        print line "\033[0m"
      }
    }'
}

(
  sleep 8
  while true; do
    touch /tmp/ec-capture
    sleep 1
    echo "panel GPIO levels: $(for i in 1 2 3 4; do devmem 0x3F200034; done | tr '\n' ' ')"
    [ -f /tmp/ec-frame.ppm ] && show_frame
    sleep 4
  done
) > /dev/console 2>&1 &
