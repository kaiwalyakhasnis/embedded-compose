# MAX7219 LED modules on a Raspberry Pi's SPI: backends/matrix, with its
# frames sent through the kernel's spidev driver (max7219.cpp).
include $(ROOT)/backends/matrix/matrix.mk

OBJS += $(OUT)/backend/max7219.o

$(OUT)/backend/max7219.o: $(ROOT)/backends/max7219/max7219.cpp $(MATRIX_DIR)/matrix.h $(HAL)/ec_hal.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -Wall -I$(HAL) -I$(MATRIX_DIR) -c -o $@ $<
