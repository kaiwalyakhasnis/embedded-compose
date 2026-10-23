// MAX7219 LED modules on a Raspberry Pi's SPI, through the kernel's spidev
// driver. A MAX7219 drives one 8x8 matrix of single-colour LEDs and keeps
// showing what it was last sent, so there is no refresh thread: an
// ec_flip() that changes the picture sends it, one byte per module and row.
// Modules are chained, DOUT to DIN; the common "4 in 1" boards (FC-16) are
// four of them, 32x8 LEDs.
//
// An LED is lit where the frame's brightest channel reaches the threshold:
// any colour on black shows, dim greys don't. The brightness (intensity) is
// one setting for the whole chain.
//
// Settings in EC_MAX7219, as name=value words, e.g. (the defaults)
//   EC_MAX7219='dev=/dev/spidev0.0 modules=4 intensity=2 threshold=128 flip=0 block=0 hz=1000000'
//   modules     8x8 modules in the chain: the panel is 8*modules x 8 LEDs
//   intensity   0-15
//   threshold   1-255, as above
//   flip=1      mounted the other way up: pins on the right, seen from the front
//   block       0, 90, 180 or 270: turns each module's picture, for modules
//               wired differently from FC-16 (letters turned within each 8x8)
//   hz          SPI clock (the MAX7219 takes up to 10 MHz)
#include "matrix.h"

#include "ec_hal.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/spi/spidev.h>
#include <pthread.h>
#include <semaphore.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

// MAX7219 registers. Digits 0-7 are the module's 8 rows of LEDs.
enum : uint8_t {
  kDigit0 = 0x01,
  kDecodeMode = 0x09,
  kIntensity = 0x0a,
  kScanLimit = 0x0b,
  kShutdown = 0x0c,
  kDisplayTest = 0x0f,
};

static struct {
  std::string dev = "/dev/spidev0.0";
  int modules = 4, intensity = 2, threshold = 128, flip = 0, block = 0, hz = 1000000;
} g_cfg;

static int g_fd = -1;
static std::mutex g_lock;  // the app's frames vs. shutdown
static sem_t g_stop;
static std::vector<uint8_t> g_sent;  // the digits last sent: module m's digit d at [m * 8 + d]
static timespec g_configured;        // when the settings were last sent
static bool g_failing;               // the last transfer failed (logged once)

static bool number(const std::string& s, int lo, int hi, int* out) {
  char* end;
  errno = 0;
  const long v = strtol(s.c_str(), &end, 10);
  if (s.empty() || *end || errno || v < lo || v > hi) return false;
  *out = (int)v;
  return true;
}

static bool parse(const char* flags) {
  std::istringstream in(flags ? flags : "");
  for (std::string w; in >> w;) {
    const size_t eq = w.find('=');
    const std::string name = w.substr(0, eq), value = eq == std::string::npos ? "" : w.substr(eq + 1);
    bool ok;
    if (name == "dev") {
      ok = !value.empty();
      g_cfg.dev = value;
    } else if (name == "modules") ok = number(value, 1, 64, &g_cfg.modules);
    else if (name == "intensity") ok = number(value, 0, 15, &g_cfg.intensity);
    else if (name == "threshold") ok = number(value, 1, 255, &g_cfg.threshold);
    else if (name == "flip") ok = number(value, 0, 1, &g_cfg.flip);
    else if (name == "block") ok = number(value, 0, 270, &g_cfg.block) && g_cfg.block % 90 == 0;
    else if (name == "hz") ok = number(value, 10000, 10000000, &g_cfg.hz);
    else ok = false;
    if (!ok) {
      ec_log(("max7219: bad setting in EC_MAX7219: " + w).c_str());
      return false;
    }
  }
  return true;
}

static bool spi_send(const std::vector<uint8_t>& buf) {
  spi_ioc_transfer t = {};
  t.tx_buf = (uintptr_t)buf.data();
  t.len = (uint32_t)buf.size();
  t.speed_hz = g_cfg.hz;
  t.bits_per_word = 8;
  if (ioctl(g_fd, SPI_IOC_MESSAGE(1), &t) < 0) {
    if (!g_failing) ec_log((std::string("max7219: SPI transfer failed: ") + strerror(errno)).c_str());
    g_failing = true;
    return false;
  }
  g_failing = false;
  return true;
}

// One register of every module, value(m) for module m (0 = next to the Pi).
// The bytes shift through the chain: the first ones out end in the last module.
template <typename F>
static bool send(uint8_t reg, F value) {
  std::vector<uint8_t> buf;
  for (int m = g_cfg.modules - 1; m >= 0; --m) {
    buf.push_back(reg);
    buf.push_back(value(m));
  }
  return spi_send(buf);
}

