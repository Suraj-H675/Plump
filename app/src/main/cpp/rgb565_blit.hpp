#pragma once

#include <cstddef>
#include <cstdint>

// mGBA's Libretro framebuffer uses little-endian RGB565, with each row
// padded to 256 pixels. Android's RGBA_8888 destination may have a wider
// stride. This conversion is independent of Android and can be host-tested.
inline void blitRgb565ToRgba8888(const void* source, size_t sourcePitchBytes,
                                uint8_t* destination, size_t destinationStridePixels,
                                unsigned width, unsigned height) {
    const auto* bytes = static_cast<const uint8_t*>(source);
    for (unsigned y = 0; y < height; ++y) {
        const uint8_t* row = bytes + static_cast<size_t>(y) * sourcePitchBytes;
        uint8_t* dest = destination + static_cast<size_t>(y) * destinationStridePixels * 4;
        for (unsigned x = 0; x < width; ++x) {
            const uint16_t pixel = static_cast<uint16_t>(row[x * 2]) |
                static_cast<uint16_t>(row[x * 2 + 1]) << 8;
            const uint8_t red = static_cast<uint8_t>((pixel >> 11) & 31);
            const uint8_t green = static_cast<uint8_t>((pixel >> 5) & 63);
            const uint8_t blue = static_cast<uint8_t>(pixel & 31);
            dest[x * 4] = static_cast<uint8_t>((red << 3) | (red >> 2));
            dest[x * 4 + 1] = static_cast<uint8_t>((green << 2) | (green >> 4));
            dest[x * 4 + 2] = static_cast<uint8_t>((blue << 3) | (blue >> 2));
            dest[x * 4 + 3] = 255;
        }
    }
}
