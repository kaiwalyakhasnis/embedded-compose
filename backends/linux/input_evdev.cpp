// Input for any Linux display backend: every /dev/input/event* device,
// read with plain evdev + epoll and turned into ec_event key and pointer
// events (multi-touch protocol B first finger, or absolute pointer).
//
// (minui has its own ev_init(), but it std::move()s the one callback into
// every device slot, so only the first device delivers events; reading evdev
// here avoids patching minui.)
#include "ec_hal.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/reboot.h>
#include <unistd.h>

#include <algorithm>

#define BITS_PER_LONG (sizeof(unsigned long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
static bool test_bit(int bit, const unsigned long* a) { return (a[bit / BITS_PER_LONG] >> (bit % BITS_PER_LONG)) & 1; }

struct Device {
  int fd;
  bool mt;              // multi-touch protocol B (phone touchscreen)
  bool pointer;
  input_absinfo ax, ay;
  int slot;             // MT: current slot; only slot 0 (first finger) is tracked
  int raw_x, raw_y;
  bool down, was_down;
  bool moved;
};

static const int kMaxDevices = 16;
static Device g_dev[kMaxDevices];
static int g_ndev;
static int g_epoll = -1;
static int g_w, g_h;

int ec_input_open(void) {
  g_w = ec_width();
  g_h = ec_height();
  g_epoll = epoll_create1(EPOLL_CLOEXEC);
  DIR* dir = opendir("/dev/input");
  if (!dir) return 0;
  while (dirent* de = readdir(dir)) {
    if (strncmp(de->d_name, "event", 5) || g_ndev == kMaxDevices) continue;
    int fd = openat(dirfd(dir), de->d_name, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) continue;
    unsigned long ev_bits[NBITS(EV_MAX + 1)] = {};
    unsigned long abs_bits[NBITS(ABS_MAX + 1)] = {};
    ioctl(fd, EVIOCGBIT(0, sizeof(ev_bits)), ev_bits);
    if (!test_bit(EV_KEY, ev_bits) && !test_bit(EV_ABS, ev_bits)) {
      close(fd);
      continue;
    }
    Device& d = g_dev[g_ndev];
    memset(&d, 0, sizeof(d));
    d.fd = fd;
    if (test_bit(EV_ABS, ev_bits)) {
      ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(abs_bits)), abs_bits);
      if (test_bit(ABS_MT_POSITION_X, abs_bits) && test_bit(ABS_MT_POSITION_Y, abs_bits)) {
        d.mt = d.pointer = true;
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &d.ax);
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &d.ay);
      } else if (test_bit(ABS_X, abs_bits) && test_bit(ABS_Y, abs_bits)) {
        d.pointer = true;
        ioctl(fd, EVIOCGABS(ABS_X), &d.ax);
        ioctl(fd, EVIOCGABS(ABS_Y), &d.ay);
      }
    }
    epoll_event ev = {};
    ev.events = EPOLLIN;
    ev.data.ptr = &d;
    if (epoll_ctl(g_epoll, EPOLL_CTL_ADD, fd, &ev) != 0) {
      close(fd);
      continue;
    }
    char name[80] = "?";
    ioctl(fd, EVIOCGNAME(sizeof(name)), name);
    char msg[400];
    snprintf(msg, sizeof(msg), "input: %s \"%s\"%s", de->d_name, name, d.pointer ? (d.mt ? " (multi-touch)" : " (pointer)") : "");
    ec_log(msg);
    g_ndev++;
  }
  closedir(dir);
  return g_ndev;
}

static int scale(int raw, const input_absinfo& a, int size) {
  int range = a.maximum - a.minimum + 1;
  if (range <= 1) return raw;
  return std::clamp((int)((long long)(raw - a.minimum) * size / range), 0, size - 1);
}

