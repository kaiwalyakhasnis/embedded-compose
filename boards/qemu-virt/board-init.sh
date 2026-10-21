# Sourced by /init before the app starts.
#
# minui tries DRM before fbdev. virtio-gpu's DRM planes only scan out
# XRGB8888, which AOSP 8.1 minui cannot ask for (it accepts the framebuffer,
# then fails to show it), so hide DRM and let minui use the fbdev emulation.
rm -rf /dev/dri
