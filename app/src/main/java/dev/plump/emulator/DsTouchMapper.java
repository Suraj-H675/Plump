package dev.plump.emulator;

/** Native DS screen pixels from either portrait panel size (no pointer offset). */
final class DsTouchMapper {
    static final int WIDTH = 256;
    static final int HEIGHT = 192;

    private DsTouchMapper() {}

    static int coordinate(float position, int viewSize, int dsSize) {
        if (viewSize <= 0 || Float.isNaN(position)) return -1;
        int pixel = (int) (position * dsSize / viewSize);
        return Math.max(0, Math.min(dsSize - 1, pixel));
    }

    static boolean inside(float x, float y, int width, int height) {
        return width > 0 && height > 0 && x >= 0 && y >= 0 && x < width && y < height;
    }

    static int x(float localX, int width) { return coordinate(localX, width, WIDTH); }
    static int y(float localY, int height) { return coordinate(localY, height, HEIGHT); }
}
