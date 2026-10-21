# Links an app's Kotlin/Native static library with a backend into one static
# executable. Called by ./ec with the board's settings:
#
#   make -f backends/Makefile BACKEND=minui CROSS=aarch64-linux-gnu- \
#        KLIB=<libapp.a> OUT=<dir> BACKEND_FLAGS="<board defines>"
#
# backends/linux/linux.mk  : evdev input, clock, log, main() (every Linux backend)
# backends/<BACKEND>/backend.mk : the display, plus whatever it needs to build
ROOT    := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))..)
BACKEND ?= minui
CROSS   ?= aarch64-linux-gnu-
OUT     ?= $(ROOT)/out/native-$(BACKEND)
KLIB    ?= $(error KLIB=<Kotlin static library> is required)
BACKEND_FLAGS ?=

CC  := $(CROSS)gcc
CXX := $(CROSS)g++
HAL := $(ROOT)/core/hal

COMMON   := -O2 -fPIC -ffunction-sections -fdata-sections -Wno-unused-parameter
CFLAGS   := $(COMMON)
CXXFLAGS := $(COMMON) -std=gnu++17
OBJS     :=

all:

include $(ROOT)/backends/linux/linux.mk
include $(ROOT)/backends/$(BACKEND)/backend.mk

BIN := $(OUT)/embedded-compose
all: $(BIN)

$(BIN): $(OBJS) $(KLIB)
	$(CXX) -static -Wl,--gc-sections -s -o $@ $(OBJS) $(KLIB) -lpthread -ldl -lm

# Board flags are part of the build: rebuild when they change.
$(OUT)/flags: FORCE
	@mkdir -p $(OUT)
	@echo '$(CROSS) $(BACKEND_FLAGS)' | cmp -s - $@ || echo '$(CROSS) $(BACKEND_FLAGS)' > $@
$(OBJS): $(OUT)/flags

clean:
	rm -rf $(OUT)
.PHONY: all clean FORCE
