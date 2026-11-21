package dev.plump.emulator;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class StorageCapacityTest {
    private static final long REQUIRED = 768L * 1024 * 1024;
    private static final Path DIRECTORY = Paths.get("private-test-dir");

    @Test public void exactThresholdRequiresBothChecksOnSameDirectory() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                path -> { assertEquals(DIRECTORY, path); checks.incrementAndGet(); return REQUIRED; },
                path -> { assertEquals(DIRECTORY, path); checks.incrementAndGet(); return REQUIRED; });
        capacity.require(DIRECTORY, REQUIRED, "launch");
        assertEquals(2, checks.get());
    }

    @Test public void physicalCapacityCannotBeReplacedByHigherAllocatableCapacity() throws Exception {
        for (long bytes : new long[]{0, REQUIRED - 1}) {
            StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                    path -> bytes, path -> REQUIRED * 2);
            assertRejected(capacity, "Insufficient storage filesystem capacity");
        }
    }

    @Test public void allocationRejectionBlocksEvenWhenFilesystemHasSpace() throws Exception {
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                path -> REQUIRED * 2, path -> REQUIRED - 1);
        assertRejected(capacity, "Insufficient storage allocation capacity");
    }

    @Test public void inaccessibleFilesystemFailsClosedWithOriginalCause() throws Exception {
        SecurityException denial = new SecurityException("filesystem query denied");
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                path -> { throw denial; }, path -> { fail("Cannot bypass failed filesystem probe"); return 0; });
        IOException error = assertRejected(capacity, "Storage filesystem capacity unavailable");
        assertSame(denial, error.getCause());
    }

    @Test public void unsupportedAllocationQueryFailsClosedWithOriginalCause() throws Exception {
        IOException unavailable = new IOException("volume UUID unavailable");
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                path -> REQUIRED, path -> { throw unavailable; });
        assertSame(unavailable, assertRejected(capacity, "Storage allocation capacity unavailable").getCause());
    }

    @Test public void invalidNegativeInformationIsRejectedByEitherProbe() throws Exception {
        assertRejected(StorageCapacity.withAllocationPreflight(path -> -1, path -> REQUIRED),
                "Invalid storage filesystem capacity");
        assertRejected(StorageCapacity.withAllocationPreflight(path -> REQUIRED, path -> -1),
                "Invalid storage allocation capacity");
    }

    private static IOException assertRejected(StorageCapacity capacity, String message) throws Exception {
        try {
            capacity.require(DIRECTORY, REQUIRED, "launch");
            fail("Unsafe capacity accepted");
            throw new AssertionError();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
            return expected;
        }
    }
}
