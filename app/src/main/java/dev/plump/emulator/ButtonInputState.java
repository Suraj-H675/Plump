package dev.plump.emulator;

import java.util.HashMap;
import java.util.Map;

/** Per-touch-pointer state, separated from Android MotionEvent for input tests. */
final class ButtonInputState {
    private final Map<Integer, Integer> pointers = new HashMap<>();

    void clear() { pointers.clear(); }

    void setPointer(int pointerId, int heldButtons) {
        if (pointerId < 0) throw new IllegalArgumentException("Invalid pointer ID");
        if (heldButtons == 0) pointers.remove(pointerId);
        else pointers.put(pointerId, heldButtons);
    }

    int mask() {
        int combined = 0;
        for (int value : pointers.values()) combined |= value;
        return combined;
    }
}
