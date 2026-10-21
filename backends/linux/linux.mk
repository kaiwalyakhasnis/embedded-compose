# Linux pieces shared by every backend: evdev input, clock, log, main().
LINUX_DIR := $(ROOT)/backends/linux
OBJS += $(OUT)/linux/input_evdev.o $(OUT)/linux/system.o

$(OUT)/linux/%.o: $(LINUX_DIR)/%.cpp $(HAL)/ec_hal.h
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -Wall -I$(HAL) -c -o $@ $<
