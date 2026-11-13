package dev.plump.emulator;

/**
 * Pure portrait geometry. The caller supplies the content area AFTER Android
 * status/navigation/cutout insets. Never crop a native 4:3 DS panel to fit.
 */
final class DsPortraitSizing {
    static final class Panels {
        final int topWidth;
        final int lowerWidth;
        final int controlsHeight;

        Panels(int topWidth, int lowerWidth, int controlsHeight) {
            this.topWidth = topWidth;
            this.lowerWidth = lowerWidth;
            this.controlsHeight = controlsHeight;
        }

        int topHeight() { return topWidth * 3 / 4; }
        int lowerHeight() { return lowerWidth * 3 / 4; }
    }

    private DsPortraitSizing() { }

    static Panels calculate(int width, int height, int toolbar, int toggle,
                            int preferredControlHeight, boolean enlarged) {
        if (width <= 0 || height <= 0) return new Panels(0, 0, 0);
        // A small reserve avoids relying on exact rounding against gesture bars.
        int available = Math.max(0, height - toolbar - toggle - 4);
        int controlHeight = enlarged ? Math.min(preferredControlHeight, available / 5) : 0;
        available -= controlHeight;

        int bottomDesired = enlarged ? width : (int) (width * 0.62f);
        int topWidth;
        int lowerWidth;
        if (!enlarged) {
            double scale = Math.min(1.0, available / (0.75 * (width + bottomDesired)));
            topWidth = Math.min(width, (int) (width * scale));
            lowerWidth = Math.min(width, (int) (bottomDesired * scale));
        } else {
            // Prefer keeping the enlarged touchscreen full width, shrinking
            // only the upper screen if it can retain useful readable size.
            topWidth = Math.min(width, Math.max(0, (available * 4 / 3) - width));
            lowerWidth = width;
            if (topWidth < width * 0.58) {
                // On short devices neither panel can remain full width.
                // Reduce both coherently, giving the expanded lower screen
                // more area while keeping both 4:3 and their pixels separate.
                double scale = Math.min(1.0, available / (0.75 * width * 1.65));
                lowerWidth = (int) (width * scale);
                topWidth = (int) (width * 0.65 * scale);
            }
        }

        // The integer 4:3 heights must fit even on extremely short devices.
        while (topWidth > 0 && lowerWidth > 0 &&
                topWidth * 3 / 4 + lowerWidth * 3 / 4 > available) {
            if (topWidth > lowerWidth && !enlarged) --topWidth;
            else --lowerWidth;
        }
        return new Panels(topWidth, lowerWidth, controlHeight);
    }
}
