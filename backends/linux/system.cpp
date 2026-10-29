// Clock, logging, memory stats and the process entry point, for any Linux
// backend.
#include "ec_hal.h"

#include <fcntl.h>
#include <stdio.h>
#include <sys/reboot.h>
#include <time.h>
#include <unistd.h>

long long ec_now_nanos(void) {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// stderr, and the kernel log when there is one (devices without a console).
void ec_log(const char* msg) {
  static int kmsg = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
  fprintf(stderr, "embedded-compose: %s\n", msg);
  if (kmsg >= 0) dprintf(kmsg, "embedded-compose: %s\n", msg);
}

long ec_rss_kb(void) {
  long pages = 0, rss = 0;
  FILE* f = fopen("/proc/self/statm", "re");
  if (!f) return -1;
  if (fscanf(f, "%ld %ld", &pages, &rss) != 2) rss = -1;
  fclose(f);
  return rss < 0 ? -1 : rss * (sysconf(_SC_PAGESIZE) / 1024);
}

void ec_power_off(void) {
  ec_log("powering off");
  sync();
  reboot(RB_POWER_OFF);
}

// The program itself is Kotlin: the app's Main.kt exports ec_app_main.
// The generic ec_mask, for displays without their own: one blended fill per pixel.
__attribute__((weak)) void ec_mask(int x, int y, int w, int h, const unsigned char* mask, int r, int g, int b, int a) {
  for (int j = 0; j < h; ++j) {
    for (int i = 0; i < w; ++i) {
      int c = mask[j * w + i];
      if (c == 0) continue;
      ec_color(r, g, b, a * c / 255);
      ec_fill(x + i, y + j, x + i + 1, y + j + 1);
    }
  }
}

int main() { return ec_app_main(); }
