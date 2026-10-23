# HUB75 LED panels on a Raspberry Pi: backends/matrix, with its frames shown
# through hzeller's rpi-rgb-led-matrix. The library is GPL-2.0 and not part
# of this repo: backend.sh downloads and builds it, and ./ec passes its
# directory as RGBMATRIX. A binary linked with it is covered by the GPL.
RGBMATRIX ?= $(error RGBMATRIX=<built rpi-rgb-led-matrix tree> is required (./ec sets it))
include $(ROOT)/backends/matrix/matrix.mk

OBJS += $(OUT)/backend/hub75.o
LIBS += $(RGBMATRIX)/lib/librgbmatrix.a

$(OUT)/backend/hub75.o: $(ROOT)/backends/hub75/hub75.cpp $(MATRIX_DIR)/matrix.h $(HAL)/ec_hal.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -Wall -I$(HAL) -I$(MATRIX_DIR) -I$(RGBMATRIX)/include -c -o $@ $<
