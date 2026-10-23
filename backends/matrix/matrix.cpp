// The display half of ec_hal.h for LED matrices (see matrix.h). Drawing
// goes into an RGB buffer in RAM and ec_flip() hands the whole frame to the
// sink. Panels are small (64x32 is 2048 pixels), so a full frame per flip is
// cheap, and blending is exact because the buffer is ordinary memory.
#include "matrix.h"

#include "ec_hal.h"

#include <png.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

#include "font_5x7.h"

static int g_w, g_h;
static float g_density;
static std::vector<uint8_t> g_rgb;
static bool g_blank;

int ec_display_init(void) {
  if (!matrix_sink_init(&g_w, &g_h) || g_w <= 0 || g_h <= 0) return -1;
  g_rgb.assign((size_t)g_w * g_h * 3, 0);
  // Pixels per dp from the board; on a matrix usually 1 (a dp is an LED).
  const char* d = getenv("EC_DENSITY");
  g_density = d ? strtof(d, nullptr) : 0;
  if (g_density <= 0) g_density = 1;
  return 0;
}

void ec_display_exit(void) { matrix_sink_exit(); }
int ec_width(void) { return g_w; }
int ec_height(void) { return g_h; }
float ec_density(void) { return g_density; }

static int g_r = 255, g_g = 255, g_b = 255, g_a = 255;

void ec_color(int r, int g, int b, int a) {
  g_r = std::clamp(r, 0, 255);
  g_g = std::clamp(g, 0, 255);
  g_b = std::clamp(b, 0, 255);
  g_a = std::clamp(a, 0, 255);
}

// Like minui's gr_clear(): the colour without its alpha.
void ec_clear(void) {
  for (size_t i = 0; i < g_rgb.size(); i += 3) {
    g_rgb[i] = g_r;
    g_rgb[i + 1] = g_g;
    g_rgb[i + 2] = g_b;
  }
}

static inline void blend(uint8_t* p, int r, int g, int b, int a) {
  if (a >= 255) {
    p[0] = r; p[1] = g; p[2] = b;
    return;
  }
  p[0] = (r * a + p[0] * (255 - a) + 127) / 255;
  p[1] = (g * a + p[1] * (255 - a) + 127) / 255;
  p[2] = (b * a + p[2] * (255 - a) + 127) / 255;
}

void ec_fill(int x1, int y1, int x2, int y2) {
  x1 = std::max(x1, 0);
  y1 = std::max(y1, 0);
  x2 = std::min(x2, g_w);
  y2 = std::min(y2, g_h);
  if (x1 >= x2 || y1 >= y2 || g_a == 0) return;
  for (int y = y1; y < y2; ++y) {
    uint8_t* p = &g_rgb[((size_t)y * g_w + x1) * 3];
    for (int x = x1; x < x2; ++x, p += 3) blend(p, g_r, g_g, g_b, g_a);
  }
}

// DEV ONLY (EC_DEV_CAPTURE=1): when /tmp/ec-capture exists, the frame just
// shown is written to /tmp/ec-frame.ppm and the request is removed, so what
// a panel shows can be checked from a shell, without a camera.
static void dev_capture() {
  static const bool enabled = [] { const char* e = getenv("EC_DEV_CAPTURE"); return e && atoi(e) > 0; }();
  if (!enabled || access("/tmp/ec-capture", F_OK) != 0) return;
  if (FILE* f = fopen("/tmp/ec-frame.tmp", "we")) {
    fprintf(f, "P6\n%d %d\n255\n", g_w, g_h);
    fwrite(g_rgb.data(), 1, g_rgb.size(), f);
    fclose(f);
    rename("/tmp/ec-frame.tmp", "/tmp/ec-frame.ppm");
  }
  unlink("/tmp/ec-capture");
}

void ec_flip(void) {
  if (!g_blank) matrix_sink_show(g_rgb.data());
  dev_capture();
}

