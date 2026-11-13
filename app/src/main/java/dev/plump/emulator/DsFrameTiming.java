package dev.plump.emulator;

import java.util.Arrays;

/** Allocation-free per-frame latency histogram; no changes to emulator timing. */
final class DsFrameTiming {
    private static final int LAST_BUCKET_MS = 64;
    private final int[] buckets = new int[LAST_BUCKET_MS + 1];
    private int count;
    private long totalNanos;
    private long maxNanos;

    void record(long elapsedNanos) {
        if (elapsedNanos < 0) return;
        int roundedMs = (int) Math.min(LAST_BUCKET_MS, (elapsedNanos + 999_999L) / 1_000_000L);
        buckets[roundedMs]++;
        count++;
        totalNanos += elapsedNanos;
        maxNanos = Math.max(maxNanos, elapsedNanos);
    }

    int count() { return count; }
    long averageMillis() { return count == 0 ? 0 : totalNanos / count / 1_000_000; }
    long peakMillis() { return maxNanos / 1_000_000; }

    /** Millisecond-rounded latency percentile; the 64ms bucket means >=64ms. */
    int percentileMillis(int percentage) {
        if (count == 0 || percentage < 1 || percentage > 100) return 0;
        int rank = (int) Math.ceil((double) count * percentage / 100.0);
        int seen = 0;
        for (int ms = 0; ms < buckets.length; ++ms) {
            seen += buckets[ms];
            if (seen >= rank) return ms;
        }
        return LAST_BUCKET_MS;
    }

    void reset() {
        Arrays.fill(buckets, 0);
        count = 0;
        totalNanos = maxNanos = 0;
    }
}
