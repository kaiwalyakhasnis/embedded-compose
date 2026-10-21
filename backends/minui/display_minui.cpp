// The display part of ec_hal.h on AOSP recovery's minui (third_party/aosp,
// compiled unmodified). Every pixel goes through minui's gr_* functions; this
// file only clips (minui drops any shape that is partly off-screen) and draws
// the minui font at integer scales.
#include "ec_hal.h"

#include <fcntl.h>
#include <linux/fb.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>

#include "minui/minui.h"

static int g_w, g_h;
static float g_density;

int ec_display_init(void) {
  if (gr_init() != 0) return -1;
  g_w = gr_fb_width();
  g_h = gr_fb_height();
  // Pixels per dp come from the board (like ro.sf.lcd_density on Android).
  // Without it: 411 dp across, the width of a typical phone.
  const char* d = getenv("EC_DENSITY");
  g_density = d ? strtof(d, nullptr) : 0;
  if (g_density <= 0) g_density = g_w / 411.43f;
  return 0;
}

void ec_display_exit(void) { gr_exit(); }
int ec_width(void) { return g_w; }
int ec_height(void) { return g_h; }
float ec_density(void) { return g_density; }

// The last gr_color(), so scaled glyph edges can keep the caller's alpha.
static int g_r = 255, g_g = 255, g_b = 255, g_a = 255;

void ec_color(int r, int g, int b, int a) {
  g_r = std::clamp(r, 0, 255);
  g_g = std::clamp(g, 0, 255);
  g_b = std::clamp(b, 0, 255);
  g_a = std::clamp(a, 0, 255);
  gr_color(g_r, g_g, g_b, g_a);
}

void ec_clear(void) { gr_clear(); }

// gr_fill() silently drops a rectangle that is even partly off-screen, so
// clip here.
void ec_fill(int x1, int y1, int x2, int y2) {
  x1 = std::max(x1, 0);
  y1 = std::max(y1, 0);
  x2 = std::min(x2, g_w);
  y2 = std::min(y2, g_h);
  if (x1 >= x2 || y1 >= y2) return;
  gr_fill(x1, y1, x2, y2);
}

// DEV ONLY (EC_DEV_CAPTURE=1, or 2 for half size): screen capture without
// tearing. When /tmp/ec-capture exists, the frame just flipped to the screen
// is copied from fbdev to /tmp/ec-frame and the request is removed. The file
// is raw 32-bit pixels, rows packed (width / scale * 4 bytes). Done here,
// between frames, because the app may flip again while a slower reader
// outside is still copying.
static void dev_capture() {
  static const int scale = [] { const char* e = getenv("EC_DEV_CAPTURE"); return e ? atoi(e) : 0; }();
  if (scale <= 0 || access("/tmp/ec-capture", F_OK) != 0) return;
  int fd = open("/dev/graphics/fb0", O_RDONLY | O_CLOEXEC);
  if (fd < 0) fd = open("/dev/fb0", O_RDONLY | O_CLOEXEC);
  fb_var_screeninfo vi;
  fb_fix_screeninfo fi;
  if (fd >= 0 && ioctl(fd, FBIOGET_VSCREENINFO, &vi) == 0 && ioctl(fd, FBIOGET_FSCREENINFO, &fi) == 0) {
    const int w = vi.xres / scale, h = vi.yres / scale;
    uint32_t* row = static_cast<uint32_t*>(malloc(fi.line_length));
    uint32_t* frame = static_cast<uint32_t*>(malloc((size_t)w * h * 4));
    FILE* out = fopen("/tmp/ec-frame.tmp", "we");
    bool ok = row && frame && out;
    for (int y = 0; ok && y < h; ++y) {
      off_t at = (off_t)fi.line_length * (vi.yoffset + y * scale);
      ok = pread(fd, row, fi.line_length, at) == (ssize_t)fi.line_length;
      for (int x = 0; ok && x < w; ++x) frame[y * w + x] = row[x * scale];
    }
    if (ok) fwrite(frame, 4, (size_t)w * h, out);
    if (out) fclose(out);
    free(row);
    free(frame);
    rename("/tmp/ec-frame.tmp", "/tmp/ec-frame");
  }
  if (fd >= 0) close(fd);
  unlink("/tmp/ec-capture");
}

void ec_flip(void) {
  gr_flip();
  dev_capture();
}
void ec_blank(int blank) { gr_fb_blank(blank != 0); }

int ec_font_width(void) { return gr_sys_font()->char_width; }
int ec_font_height(void) { return gr_sys_font()->char_height; }

static void scaled_text(const GRFont* font, int x, int y, const char* s, int scale, bool bold) {
  const GRSurface* tex = font->texture;
  const int cw = font->char_width, ch = font->char_height;
  bold = bold && tex->height != ch;
  for (; *s; ++s, x += cw * scale) {
    unsigned char c = *s;
    if (c < ' ' || c > '~') c = '?';
    if (x >= g_w) break;
    if (x + cw * scale <= 0) continue;
    const unsigned char* glyph = tex->data + (c - ' ') * cw + (bold ? ch * tex->row_bytes : 0);
    for (int j = 0; j < ch; ++j) {
      const unsigned char* row = glyph + j * tex->row_bytes;
      int i = 0;
      while (i < cw) {
        unsigned char a = row[i];
        int run = i + 1;
        while (run < cw && row[run] == a) ++run;
        if (a) {
          gr_color(g_r, g_g, g_b, a * g_a / 255);
          ec_fill(x + i * scale, y + j * scale, x + run * scale, y + (j + 1) * scale);
        }
        i = run;
      }
    }
  }
  gr_color(g_r, g_g, g_b, g_a);
}

void ec_text(int x, int y, const char* s, int scale, int bold) {
  const GRFont* font = gr_sys_font();
  if (scale <= 1 && x >= 0 && y >= 0 && x + gr_measure(font, s) <= g_w && y + font->char_height <= g_h) {
    gr_text(font, x, y, s, bold != 0);
    return;
  }
  scaled_text(font, x, y, s, std::max(scale, 1), bold != 0);
}

// minui loads /res/images/<name>.png.
long ec_image_load(const char* name) {
  GRSurface* surface = nullptr;
  if (res_create_display_surface(name, &surface) < 0 || surface == nullptr) return 0;
  return reinterpret_cast<long>(surface);
}

int ec_image_width(long handle) { return handle > 0 ? gr_get_width(reinterpret_cast<GRSurface*>(handle)) : 0; }
int ec_image_height(long handle) { return handle > 0 ? gr_get_height(reinterpret_cast<GRSurface*>(handle)) : 0; }

// gr_blit() also drops anything partly off-screen: clip the source rect.
void ec_image_draw(long handle, int x, int y) {
  if (handle <= 0) return;
  GRSurface* s = reinterpret_cast<GRSurface*>(handle);
  int sx = 0, sy = 0, w = s->width, h = s->height;
  if (x < 0) { sx = -x; w += x; x = 0; }
  if (y < 0) { sy = -y; h += y; y = 0; }
  w = std::min(w, g_w - x);
  h = std::min(h, g_h - y);
  if (w <= 0 || h <= 0) return;
  gr_blit(s, sx, sy, w, h, x, y);
}

void ec_image_free(long handle) {
  if (handle > 0) res_free_surface(reinterpret_cast<GRSurface*>(handle));
}
