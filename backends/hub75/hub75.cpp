// HUB75 LED panels on a Raspberry Pi's GPIO header, driven by hzeller's
// rpi-rgb-led-matrix (fetched and built by backend.sh). The library refreshes
// the panel from its own real-time thread; each ec_flip() fills an offscreen
// canvas and swaps it in at the panel's next vertical sync, so frames never
// tear.
//
// The panel and its wiring are set with the library's own flags in
// EC_HUB75 (see its README), e.g.
//   EC_HUB75='--led-rows=32 --led-cols=64 --led-gpio-mapping=adafruit-hat --led-slowdown-gpio=2'
#include "matrix.h"

#include "ec_hal.h"

#include <pthread.h>
#include <semaphore.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "led-matrix.h"

using rgb_matrix::FrameCanvas;
using rgb_matrix::RGBMatrix;

static RGBMatrix* g_matrix;
static FrameCanvas* g_canvas;
static std::mutex g_lock;  // the app's frames vs. shutdown
static sem_t g_stop;

// Deleting the matrix stops the refresh thread and turns every LED off. Done
// at exit and on SIGINT/SIGTERM: a process that just dies leaves the row
// being scanned lit at full brightness.
static void panel_off() {
  std::lock_guard<std::mutex> lock(g_lock);
  delete g_matrix;
  g_matrix = nullptr;
}

// The handler may only post; this thread does the work.
static void on_signal(int) { sem_post(&g_stop); }
static void* stopper(void*) {
  while (sem_wait(&g_stop) != 0) {}
  ec_log("hub75: stopping");
  panel_off();
  _exit(0);
}

bool matrix_sink_init(int* width, int* height) {
  // The library reads its flags from an argv: make one from EC_HUB75.
  std::vector<std::string> words{"embedded-compose"};
  if (const char* flags = getenv("EC_HUB75")) {
    std::istringstream in(flags);
    for (std::string w; in >> w;) words.push_back(w);
  }
  std::vector<char*> args;
  for (std::string& w : words) args.push_back(&w[0]);
  args.push_back(nullptr);
  int argc = (int)words.size();
  char** argv = args.data();

  RGBMatrix::Options options;
  rgb_matrix::RuntimeOptions runtime;
  runtime.drop_privileges = -1;  // stay root: /dev/input is opened after the display
  runtime.daemon = -1;           // never fork; we then start the refresh thread ourselves
  if (!rgb_matrix::ParseOptionsFromFlags(&argc, &argv, &options, &runtime)) {
    rgb_matrix::PrintMatrixFlags(stderr, options, runtime);
    return false;
  }
  if (argc > 1) {
    ec_log((std::string("hub75: not a --led- flag in EC_HUB75: ") + argv[1]).c_str());
    return false;
  }
  g_matrix = RGBMatrix::CreateFromOptions(options, runtime);  // prints why if it fails
  if (!g_matrix) return false;
  if (!g_matrix->StartRefresh()) {
    ec_log("hub75: the refresh thread did not start");
    return false;
  }
  g_canvas = g_matrix->CreateFrameCanvas();
  *width = g_canvas->width();
  *height = g_canvas->height();

  sem_init(&g_stop, 0, 0);
  pthread_t thread;
  pthread_create(&thread, nullptr, stopper, nullptr);
  struct sigaction sa = {};
  sa.sa_handler = on_signal;
  sigaction(SIGINT, &sa, nullptr);
  sigaction(SIGTERM, &sa, nullptr);
  atexit(panel_off);

  char msg[160];
  snprintf(msg, sizeof(msg), "hub75: %dx%d pixels (%d rows x %d cols, chain %d, parallel %d, %s wiring)",
           *width, *height, options.rows, options.cols, options.chain_length, options.parallel,
           options.hardware_mapping ? options.hardware_mapping : "regular");
  ec_log(msg);
  return true;
}

void matrix_sink_show(const uint8_t* rgb) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (!g_matrix) return;
  const int w = g_canvas->width(), h = g_canvas->height();
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x, rgb += 3) g_canvas->SetPixel(x, y, rgb[0], rgb[1], rgb[2]);
  }
  g_canvas = g_matrix->SwapOnVSync(g_canvas);
}

void matrix_sink_exit(void) { panel_off(); }
