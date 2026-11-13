package dev.plump.emulator;

import org.junit.Test;

import static org.junit.Assert.*;

public class DsPortraitSizingTest {
    private static void verify(int width, int contentHeight, int densityTimes100) {
        int toolbar = 56 * densityTimes100 / 100;
        int toggle = 43 * densityTimes100 / 100;
        int controls = 130 * densityTimes100 / 100;
        DsPortraitSizing.Panels regular = DsPortraitSizing.calculate(
                width, contentHeight, toolbar, toggle, controls, false);
        DsPortraitSizing.Panels expanded = DsPortraitSizing.calculate(
                width, contentHeight, toolbar, toggle, controls, true);

        assertTrue(regular.topWidth > regular.lowerWidth);
        assertTrue(regular.topWidth <= width);
        assertTrue(expanded.lowerWidth > regular.lowerWidth);
        assertTrue(expanded.topWidth <= width);
        assertTrue(expanded.lowerWidth <= width);
        assertEquals(0, regular.controlsHeight);
        assertTrue(expanded.controlsHeight > 0);
        assertTrue(toolbar + toggle + regular.topHeight() + regular.lowerHeight() + 4
                <= contentHeight);
        assertTrue(toolbar + toggle + expanded.topHeight() + expanded.lowerHeight()
                + expanded.controlsHeight + 4 <= contentHeight);

        // Touch coordinates stay identical at the midpoint of every valid
        // lower panel size, even as its physical dimensions change.
        assertEquals(128, DsTouchMapper.x(regular.lowerWidth / 2f, regular.lowerWidth));
        assertEquals(96, DsTouchMapper.y(regular.lowerHeight() / 2f,
                regular.lowerHeight()));
        assertEquals(128, DsTouchMapper.x(expanded.lowerWidth / 2f, expanded.lowerWidth));
        assertEquals(96, DsTouchMapper.y(expanded.lowerHeight() / 2f,
                expanded.lowerHeight()));
    }

    @Test public void iqooNeo6ContentAreaAfterBarsFitsBothModes() {
        // 1080x2400, 440 DPI: 88px status + approx. 100px navigation area.
        verify(1080, 2212, 275);
    }

    @Test public void compactAndroidPortraitFitsBothModes() {
        verify(720, 1260, 250);
    }

    @Test public void wideHighDensityPortraitFitsBothModes() {
        verify(1440, 2620, 350);
    }

    @Test public void veryShortContentAreaStillFitsWithoutCropping() {
        verify(720, 990, 225);
    }

    @Test public void invalidViewportDoesNotEmitNegativeGeometry() {
        DsPortraitSizing.Panels missing = DsPortraitSizing.calculate(0, 1300, 130, 95, 300, true);
        assertEquals(0, missing.topWidth);
        assertEquals(0, missing.lowerWidth);
        assertEquals(0, missing.controlsHeight);
    }
}