// The digits, or with `old` (what the modules show) only the ones that changed.
static bool send_digits(const std::vector<uint8_t>& digits, const std::vector<uint8_t>* old = nullptr) {
  bool ok = true;
  for (int d = 0; d < 8; ++d) {
    bool changed = !old;
    for (int m = 0; m < g_cfg.modules && !changed; ++m) changed = digits[m * 8 + d] != (*old)[m * 8 + d];
    if (changed) ok = send(kDigit0 + d, [&](int m) { return digits[m * 8 + d]; }) && ok;
  }
  return ok;
}

static bool configure() {
  clock_gettime(CLOCK_MONOTONIC, &g_configured);
  return send(kDisplayTest, [](int) { return 0; }) && send(kDecodeMode, [](int) { return 0; }) &&
         send(kScanLimit, [](int) { return 7; }) && send(kIntensity, [](int) { return g_cfg.intensity; }) &&
         send(kShutdown, [](int) { return 1; });
}

// A frame as digits, for FC-16 modules seen from the front with their pins
// on the left: module 0 is the leftmost, and its digit d lights row 7 - d,
// bit b column b.
static std::vector<uint8_t> encode(const uint8_t* rgb) {
  const int w = g_cfg.modules * 8;
  std::vector<uint8_t> digits(g_cfg.modules * 8);
  for (int y = 0; y < 8; ++y) {
    for (int x = 0; x < w; ++x, rgb += 3) {
      if (std::max({rgb[0], rgb[1], rgb[2]}) < g_cfg.threshold) continue;
      const int px = g_cfg.flip ? w - 1 - x : x;
      int u = px % 8, v = g_cfg.flip ? 7 - y : y;
      for (int turn = 0; turn < g_cfg.block / 90; ++turn) {  // a quarter turn within the module
        const int t = u;
        u = 7 - v;
        v = t;
      }
      digits[px / 8 * 8 + (7 - v)] |= 1 << u;
    }
  }
  return digits;
}

// Shutdown mode turns the LEDs off. Done at exit and on SIGINT/SIGTERM: the
// modules would otherwise keep showing the last frame.
static void panel_off() {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_fd < 0) return;
  send(kShutdown, [](int) { return 0; });
  close(g_fd);
  g_fd = -1;
}

// The handler may only post; this thread does the work.
static void on_signal(int) { sem_post(&g_stop); }
static void* stopper(void*) {
  while (sem_wait(&g_stop) != 0) {}
  ec_log("max7219: stopping");
  panel_off();
  _exit(0);
}

bool matrix_sink_init(int* width, int* height) {
  if (!parse(getenv("EC_MAX7219"))) return false;
  g_fd = open(g_cfg.dev.c_str(), O_RDWR | O_CLOEXEC);
  if (g_fd < 0) {
    ec_log(("max7219: " + g_cfg.dev + ": " + strerror(errno) + " (is SPI on? dtparam=spi=on in config.txt)").c_str());
    return false;
  }
  uint8_t mode = SPI_MODE_0, bits = 8;
  uint32_t hz = g_cfg.hz;
  if (ioctl(g_fd, SPI_IOC_WR_MODE, &mode) < 0 || ioctl(g_fd, SPI_IOC_WR_BITS_PER_WORD, &bits) < 0 ||
      ioctl(g_fd, SPI_IOC_WR_MAX_SPEED_HZ, &hz) < 0) {
    ec_log(("max7219: " + g_cfg.dev + " is not an SPI device: " + strerror(errno)).c_str());
    return false;
  }
  g_sent.assign(g_cfg.modules * 8, 0);
  if (!configure() || !send_digits(g_sent)) return false;

  sem_init(&g_stop, 0, 0);
  pthread_t thread;
  pthread_create(&thread, nullptr, stopper, nullptr);
  struct sigaction sa = {};
  sa.sa_handler = on_signal;
  sigaction(SIGINT, &sa, nullptr);
  sigaction(SIGTERM, &sa, nullptr);
  atexit(panel_off);

  *width = g_cfg.modules * 8;
  *height = 8;
  char msg[160];
  snprintf(msg, sizeof(msg), "max7219: %d modules, %dx8 LEDs on %s (intensity %d, threshold %d%s, block %d)",
           g_cfg.modules, *width, g_cfg.dev.c_str(), g_cfg.intensity, g_cfg.threshold,
           g_cfg.flip ? ", flipped" : "", g_cfg.block);
  ec_log(msg);
  return true;
}

void matrix_sink_show(const uint8_t* rgb) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_fd < 0) return;
  std::vector<uint8_t> digits = encode(rgb);
  // Noise on long wires can upset a MAX7219's registers: everything is sent
  // again once a second.
  timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  const bool refresh = now.tv_sec - g_configured.tv_sec >= 1;
  if (digits == g_sent && !refresh) return;
  if (refresh) configure();
  send_digits(digits, refresh ? nullptr : &g_sent);
  g_sent.swap(digits);
}

void matrix_sink_exit(void) { panel_off(); }