// LEDs have no backlight: blanking shows a black frame, unblanking the last one.
void ec_blank(int blank) {
  g_blank = blank != 0;
  if (g_blank) {
    std::vector<uint8_t> black(g_rgb.size());
    matrix_sink_show(black.data());
  } else {
    matrix_sink_show(g_rgb.data());
  }
}

// ---- text: the 5x7 font in font_5x7.h, in 6x8 cells

static const int kGlyphW = 5, kGlyphH = 7;

int ec_font_width(void) { return kGlyphW + 1; }
int ec_font_height(void) { return kGlyphH + 1; }

// A glyph's rows as bit masks (bit 4 = leftmost column), decoded once.
static const uint8_t* glyph(unsigned char c) {
  static uint8_t rows[95][kGlyphH];
  static const bool decoded = [] {
    for (int i = 0; i < 95; ++i) {
      for (int j = 0; j < kGlyphH; ++j) {
        uint8_t m = 0;
        for (int k = 0; k < kGlyphW; ++k) m = (m << 1) | (kFont5x7[i][j * (kGlyphW + 1) + k] == '#');
        rows[i][j] = m;
      }
    }
    return true;
  }();
  (void)decoded;
  if (c < ' ' || c > '~') c = '?';
  return rows[c - ' '];
}

// scale >= 1 multiplies every font pixel; bold also lights the pixels just
// right of each lit one.
void ec_text(int x, int y, const char* s, int scale, int bold) {
  scale = std::max(scale, 1);
  const int extra = bold ? std::max(1, scale / 2) : 0;
  for (; *s; ++s, x += (kGlyphW + 1) * scale) {
    if (x >= g_w) break;
    if (x + (kGlyphW + 1) * scale <= 0) continue;
    const uint8_t* g = glyph(*s);
    for (int j = 0; j < kGlyphH; ++j) {
      for (int k = 0; k < kGlyphW; ++k) {
        if (!((g[j] >> (kGlyphW - 1 - k)) & 1)) continue;
        const int px = x + k * scale, py = y + j * scale;
        ec_fill(px, py, px + scale + extra, py + scale);
      }
    }
  }
}

// ---- images: <EC_RES>/<name>.png (default /res/images, as with minui)

struct Image {
  int w, h;
  std::vector<uint8_t> rgba;
};

long ec_image_load(const char* name) {
  const char* dir = getenv("EC_RES");
  const std::string path = std::string(dir && *dir ? dir : "/res/images") + "/" + name + ".png";
  png_image png;
  memset(&png, 0, sizeof(png));
  png.version = PNG_IMAGE_VERSION;
  if (!png_image_begin_read_from_file(&png, path.c_str())) return 0;
  png.format = PNG_FORMAT_RGBA;
  Image* img = new Image{(int)png.width, (int)png.height, std::vector<uint8_t>(PNG_IMAGE_SIZE(png))};
  if (!png_image_finish_read(&png, nullptr, img->rgba.data(), 0, nullptr)) {
    png_image_free(&png);
    delete img;
    return 0;
  }
  return reinterpret_cast<long>(img);
}

int ec_image_width(long handle) { return handle > 0 ? reinterpret_cast<Image*>(handle)->w : 0; }
int ec_image_height(long handle) { return handle > 0 ? reinterpret_cast<Image*>(handle)->h : 0; }

void ec_image_draw(long handle, int x, int y) {
  if (handle <= 0) return;
  const Image* img = reinterpret_cast<Image*>(handle);
  for (int j = std::max(0, -y); j < img->h && y + j < g_h; ++j) {
    for (int i = std::max(0, -x); i < img->w && x + i < g_w; ++i) {
      const uint8_t* s = &img->rgba[((size_t)j * img->w + i) * 4];
      if (s[3]) blend(&g_rgb[((size_t)(y + j) * g_w + x + i) * 3], s[0], s[1], s[2], s[3]);
    }
  }
}

void ec_image_free(long handle) {
  if (handle > 0) delete reinterpret_cast<Image*>(handle);
}
