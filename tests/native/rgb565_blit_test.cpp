#include "../../app/src/main/cpp/rgb565_blit.hpp"

#include <array>
#include <cassert>
#include <cstdint>

int main() {
    // Two rows, 4 pixels each, with padding to 8 source pixels per row.
    // Destination has 2 extra stride pixels; padding must be untouched.
    std::array<uint8_t, 8 * 2 * 2> rgb{};
    const auto put = [&rgb](int x, int y, uint16_t pixel) {
        const int at = y * 16 + x * 2;
        rgb[at] = pixel & 0xff;
        rgb[at + 1] = pixel >> 8;
    };
    put(0, 0, 0xf800); // red
    put(1, 0, 0x07e0); // green
    put(2, 0, 0x001f); // blue
    put(3, 0, 0xffff); // white
    put(0, 1, 0x0000); // black
    put(1, 1, 0x8410); // middle gray
    put(2, 1, 0xf81f); // magenta
    put(3, 1, 0xffe0); // yellow

    std::array<uint8_t, 6 * 2 * 4> rgba{};
    rgba.fill(0x5a);
    blitRgb565ToRgba8888(rgb.data(), 16, rgba.data(), 6, 4, 2);
    const auto pixel = [&rgba](int x, int y, int channel) -> uint8_t {
        return rgba[(y * 6 + x) * 4 + channel];
    };
    assert(pixel(0, 0, 0) == 255 && pixel(0, 0, 1) == 0 && pixel(0, 0, 2) == 0);
    assert(pixel(1, 0, 1) == 255 && pixel(1, 0, 0) == 0 && pixel(1, 0, 2) == 0);
    assert(pixel(2, 0, 2) == 255 && pixel(2, 0, 0) == 0 && pixel(2, 0, 1) == 0);
    assert(pixel(3, 0, 0) == 255 && pixel(3, 0, 1) == 255 && pixel(3, 0, 2) == 255);
    assert(pixel(0, 1, 0) == 0 && pixel(0, 1, 1) == 0 && pixel(0, 1, 2) == 0);
    assert(pixel(1, 1, 0) >= 128 && pixel(1, 1, 0) <= 136);
    assert(pixel(2, 1, 0) == 255 && pixel(2, 1, 2) == 255);
    assert(pixel(3, 1, 0) == 255 && pixel(3, 1, 1) == 255);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 4; ++x) assert(pixel(x, y, 3) == 255);
        for (int x = 4; x < 6; ++x) {
            for (int channel = 0; channel < 4; ++channel)
                assert(pixel(x, y, channel) == 0x5a);
        }
    }
    return 0;
}
