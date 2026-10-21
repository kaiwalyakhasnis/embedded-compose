// Clock, logging, memory stats and the process entry point, for any Linux
// backend.
#include "ec_hal.h"

#include <fcntl.h>
#include <stdio.h>
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

// The program itself is Kotlin: the app's Main.kt exports ec_app_main.
int main() { return ec_app_main(); }
