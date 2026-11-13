#pragma once
#include <cstddef>
#include <cstdint>

// Libretro melonDS DS presents two native 256x192 screens stacked in one
// XRGB8888 buffer. Each Android RGBA8888 SurfaceView receives its OWN panel.
// XRGB8888 is numerically 0x00RRGGBB on little-endian ARM64.
inline bool splitNdsFrame(const void* source, unsigned width, unsigned height,
                          size_t pitch, uint8_t* top, size_t topStride,
                          uint8_t* bottom, size_t bottomStride) {
    if (!source || !top || !bottom || width != 256 || height != 384 || pitch < 256 * 4 ||
        topStride < 256 || bottomStride < 256) return false;
    const auto* bytes = static_cast<const uint8_t*>(source);
    for (unsigned y = 0; y < 384; ++y) {
        uint8_t* dest = y < 192 ? top + static_cast<size_t>(y) * topStride * 4 :
                                     bottom + static_cast<size_t>(y - 192) * bottomStride * 4;
        const uint8_t* src = bytes + static_cast<size_t>(y) * pitch;
        for (unsigned x = 0; x < 256; ++x) {
            dest[x * 4] = src[x * 4 + 2];
            dest[x * 4 + 1] = src[x * 4 + 1];
            dest[x * 4 + 2] = src[x * 4];
            dest[x * 4 + 3] = 255;
        }
    }
    return true;
}

// DS stylus positions are local to the bottom 256x192 display, independent
// of Android scaling or the optional enlarged bottom panel.
inline int16_t ndsPointerCoordinate(int pixel, int maximum) {
    if (pixel < 0) pixel = 0;
    if (pixel >= maximum) pixel = maximum - 1;
    return static_cast<int16_t>(-32768 +
        (static_cast<int64_t>(pixel) * 65535) / (maximum - 1));
}
