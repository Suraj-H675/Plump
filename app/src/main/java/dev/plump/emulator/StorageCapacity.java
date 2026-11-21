package dev.plump.emulator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only capacity gate for a prospective write to an existing directory.
 * Platform queries are injected so Android need not enumerate Unix mounts via
 * Files.getFileStore(). Capacity is advisory, never a reservation: callers must
 * continue to handle copy/write/force failures transactionally.
 */
final class StorageCapacity {
    @FunctionalInterface
    interface Probe {
        long bytes(Path directory) throws IOException;
    }

    private final Probe filesystem;
    private final Probe allocation;

    private StorageCapacity(Probe filesystem, Probe allocation) {
        this.filesystem = Objects.requireNonNull(filesystem);
        this.allocation = allocation;
    }

    /** Host-only convenience for existing Linux generation-store callers. */
    static StorageCapacity host() {
        // File.getUsableSpace returns zero if capacity cannot be determined.
        // Zero fails every positive-size request.
        return new StorageCapacity(path -> path.toFile().getUsableSpace(), null);
    }

    /** Require independent filesystem and platform allocation checks. */
    static StorageCapacity withAllocationPreflight(Probe filesystem, Probe allocation) {
        return new StorageCapacity(filesystem, Objects.requireNonNull(allocation));
    }

    void require(Path directory, long requiredBytes, String operation) throws IOException {
        if (requiredBytes <= 0) throw new IllegalArgumentException("Positive capacity required");
        check(filesystem, directory, requiredBytes, operation, "filesystem");
        if (allocation != null)
            check(allocation, directory, requiredBytes, operation, "allocation");
    }

    private static void check(Probe probe, Path directory, long requiredBytes,
                              String operation, String kind) throws IOException {
        final long available;
        try {
            available = probe.bytes(directory);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Storage " + kind + " capacity unavailable for " + operation,
                    failure);
        }
        if (available < 0)
            throw new IOException("Invalid storage " + kind + " capacity for " + operation);
        if (available < requiredBytes)
            throw new IOException("Insufficient storage " + kind + " capacity for " + operation
                    + ": required " + requiredBytes + " bytes, available " + available + " bytes");
    }
}
