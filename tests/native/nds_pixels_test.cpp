#include "../../app/src/main/cpp/nds_pixels.hpp"
#include <array>
#include <cassert>

int main() {
    // Source pitch 260 pixels, two separate Android destination strides 260.
    std::array<uint8_t, 260 * 384 * 4> source{};
    std::array<uint8_t, 260 * 192 * 4> top{}, bottom{};
    top.fill(42);
    bottom.fill(42);
    const auto color = [&source](int x, int y, uint8_t blue, uint8_t green, uint8_t red) {
        size_t index = (static_cast<size_t>(y) * 260 + x) * 4;
        source[index] = blue;
        source[index + 1] = green;
        source[index + 2] = red;
    };
    color(1, 0, 3, 2, 1);
    color(2, 191, 6, 5, 4);
    color(3, 192, 9, 8, 7);
    color(4, 383, 12, 11, 10);
    assert(splitNdsFrame(source.data(), 256, 384, 260 * 4,
                         top.data(), 260, bottom.data(), 260));
    const auto check = [](const auto& array, int x, int y, int r, int g, int b) {
        const size_t i = (static_cast<size_t>(y) * 260 + x) * 4;
        assert(array[i] == r && array[i + 1] == g && array[i + 2] == b && array[i + 3] == 255);
    };
    check(top, 1, 0, 1, 2, 3);
    check(top, 2, 191, 4, 5, 6);
    check(bottom, 3, 0, 7, 8, 9);
    check(bottom, 4, 191, 10, 11, 12);
    assert(top[(0 * 260 + 256) * 4] == 42);
    assert(bottom[(0 * 260 + 256) * 4] == 42);
    assert(!splitNdsFrame(source.data(), 512, 192, 260 * 4, top.data(), 260, bottom.data(), 260));
    assert(ndsPointerCoordinate(0, 256) == -32768);
    assert(ndsPointerCoordinate(255, 256) == 32767);
    assert(ndsPointerCoordinate(0, 384) == -32768);
    assert(ndsPointerCoordinate(383, 384) == 32767);
    return 0;
}
