# AOSP sources vendored unmodified in third_party/aosp, built with the flags
# of their Android.mk. A backend adds the object lists it uses to OBJS:
#
#   AOSP_MINUI_OBJS   minui, with libdrm, libadf, libsync and libbase strings
#                     (needs AOSP_PNG_OBJS too)
#   AOSP_PNG_OBJS     libpng + zlib
#
# minui's board flags (BACKEND_FLAGS), as TARGET_RECOVERY_* would set them:
#   -DRECOVERY_BGRA / -DRECOVERY_ABGR / -DRECOVERY_RGBX   pixel format
#   (none: RGBA byte order on fbdev, RGB565 on DRM)
ifndef AOSP_MK
AOSP_MK := 1

AOSP  ?= $(ROOT)/third_party/aosp
MINUI := $(AOSP)/bootable/recovery/minui
PNG   := $(AOSP)/external/libpng
ZLIB  := $(AOSP)/external/zlib/src
DRM   := $(AOSP)/external/libdrm
ADF   := $(AOSP)/system/core/adf/libadf
SYNC  := $(AOSP)/system/core/libsync
BASE  := $(AOSP)/system/core/base

AOSP_INCS := -I$(MINUI)/include -I$(MINUI) -I$(PNG) -I$(ZLIB) -I$(DRM) -I$(DRM)/include/drm \
             -I$(ADF)/include -I$(SYNC)/include -I$(BASE)/include
# bionic headers pull these in implicitly; glibc ones do not.
AOSP_COMPAT := -include stddef.h -include stdint.h -include string.h -D__user= -D__unused=
AOSP_C   := $(CFLAGS) $(AOSP_COMPAT) $(AOSP_INCS)
AOSP_CXX := $(CXXFLAGS) $(AOSP_COMPAT) $(AOSP_INCS)
QUIET    := -w   # vendored code is built as-is; its warnings are not ours to fix

MINUI_FLAGS := -DOVERSCAN_PERCENT=0 $(BACKEND_FLAGS)
DRM_FLAGS   := -DMAJOR_IN_SYSMACROS=1 -DHAVE_VISIBILITY=1 -DHAVE_LIBDRM_ATOMIC_PRIMITIVES=1
PNG_FLAGS   := -std=gnu89 -DPNG_ARM_NEON_OPT=0

MINUI_SRCS := events.cpp graphics.cpp graphics_adf.cpp graphics_drm.cpp graphics_fbdev.cpp resources.cpp
PNG_SRCS   := png.c pngerror.c pngget.c pngmem.c pngpread.c pngread.c pngrio.c pngrtran.c pngrutil.c \
              pngset.c pngtrans.c pngwio.c pngwrite.c pngwtran.c pngwutil.c
ZLIB_SRCS  := adler32.c compress.c crc32.c deflate.c gzclose.c gzlib.c gzread.c gzwrite.c infback.c \
              inflate.c inftrees.c inffast.c trees.c uncompr.c zutil.c
DRM_SRCS   := xf86drm.c xf86drmHash.c xf86drmRandom.c xf86drmSL.c xf86drmMode.c

AOSP_MINUI_OBJS := $(MINUI_SRCS:%.cpp=$(OUT)/minui/%.o) \
                   $(DRM_SRCS:%.c=$(OUT)/drm/%.o) \
                   $(OUT)/adf/adf.o $(OUT)/sync/sync.o $(OUT)/base/strings.o
AOSP_PNG_OBJS   := $(PNG_SRCS:%.c=$(OUT)/png/%.o) $(ZLIB_SRCS:%.c=$(OUT)/zlib/%.o)

$(OUT)/minui/%.o: $(MINUI)/%.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(AOSP_CXX) $(MINUI_FLAGS) $(QUIET) -c -o $@ $<
$(OUT)/png/%.o: $(PNG)/%.c
	@mkdir -p $(dir $@)
	$(CC) $(AOSP_C) $(PNG_FLAGS) $(QUIET) -c -o $@ $<
$(OUT)/zlib/%.o: $(ZLIB)/%.c
	@mkdir -p $(dir $@)
	$(CC) $(AOSP_C) $(QUIET) -DHAVE_HIDDEN -DZLIB_CONST -c -o $@ $<
$(OUT)/drm/%.o: $(DRM)/%.c
	@mkdir -p $(dir $@)
	$(CC) $(AOSP_C) $(DRM_FLAGS) $(QUIET) -c -o $@ $<
$(OUT)/adf/adf.o: $(ADF)/adf.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(AOSP_CXX) $(QUIET) -c -o $@ $<
$(OUT)/sync/sync.o: $(SYNC)/sync.c
	@mkdir -p $(dir $@)
	$(CC) $(AOSP_C) $(QUIET) -c -o $@ $<
$(OUT)/base/strings.o: $(BASE)/strings.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(AOSP_CXX) $(QUIET) -c -o $@ $<

endif
