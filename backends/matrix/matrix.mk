# LED matrices: the display half of ec_hal.h on an RGB buffer in RAM
# (matrix.cpp), shown by a sink in the including backend (hub75, max7219).
# PNGs are decoded with the vendored libpng (backends/aosp.mk).
include $(ROOT)/backends/aosp.mk

MATRIX_DIR := $(ROOT)/backends/matrix
OBJS += $(AOSP_PNG_OBJS) $(OUT)/matrix/matrix.o

$(OUT)/matrix/matrix.o: $(MATRIX_DIR)/matrix.cpp $(MATRIX_DIR)/matrix.h $(MATRIX_DIR)/font_5x7.h $(HAL)/ec_hal.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -Wall -I$(HAL) -I$(PNG) -I$(ZLIB) -c -o $@ $<
