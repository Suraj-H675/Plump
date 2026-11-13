package dev.plump.emulator;

import org.junit.Test;
import static org.junit.Assert.*;

public class DsTouchMapperTest {
    @Test public void nativeCoordinatesAreInvariantUnderPanelEnlargement() {
        for (int width : new int[]{256, 384, 640, 1024}) {
            int height = width * 3 / 4;
            assertEquals(0, DsTouchMapper.x(0, width));
            assertEquals(0, DsTouchMapper.y(0, height));
            assertEquals(128, DsTouchMapper.x(width / 2f, width));
            assertEquals(96, DsTouchMapper.y(height / 2f, height));
            assertEquals(255, DsTouchMapper.x(width - 1, width));
            assertEquals(191, DsTouchMapper.y(height - 1, height));
        }
    }

    @Test public void touchIsRejectedOutsideLowerPanel() {
        assertFalse(DsTouchMapper.inside(-1, 15, 640, 480));
        assertFalse(DsTouchMapper.inside(20, 480, 640, 480));
        assertTrue(DsTouchMapper.inside(639, 479, 640, 480));
        assertFalse(DsTouchMapper.inside(0, 0, 0, 480));
    }

    @Test public void coordinatesClampSafely() {
        assertEquals(255, DsTouchMapper.x(10000, 100));
        assertEquals(0, DsTouchMapper.y(-10, 100));
        assertEquals(-1, DsTouchMapper.x(10, 0));
    }
}
