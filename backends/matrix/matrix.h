// LED matrices: backends/matrix/matrix.cpp implements the display half of
// ec_hal.h on an RGB pixel buffer in RAM, one byte per channel. A sink
// shows that buffer on a panel: backends/hub75 (HUB75 panels on a
// Raspberry Pi), backends/max7219 (MAX7219 modules on a Raspberry Pi's SPI).
#ifndef EC_MATRIX_H
#define EC_MATRIX_H

#include <stdint.h>

// Opens the panel and reports its size in pixels. false on failure.
bool matrix_sink_init(int* width, int* height);

// Shows a whole frame: width * height pixels, rows packed, bytes R, G, B.
void matrix_sink_show(const uint8_t* rgb);

void matrix_sink_exit(void);

#endif
