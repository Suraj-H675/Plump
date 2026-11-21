package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.*;

public final class VirtualSdGenerationStoreTest {
    private static final long IMAGE_BYTES = 1024 * 1024;
    private static final CheckpointStore.Identity IDENTITY = new CheckpointStore.Identity("ds",
            repeat('a', 64), "melondsds-libretro", repeat('c', 40), repeat('d', 40),
            repeat('b', 64), "x86_64", 1);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void commitsClosedWorkingImageAsNewImmutableGeneration() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path baseline = createImage(root.resolve("baseline.img"), 11);
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(baseline);
        byte[] original = Files.readAllBytes(first.imageFile);

        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        assertEquals(session.saveDirectory.resolve(VirtualSdGenerationStore.IMAGE_RELATIVE), session.imageFile);
        writeAt(session.imageFile, 8192, (byte) 77);
        String checkpointId = UUID.randomUUID().toString();
        VirtualSdGenerationStore.Generation second = store.commit(session, goodReceipt(), checkpointId);

        assertEquals(2, second.sequence);
        assertNotEquals(first.id, second.id);
        assertArrayEquals("committed generations must never mutate their parent", original,
                Files.readAllBytes(first.imageFile));
        assertEquals((byte) 77, readAt(second.imageFile, 8192));
        assertEquals("canonical", second.parentBranchId);
        assertEquals(first.id, second.parentGenerationId);
        assertEquals(checkpointId, second.checkpointId);
        assertEquals(second.id, store.current().id);
        assertTrue(Files.isRegularFile(root.resolve("canonical/HEAD.0")));
        assertTrue(Files.isRegularFile(root.resolve("canonical/HEAD.1")));
    }

    @Test public void readOnlyBaselineProducesWritableWorkingCopy() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path baseline = createImage(root.resolve("baseline.img"), 13);
        assertTrue(baseline.toFile().setReadOnly());
        assertFalse(Files.isWritable(baseline));
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(baseline);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        assertTrue("working copies must be writable even when the immutable source is read-only",
                Files.isWritable(session.imageFile));
        writeAt(session.imageFile, 2048, (byte) 14);
        store.abort(session);
        assertEquals(first.id, store.current().id);
    }

    @Test public void failedNativeCloseReceiptCannotAdvanceHead() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 21));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 4096, (byte) 99);

        VirtualSdGenerationStore.CoreCloseReceipt failed = new VirtualSdGenerationStore.CoreCloseReceipt(
                1, 4, 10, 10, IMAGE_BYTES, 0, 1, 0, 1, false, false,
                true, true, true, false, true, true);
        try {
            store.commit(session, failed);
            fail("a failed native DLDI write must not be committed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not safe to commit"));
        }
        assertEquals(first.id, store.current().id);

        // The failed session is abandoned; the next open quarantines it and
        // starts from the last verified immutable generation.
        VirtualSdGenerationStore.WorkingSession next = store.openSession();
        assertEquals(first.imageSha256, sha(next.imageFile));
        assertEquals(1, countChildren(root.resolve("canonical/quarantine")));
        store.abort(next);
        assertEquals(first.id, store.current().id);
        assertEquals(2, countChildren(root.resolve("canonical/quarantine")));
    }

    @Test public void orphanGenerationAfterInterruptedPublishIsNeverPromoted() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore firstStore = store(storeRoot, "canonical", point -> {
            if (point == failAt[0]) throw new IOException("injected after generation rename");
        });
        VirtualSdGenerationStore.Generation first = firstStore.initialize(createImage(root.resolve("base.img"), 31));
        VirtualSdGenerationStore.WorkingSession session = firstStore.openSession();
        writeAt(session.imageFile, 16384, (byte) 123);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_GENERATION_RENAME;
        try {
            firstStore.commit(session, goodReceipt());
            fail("injected publication interruption should fail the operation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertEquals("the old head remains authoritative until pointer publication", first.id,
                firstStore.current().id);
        assertEquals("the unreferenced generation remains evidence, not an automatic recovery choice", 2,
                countChildren(storeRoot.resolve("generations")));
        VirtualSdGenerationStore.WorkingSession retry = firstStore.openSession();
        assertEquals(first.imageSha256, sha(retry.imageFile));
        firstStore.abort(retry);
    }

    @Test public void interruptionAfterHeadRenameLeavesOnlyValidOldOrNewGeneration() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore store = store(storeRoot, "canonical", point -> {
            if (point == failAt[0]) throw new IOException("injected after head rename");
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 41));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 32768, (byte) 101);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME;
        try {
            store.commit(session, goodReceipt());
            fail("directory-sync fault should not be reported as a successful commit");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        VirtualSdGenerationStore.Generation recovered = store.current();
        assertTrue(recovered.sequence == first.sequence || recovered.sequence == first.sequence + 1);
        assertTrue("recovery may select only a fully verified generation",
                recovered.id.equals(first.id) || Files.isRegularFile(recovered.imageFile));
    }

    @Test public void fsyncedTemporaryHeadIsNotPromotedBeforeAtomicRename() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", point -> {
            if (point == failAt[0]) throw new IOException("injected before head rename");
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 46));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 49152, (byte) 102);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_FILE_FORCE;

        try {
            store.commit(session, goodReceipt());
            fail("a staged pointer is not a committed head");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertEquals(first.id, store.current().id);
        assertFalse(Files.exists(root.resolve("canonical/HEAD.0")));
        assertEquals(1, countChildren(root.resolve("canonical/quarantine")));
    }

    @Test public void corruptNewestGenerationFallsBackToPreviousVerifiedHead() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 51));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 65536, (byte) 88);
        VirtualSdGenerationStore.Generation second = store.commit(session, goodReceipt());

        writeAt(second.imageFile, 65536, (byte) 89);
        assertEquals("a hash-invalid newest generation must fall back to its valid predecessor",
                first.id, store.current().id);
    }

    @Test public void branchStorageCannotAdvanceCanonicalStorage() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path baseline = createImage(root.resolve("base.img"), 61);
        VirtualSdGenerationStore canonical = store(root.resolve("canonical"), "canonical", ignored -> { });
        String branchId = UUID.randomUUID().toString();
        VirtualSdGenerationStore branch = store(root.resolve("branch-" + branchId), branchId, ignored -> { });
        VirtualSdGenerationStore.Generation canonicalFirst = canonical.initialize(baseline);
        VirtualSdGenerationStore.Generation branchFirst =
                branch.initialize(baseline, "canonical", canonicalFirst.id);
        assertEquals("canonical", branchFirst.parentBranchId);
        assertEquals(canonicalFirst.id, branchFirst.parentGenerationId);

        VirtualSdGenerationStore.WorkingSession session = branch.openSession();
        writeAt(session.imageFile, 131072, (byte) 92);
        branch.commit(session, goodReceipt());

        assertEquals(canonicalFirst.id, canonical.current().id);
        assertEquals(1, canonical.current().sequence);
        assertEquals(2, branch.current().sequence);
        assertEquals(branchId, branch.current().parentBranchId);
        assertEquals(branchFirst.id, branch.current().parentGenerationId);
    }

    @Test public void secondWriterCannotAcquireTheSameBranchLock() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore first = store(storeRoot, "canonical", ignored -> { });
        first.initialize(createImage(root.resolve("base.img"), 71));
        VirtualSdGenerationStore.WorkingSession active = first.openSession();
        VirtualSdGenerationStore second = store(storeRoot, "canonical", ignored -> { });
        try {
            second.openSession();
            fail("a second active writer must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("active writer"));
        }
        first.abort(active);
    }

    @Test public void symlinkedWorkingImageCannotBePublishedOrModifyItsTarget() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 81));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        Path external = root.resolve("external.img");
        byte[] sentinel = Files.readAllBytes(session.imageFile);
        Files.write(external, sentinel);
        Files.delete(session.imageFile);
        try { Files.createSymbolicLink(session.imageFile, external); }
        catch (UnsupportedOperationException | IOException unsupported) { return; }

        try {
            store.commit(session, goodReceipt());
            fail("symlinked image must not be committed");
        } catch (IOException expected) {
            assertEquals(first.id, store.current().id);
        }
        assertArrayEquals(sentinel, Files.readAllBytes(external));
    }

    @Test public void unexpectedIdxSidecarCannotBePublished() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 19));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        Path sidecar = session.imageFile.resolveSibling("dldi_sd_card.bin.idx");
        Files.write(sidecar, "unexpected sidecar\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            store.commit(session, goodReceipt());
            fail("unexpected DLDI sidecar must never become part of an immutable generation");
        } catch (IOException expected) {
            assertEquals(first.id, store.current().id);
        }
    }

    @Test public void insufficientOrUnavailableCapacityRejectsBootstrapWithoutPublishing() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path baseline = createImage(root.resolve("base.img"), 31);
        String sourceSha = sha(baseline);
        long[] physical = { IMAGE_BYTES - 1 };
        long[] allocatable = { IMAGE_BYTES };
        boolean[] unavailable = { false };
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(path -> {
            if (unavailable[0]) throw new SecurityException("capacity denied");
            return physical[0];
        }, path -> allocatable[0]);
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(storeRoot, IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage, ignored -> { }, capacity);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt == 1) { physical[0] = IMAGE_BYTES; allocatable[0] = 0; }
            if (attempt == 2) { unavailable[0] = true; allocatable[0] = IMAGE_BYTES; }
            try { store.initialize(baseline); fail("Unexpected bootstrap publication"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("capacity")); }
            assertNull(store.current());
            assertEquals(0, countChildren(storeRoot.resolve("generations")));
            assertEquals(0, countChildren(storeRoot.resolve("working")));
            assertEquals(sourceSha, sha(baseline));
        }
        unavailable[0] = false;
        assertEquals(1, store.initialize(baseline).sequence);
    }

    @Test public void workingCopyCapacityIsRecheckedAndRejectionPreservesHeadAndReleasesLock() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        long[] physical = { IMAGE_BYTES };
        long[] allocatable = { IMAGE_BYTES };
        boolean[] unavailable = { false };
        StorageCapacity capacity = StorageCapacity.withAllocationPreflight(
                path -> physical[0], path -> {
                    if (unavailable[0]) throw new IOException("allocation unavailable");
                    return allocatable[0];
                });
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(storeRoot, IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage, ignored -> { }, capacity);
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 41));
        byte[] head = Files.readAllBytes(storeRoot.resolve("HEAD.1"));
        for (int attempt = 0; attempt < 3; attempt++) {
            physical[0] = attempt == 0 ? IMAGE_BYTES - 1 : IMAGE_BYTES;
            allocatable[0] = attempt == 1 ? IMAGE_BYTES - 1 : IMAGE_BYTES;
            unavailable[0] = attempt == 2;
            try { store.openSession(); fail("Unexpected working image"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("capacity")); }
            assertEquals(first.id, store.current().id);
            assertArrayEquals(head, Files.readAllBytes(storeRoot.resolve("HEAD.1")));
            assertFalse(Files.exists(storeRoot.resolve("HEAD.0")));
            assertEquals(first.imageSha256, sha(first.imageFile));
            assertEquals(1, countChildren(storeRoot.resolve("generations")));
            assertEquals(0, countChildren(storeRoot.resolve("working")));
        }
        unavailable[0] = false;
        try (VirtualSdGenerationStore.WorkingSession session = store.openSession()) {
            assertEquals(first.imageSha256, sha(session.imageFile));
        }
        assertEquals(first.id, store.current().id);
    }

    private static VirtualSdGenerationStore store(Path root, String branchId,
                                                   VirtualSdGenerationStore.FaultInjector faults) {
        return new VirtualSdGenerationStore(root, IDENTITY, branchId, IMAGE_BYTES,
                VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage, faults);
    }

    private static VirtualSdGenerationStore.CoreCloseReceipt goodReceipt() {
        return new VirtualSdGenerationStore.CoreCloseReceipt(1, 4, 0, 0, IMAGE_BYTES,
                0, 0, 0, 0, false, false, true, true, true, true, true, true);
    }

    private static Path createImage(Path path, int seed) throws IOException {
        byte[] bytes = new byte[(int) IMAGE_BYTES];
        byte[] marker = "PLUMPFAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, bytes, 0, marker.length);
        for (int i = 512; i < bytes.length; i++) bytes[i] = (byte) (seed + i * 13);
        Files.write(path, bytes);
        return path;
    }

    private static void validateImage(Path image) throws IOException {
        if (Files.size(image) != IMAGE_BYTES) throw new IOException("unexpected image length");
        byte[] header = new byte[8];
        try (FileChannel channel = FileChannel.open(image, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(header);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        }
        if (!Arrays.equals(header, "PLUMPFAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("test image validator rejected the volume header");
    }

    private static void validateFatWithFsck(Path image) throws IOException {
        Process process = new ProcessBuilder("fsck.fat", "-vn", image.toString())
                .redirectErrorStream(true).start();
        byte[] output;
        try (var stream = process.getInputStream()) { output = stream.readAllBytes(); }
        try {
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("Read-only fsck.fat rejected image: " +
                    new String(output, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while validating FAT image", interrupted);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void writeAt(Path path, long offset, byte value) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.position(offset);
            channel.write(ByteBuffer.wrap(new byte[]{value}));
            channel.force(true);
        }
    }

    private static byte readAt(Path path, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(1);
            if (channel.read(buffer) != 1) throw new IOException("test image read failed");
            return buffer.array()[0];
        }
    }

    private static String sha(Path path) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int countChildren(Path directory) throws IOException {
        int count = 0;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path ignored : entries) count++;
        }
        return count;
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }
}
