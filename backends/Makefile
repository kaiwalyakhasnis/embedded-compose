# Links an app's Kotlin/Native static library with a backend into one static
# executable. Called by ./ec with the board's settings:
#
#   make -f backends/Makefile BACKEND=minui CROSS=aarch64-linux-gnu- \
#        KLIB=<libapp.a> OUT=<dir> BACKEND_FLAGS="<board defines>" [APP_SRCS="<app's .c/.cpp>"]
#
# backends/linux/linux.mk  : evdev input, clock, log, main() (every Linux backend)
# backends/<BACKEND>/backend.mk : the display, plus whatever it needs to build
ROOT    := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))..)
BACKEND ?= minui
CROSS   ?= aarch64-linux-gnu-
OUT     ?= $(ROOT)/out/native-$(BACKEND)
KLIB    ?= $(error KLIB=<Kotlin static library> is required)
BACKEND_FLAGS ?=
APP_SRCS ?=      # the app's own native code (app.env: APP_NATIVE), e.g. what its Kotlin calls via cinterop

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

# The app's sources, compiled with the HAL header and their own directory on the include path.
APP_OBJS := $(foreach s,$(APP_SRCS),$(OUT)/app/$(basename $(notdir $(s))).o)
OBJS += $(APP_OBJS)
define app_rule
$(OUT)/app/$(basename $(notdir $(1))).o: $(1) $(HAL)/ec_hal.h
	@mkdir -p $$(dir $$@)
	$$(if $$(filter %.c,$$<),$$(CC) $$(CFLAGS),$$(CXX) $$(CXXFLAGS)) -Wall -I$$(HAL) -I$$(dir $$<) -c -o $$@ $$<
endef
$(foreach s,$(APP_SRCS),$(eval $(call app_rule,$(s))))

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
