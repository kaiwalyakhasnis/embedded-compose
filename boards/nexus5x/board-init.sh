# Sourced by /init before the app starts.

# The panel comes up with the backlight off; turn it on once the app has had
# time to draw a frame.
(sleep 3; echo 160 > /sys/class/leds/lcd-backlight/brightness) &

# DEV ONLY (EC_DEV_USB=1 at build time): USB network (RNDIS) with a root
# shell over telnet at 172.16.42.1, the PC gets 172.16.42.2 by DHCP.
# boards/nexus5x/phone.py uses it for logs, memory stats and screen capture.
if [ "$EC_DEV_USB" = 1 ]; then
  G=/sys/class/android_usb/android0
  if [ -d $G ]; then
    echo 0 > $G/enable
    echo 18d1 > $G/idVendor
    echo 4ee3 > $G/idProduct
    echo embedded-compose > $G/iProduct
    echo rndis > $G/functions
    echo 1 > $G/enable
    (
      sleep 1
      ifconfig rndis0 172.16.42.1 netmask 255.255.255.0 up
      printf 'start 172.16.42.2\nend 172.16.42.2\ninterface rndis0\nmax_leases 1\noption subnet 255.255.255.0\nlease_file /tmp/udhcpd.leases\n' > /tmp/udhcpd.conf
      udhcpd /tmp/udhcpd.conf
      # telnetd needs ptys; without devtmpfs there is no /dev/ptmx or /dev/pts.
      [ -e /dev/ptmx ] || mknod -m 666 /dev/ptmx c 5 2
      mkdir -p /dev/pts && mount -t devpts devpts /dev/pts
      telnetd -l /bin/sh
    ) &
  fi
fi
