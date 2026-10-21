// The platform contract of embedded-compose: everything the Kotlin toolkit
// (core/ui, through cinterop) needs from the device. A backend implements it:
//
//   backends/linux/   input (evdev), clock, logging, memory stats, main()
//                     -- shared by every Linux backend
//   backends/minui/   display, font and images on AOSP recovery's minui
//
// A new display (an LED-matrix driver, a DRM-only path, ...) is another
// directory under backends/ implementing the "display" part below.
#ifndef EC_HAL_H
#define EC_HAL_H

#ifdef __cplusplus
extern "C" {
#endif

// ---- display ----
int   ec_display_init(void);       // 0 on success
void  ec_display_exit(void);
int   ec_width(void);              // pixels
int   ec_height(void);
float ec_density(void);            // pixels per dp (board config: EC_DENSITY)
void  ec_color(int r, int g, int b, int a);
void  ec_clear(void);
void  ec_fill(int x1, int y1, int x2, int y2);  // x2/y2 exclusive; clipped to the screen
void  ec_flip(void);
void  ec_blank(int blank);

// Bitmap font of the display (one size). scale >= 1 multiplies every pixel.
int  ec_font_width(void);
int  ec_font_height(void);
void ec_text(int x, int y, const char* s, int scale, int bold);

// PNG images by name (the backend decides where they live).
// Returns a handle (> 0) or <= 0 on failure.
long ec_image_load(const char* name);
int  ec_image_width(long handle);
int  ec_image_height(long handle);
void ec_image_draw(long handle, int x, int y);  // clipped
void ec_image_free(long handle);

// ---- input ----
enum {
  EC_EV_KEY = 1,          // code = linux KEY_*, value = 1 down / 0 up / 2 repeat
  EC_EV_POINTER_DOWN = 2, // x, y in screen pixels
  EC_EV_POINTER_MOVE = 3,
  EC_EV_POINTER_UP = 4,
};

typedef struct {
  int type;
  int code;
  int value;
  int x;
  int y;
} ec_event;

int ec_input_open(void);   // number of input devices opened (call after ec_display_init)
// Waits up to timeout_ms (-1 = forever) for input; returns events written to out.
int ec_input_poll(int timeout_ms, ec_event* out, int max);

// ---- system ----
long long ec_now_nanos(void);  // monotonic clock
void ec_log(const char* msg);
long ec_rss_kb(void);          // resident memory, -1 if unknown
void ec_power_off(void);       // syncs and powers the device off

// Implemented by the app (Kotlin, exported with @CName); called by the
// backend's main().
int ec_app_main(void);

#ifdef __cplusplus
}
#endif

#endif
