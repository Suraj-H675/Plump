package dev.plump.emulator;

import org.junit.Test;

import static org.junit.Assert.*;

public class DsFrameTimingTest {
    @Test public void histogramReportsPercentilesAndPeakWithoutDroppingOutliers() {
        DsFrameTiming stats = new DsFrameTiming();
        for (int i = 0; i < 98; i++) stats.record(8_000_000L);
        stats.record(29_000_000L);
        stats.record(90_000_000L);
        assertEquals(100, stats.count());
        assertEquals(8, stats.percentileMillis(95));
        assertEquals(29, stats.percentileMillis(99));
        assertEquals(64, stats.percentileMillis(100));
        assertEquals(90, stats.peakMillis());
        assertEquals(9, stats.averageMillis());
    }

    @Test public void resetClearsPreviousInterval() {
        DsFrameTiming stats = new DsFrameTiming();
        stats.record(16_800_000L);
        assertEquals(17, stats.percentileMillis(99));
        stats.reset();
        assertEquals(0, stats.count());
        assertEquals(0, stats.percentileMillis(95));
        stats.record(1_200_000L);
        assertEquals(2, stats.percentileMillis(99));
    }

    @Test public void invalidElapsedTimeCannotSkewMetrics() {
        DsFrameTiming stats = new DsFrameTiming();
        stats.record(-1L);
        assertEquals(0, stats.count());
        stats.record(0L);
        assertEquals(1, stats.count());
        assertEquals(0, stats.percentileMillis(95));
    }
}
