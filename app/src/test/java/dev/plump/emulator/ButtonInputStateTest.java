package dev.plump.emulator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ButtonInputStateTest {
    private static final int A = 1 << 8;
    private static final int B = 1;
    private static final int UP = 1 << 4;
    private static final int L = 1 << 10;

    @Test public void multipleIndependentPointersKeepButtonsHeld() {
        ButtonInputState state = new ButtonInputState();
        state.setPointer(2, A);
        state.setPointer(9, L | UP);
        assertEquals(A | L | UP, state.mask());
        state.setPointer(2, 0);
        assertEquals(L | UP, state.mask());
        state.setPointer(9, 0);
        assertEquals(0, state.mask());
    }

    @Test public void canceledGestureClearsAllInputsWithoutSticking() {
        ButtonInputState state = new ButtonInputState();
        state.setPointer(12, A);
        state.setPointer(7, B);
        state.clear(); // Android ACTION_CANCEL or Activity.onPause.
        assertEquals(0, state.mask());
        state.setPointer(12, UP);
        assertEquals(UP, state.mask());
    }

    @Test public void movedPointerReplacesRatherThanAccumulatesButtons() {
        ButtonInputState state = new ButtonInputState();
        state.setPointer(4, A);
        state.setPointer(4, B);
        assertEquals(B, state.mask());
        state.setPointer(4, 0); // Drag outside the virtual controls.
        assertEquals(0, state.mask());
    }
}