static int read_device(Device& d, ec_event* out, int max) {
  int n = 0;
  input_event ev[32];
  ssize_t r;
  while (n < max && (r = read(d.fd, ev, sizeof(ev))) > 0) {
    for (size_t k = 0; k < r / sizeof(input_event) && n < max; ++k) {
      const input_event& e = ev[k];
      if (e.type == EV_KEY) {
        if (e.code == BTN_TOUCH || e.code == BTN_LEFT) {
          if (d.pointer) d.down = e.value != 0;
        } else if (e.code < BTN_MISC || e.code >= KEY_OK) {
          out[n++] = ec_event{EC_EV_KEY, e.code, e.value, 0, 0};
        }
      } else if (e.type == EV_ABS && d.pointer) {
        if (d.mt) {
          if (e.code == ABS_MT_SLOT) d.slot = e.value;
          else if (d.slot == 0 && e.code == ABS_MT_TRACKING_ID) d.down = e.value >= 0;
          else if (d.slot == 0 && e.code == ABS_MT_POSITION_X) { d.raw_x = e.value; d.moved = true; }
          else if (d.slot == 0 && e.code == ABS_MT_POSITION_Y) { d.raw_y = e.value; d.moved = true; }
        } else {
          if (e.code == ABS_X) { d.raw_x = e.value; d.moved = true; }
          else if (e.code == ABS_Y) { d.raw_y = e.value; d.moved = true; }
        }
      } else if (e.type == EV_SYN && e.code == SYN_REPORT && d.pointer) {
        int x = scale(d.raw_x, d.ax, g_w), y = scale(d.raw_y, d.ay, g_h);
        if (d.down && !d.was_down) out[n++] = ec_event{EC_EV_POINTER_DOWN, 0, 0, x, y};
        else if (!d.down && d.was_down) out[n++] = ec_event{EC_EV_POINTER_UP, 0, 0, x, y};
        else if (d.down && d.moved) out[n++] = ec_event{EC_EV_POINTER_MOVE, 0, 0, x, y};
        d.was_down = d.down;
        d.moved = false;
      }
    }
  }
  return n;
}

// Devices with no shell or menu (a phone after `fastboot boot`): with
// EC_POWER_REBOOT=1 in the environment, holding POWER for kPowerHoldMs
// reboots.
static const int kPowerHoldMs = 3000;
static long long g_power_down_ns = -1;

static void check_power_hold() {
  if (g_power_down_ns < 0) return;
  static const bool enabled = [] { const char* e = getenv("EC_POWER_REBOOT"); return e && !strcmp(e, "1"); }();
  if (!enabled || ec_now_nanos() - g_power_down_ns < kPowerHoldMs * 1000000LL) return;
  ec_log("POWER held: rebooting");
  sync();
  reboot(RB_AUTOBOOT);
}

int ec_input_poll(int timeout_ms, ec_event* out, int max) {
  if (g_power_down_ns >= 0) {
    // Wake up in time to notice the hold even if no other event comes.
    int left = (int)((g_power_down_ns + kPowerHoldMs * 1000000LL - ec_now_nanos()) / 1000000) + 10;
    if (timeout_ms < 0 || timeout_ms > left) timeout_ms = std::max(left, 0);
  }
  if (g_epoll < 0) {
    if (timeout_ms > 0) usleep(timeout_ms * 1000);
    return 0;
  }
  epoll_event evs[kMaxDevices];
  int ready = epoll_wait(g_epoll, evs, kMaxDevices, timeout_ms);
  int n = 0;
  for (int i = 0; i < ready && n < max; ++i) {
    n += read_device(*static_cast<Device*>(evs[i].data.ptr), out + n, max - n);
  }
  for (int i = 0; i < n; ++i) {
    if (out[i].type == EC_EV_KEY && out[i].code == KEY_POWER && out[i].value != 2) {
      g_power_down_ns = out[i].value ? ec_now_nanos() : -1;
    }
  }
  check_power_hold();
  return n;
}
