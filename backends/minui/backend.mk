# Display on AOSP recovery's minui (vendored, see backends/aosp.mk; its
# pixel-format flags come from the board's BACKEND_FLAGS).
include $(ROOT)/backends/aosp.mk

OBJS += $(AOSP_MINUI_OBJS) $(AOSP_PNG_OBJS) $(OUT)/backend/display_minui.o

$(OUT)/backend/display_minui.o: $(ROOT)/backends/minui/display_minui.cpp $(HAL)/ec_hal.h
	@mkdir -p $(dir $@)
	$(CXX) $(AOSP_CXX) $(MINUI_FLAGS) -Wall -I$(HAL) -c -o $@ $<
