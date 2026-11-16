package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class CheckpointStoreTest {
    private static final CheckpointStore.Identity GBA = identity("a", "mgba-libretro",
            "d58ea8a86f30598fbf3958585de3d33028535072",
            "05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429", "arm64-v8a", 1);
    private static final byte[] MACHINE = bytes(8192, 7);
    private static final byte[] NATIVE = bytes(256, 29);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void publishesImmutableManifestAndIndependentlyVerifiedBlobs() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);

        assertEquals(1, record.identity.libretroApi);
        assertEquals(MACHINE.length, record.machineLength);
        assertEquals(NATIVE.length, record.nativeLength);
        assertEquals(record.id, store.verify(record.id, GBA).id);
        String manifest = new String(Files.readAllBytes(root.resolve("slot-" + record.id).resolve("manifest.pcm")),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("status=committed\n"));
        assertTrue(manifest.contains("core_git_revision=" + GBA.coreRevision + "\n"));
        assertTrue(manifest.contains("core_frontend_git_revision=none\n"));
        assertTrue(manifest.contains("core_binary_sha256=" + GBA.coreBinarySha256 + "\n"));
        assertTrue(manifest.contains("machine_sha256=" + record.machineSha256 + "\n"));
        assertTrue(manifest.contains("native_sha256=" + record.nativeSha256 + "\n"));
        assertTrue(manifest.contains("branch_id=canonical\n"));
        assertTrue(manifest.contains("session_run_token=0\n"));
        assertTrue(manifest.matches("(?s).*manifest_sha256=[0-9a-f]{64}\n"));
        assertEquals("canonical", record.branchId);
        assertEquals(0L, record.sessionRunToken);
        assertFalse(manifest.contains(root.toString()));
        assertEquals(1, store.listValid(GBA).size());
    }

    @Test public void unsupportedCapabilitiesAndInvalidSizesFailBeforePublication() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        assertCreateFails(store, CheckpointStore.Capture.unsupported(1, "missing serializer"),
                CheckpointStore.Kind.MANUAL, "missing serializer");
        assertCreateFails(store, CheckpointStore.Capture.supported(2, MACHINE, NATIVE),
                CheckpointStore.Kind.MANUAL, "API");
        assertCreateFails(store, capture(new byte[0], NATIVE), CheckpointStore.Kind.MANUAL, "length");
        assertCreateFails(store, capture(new byte[CheckpointStore.MAX_MACHINE_BYTES + 1], NATIVE),
                CheckpointStore.Kind.MANUAL, "length");
        assertCreateFails(store, capture(MACHINE, new byte[CheckpointStore.MAX_NATIVE_BYTES + 1]),
                CheckpointStore.Kind.MANUAL, "length");
        assertTrue(store.listValid(GBA).isEmpty());
        assertNoPending(root);
    }

    @Test public void failedPublicationStagesLeaveOnlyCompleteOldOrNewRecords() throws Exception {
        List<Failure> failures = new ArrayList<>();
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_TEMP_WRITE, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.DURING_FILE_WRITE, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.DURING_FILE_WRITE, 2, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.DURING_FILE_WRITE, 3, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_TEMP_WRITE, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_FILE_FSYNC, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_FILE_FSYNC, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_MANIFEST_WRITE, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_MANIFEST_WRITE, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_DIRECTORY_FSYNC, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_DIRECTORY_FSYNC, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_RENAME, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_RENAME, 1, true));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_DIRECTORY_FSYNC, 2, true));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_DIRECTORY_FSYNC, 2, true));
        failures.add(new Failure(CheckpointStore.FaultPoint.BEFORE_MANIFEST_PUBLICATION, 1, false));
        failures.add(new Failure(CheckpointStore.FaultPoint.AFTER_MANIFEST_PUBLICATION, 1, true));

        for (Failure failure : failures) {
            Path root = temporary.newFolder().toPath();
            CheckpointStore baseline = store(root, point -> { }, path -> Long.MAX_VALUE);
            CheckpointStore.Record old = baseline.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
            AtomicInteger seen = new AtomicInteger();
            CheckpointStore failing = store(root, point -> {
                if (point == failure.point && seen.incrementAndGet() == failure.occurrence)
                    throw new IOException("injected " + point);
            }, path -> Long.MAX_VALUE);
            try {
                failing.create(GBA, capture(bytes(8192, 31), NATIVE), CheckpointStore.Kind.MANUAL);
                fail("Expected failure at " + failure.point + " occurrence " + failure.occurrence);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("injected"));
            }
            assertEquals(old.id, baseline.verify(old.id, GBA).id);
            List<CheckpointStore.Record> valid = baseline.listValid(GBA);
            assertEquals(failure.newSlotVisible ? 2 : 1, valid.size());
            for (CheckpointStore.Record record : valid) assertEquals(record.id, baseline.verify(record.id, GBA).id);
            assertNoPending(root);
        }
    }

    @Test public void wrongGameCoreRevisionBinaryAbiAndApiAreRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        assertVerifyFails(store, record.id, identity("b", GBA.coreId, GBA.coreRevision, GBA.coreBinarySha256, "arm64-v8a", 1));
        assertVerifyFails(store, record.id, identity("a", "other-core", GBA.coreRevision, GBA.coreBinarySha256, "arm64-v8a", 1));
        assertVerifyFails(store, record.id, identity("a", GBA.coreId, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", GBA.coreBinarySha256, "arm64-v8a", 1));
        assertVerifyFails(store, record.id, identity("a", GBA.coreId, GBA.coreRevision, "b".repeat(64), "arm64-v8a", 1));
        assertVerifyFails(store, record.id, identity("a", GBA.coreId, GBA.coreRevision, GBA.coreBinarySha256, "x86_64", 1));
        assertVerifyFails(store, record.id, new CheckpointStore.Identity("gba", GBA.romSha256,
                GBA.coreId, GBA.coreRevision, "cccccccccccccccccccccccccccccccccccccccc",
                GBA.coreBinarySha256, GBA.abi, 1));
        assertVerifyFails(store, record.id,
                identity("a", GBA.coreId, GBA.coreRevision, GBA.coreBinarySha256, "arm64-v8a", 2));
    }

    @Test public void truncatedTamperedAndWrongVersionRecordsAreRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path slot = root.resolve("slot-" + record.id);
        Path machine = slot.resolve("machine.bin");
        byte[] altered = Files.readAllBytes(machine);
        altered[0] ^= 1;
        Files.write(machine, altered);
        assertVerifyFails(store, record.id, GBA);
        assertTrue(store.listValid(GBA).isEmpty());

        Path secondRoot = temporary.newFolder().toPath();
        CheckpointStore second = store(secondRoot, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record secondRecord = second.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path secondSlot = secondRoot.resolve("slot-" + secondRecord.id);
        Files.write(secondSlot.resolve("native.bin"), new byte[]{1, 2, 3});
        assertVerifyFails(second, secondRecord.id, GBA);

        Path thirdRoot = temporary.newFolder().toPath();
        CheckpointStore third = store(thirdRoot, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record thirdRecord = third.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path manifest = thirdRoot.resolve("slot-" + thirdRecord.id).resolve("manifest.pcm");
        String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8).replace("format=1\n", "format=2\n");
        Files.write(manifest, text.getBytes(StandardCharsets.UTF_8));
        assertVerifyFails(third, thirdRecord.id, GBA);
    }

    @Test public void manifestChecksumRejectsReclassifiedManualGenerationAndSessionMetadata() throws Exception {
        for (String field : new String[]{"kind=manual", "generation=", "session_run_token=42", "branch_id=canonical", "manifest_sha256="}) {
            Path root = temporary.newFolder().toPath();
            CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
            CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL, 42L);
            assertEquals(42L, record.sessionRunToken);
            Path manifest = root.resolve("slot-" + record.id).resolve("manifest.pcm");
            String original = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
            String changed;
            if (field.equals("kind=manual")) changed = original.replace("kind=manual\n", "kind=auto\n");
            else if (field.equals("generation=")) changed = original.replace("generation=" + record.generation + "\n", "generation=" + (record.generation + 1) + "\n");
            else if (field.equals("session_run_token=42")) changed = original.replace("session_run_token=42\n", "session_run_token=43\n");
            else if (field.equals("branch_id=canonical")) changed = original.replace("branch_id=canonical\n", "branch_id=alternate\n");
            else changed = original.replace("manifest_sha256=", "manifest_sha256=0");
            assertFalse("Mutation must change bytes", original.equals(changed));
            Files.write(manifest, changed.getBytes(StandardCharsets.UTF_8));
            assertVerifyFails(store, record.id, GBA);
            assertTrue(store.listValid(GBA).isEmpty());
            for (int index = 0; index < 6; index++)
                store.create(GBA, capture(bytes(256, index + 1), NATIVE), CheckpointStore.Kind.AUTO);
            assertTrue("Invalid manual slot may not be reclaimed by auto retention", Files.exists(manifest));
        }
    }

    @Test public void malformedOrMissingManifestChecksumIsRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path manifest = root.resolve("slot-" + record.id).resolve("manifest.pcm");
        String original = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        Files.write(manifest, original.replaceAll("manifest_sha256=[0-9a-f]{64}\n", "").getBytes(StandardCharsets.UTF_8));
        assertVerifyFails(store, record.id, GBA);
    }

    @Test public void slotSymlinksCannotReadOrDeleteFilesOutsideTheCheckpointRoot() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record record = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path external = temporary.newFile().toPath();
        byte[] externalBytes = bytes(512, 73);
        Files.write(external, externalBytes);
        Path machine = root.resolve("slot-" + record.id).resolve("machine.bin");
        Files.delete(machine);
        Files.createSymbolicLink(machine, external);

        assertVerifyFails(store, record.id, GBA);
        assertArrayEquals(externalBytes, Files.readAllBytes(external));
    }

    @Test public void interruptedPendingDirectoryIsNeverEnumeratedAsCommitted() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record old = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path interrupted = root.resolve(".pending-interrupted");
        Files.createDirectory(interrupted);
        Files.write(interrupted.resolve("machine.bin"), new byte[]{1, 2, 3});
        Files.write(interrupted.resolve("manifest.pcm"), "status=committed\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, store.listValid(GBA).size());
        assertEquals(old.id, store.verify(old.id, GBA).id);
    }

    @Test public void autoRetentionCannotDeleteUnrecognizedDataInAValidCheckpointDirectory() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        List<CheckpointStore.Record> autos = new ArrayList<>();
        for (int i = 0; i < 4; i++) autos.add(store.create(GBA,
                capture(bytes(256, i + 1), NATIVE), CheckpointStore.Kind.AUTO));
        Path slot = root.resolve("slot-" + autos.get(1).id);
        Path unknown = slot.resolve("unrelated-data.bin");
        byte[] sentinel = bytes(99, 77);
        Files.write(unknown, sentinel);
        store.create(GBA, capture(bytes(256, 5), NATIVE), CheckpointStore.Kind.AUTO);
        assertArrayEquals(sentinel, Files.readAllBytes(unknown));
        assertEquals(autos.get(1).id, store.verify(autos.get(1).id, GBA).id);
        assertEquals(5, store.listValid(GBA).size());
    }

    @Test public void automaticRetentionSyncFailureDoesNotUndoVerifiedPublication() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicInteger syncs = new AtomicInteger();
        CheckpointStore store = new CheckpointStore(root, directory -> {
            if (directory.equals(root) && syncs.incrementAndGet() == 6)
                throw new IOException("injected retention sync failure");
            CheckpointStoreTest.sync(directory);
        }, path -> Long.MAX_VALUE, point -> { });

        CheckpointStore.Record record = null;
        for (int index = 0; index < 5; index++)
            record = store.create(GBA, capture(bytes(256, index + 1), NATIVE), CheckpointStore.Kind.AUTO);

        assertNotNull(record);
        assertEquals(record.id, store.verify(record.id, GBA).id);
        assertEquals(4, store.listValid(GBA).size());
    }

    @Test public void storageExhaustionPreservesExistingManualCheckpoint() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        CheckpointStore.Record old = store.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        CheckpointStore full = store(root, point -> { }, path -> 0);
        try {
            full.create(GBA, capture(MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
            fail("Storage exhaustion must fail closed");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Insufficient")); }
        assertEquals(old.id, full.verify(old.id, GBA).id);
        assertEquals(1, full.listValid(GBA).size());
        assertNoPending(root);
    }

    @Test public void unlimitedManualRecordsAndThreeRecentPlusOneAnchorAutosAreRetainedPerGame() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore store = store(root, point -> { }, path -> Long.MAX_VALUE);
        List<CheckpointStore.Record> autos = new ArrayList<>();
        for (int i = 0; i < 8; i++) autos.add(store.create(GBA,
                capture(bytes(256, i + 1), NATIVE), CheckpointStore.Kind.AUTO));
        List<CheckpointStore.Record> manual = new ArrayList<>();
        for (int i = 0; i < 6; i++) manual.add(store.create(GBA,
                capture(bytes(256, i + 20), NATIVE), CheckpointStore.Kind.MANUAL));
        List<CheckpointStore.Record> retained = store.listValid(GBA);
        List<CheckpointStore.Record> retainedAutos = new ArrayList<>();
        List<CheckpointStore.Record> retainedManual = new ArrayList<>();
        for (CheckpointStore.Record record : retained) {
            if (record.kind == CheckpointStore.Kind.AUTO) retainedAutos.add(record);
            else retainedManual.add(record);
        }
        assertEquals(4, retainedAutos.size());
        assertEquals(6, retainedManual.size());
        assertEquals(autos.get(0).id, retainedAutos.get(0).id);
        assertEquals(autos.get(5).id, retainedAutos.get(1).id);
        assertEquals(autos.get(6).id, retainedAutos.get(2).id);
        assertEquals(autos.get(7).id, retainedAutos.get(3).id);
        for (CheckpointStore.Record record : manual) assertEquals(record.id, store.verify(record.id, GBA).id);

        CheckpointStore.Identity otherGame = identity("b", GBA.coreId, GBA.coreRevision,
                GBA.coreBinarySha256, GBA.abi, GBA.libretroApi);
        CheckpointStore.Record other = store.create(otherGame, capture(MACHINE, NATIVE), CheckpointStore.Kind.AUTO);
        assertEquals(1, store.listValid(otherGame).size());
        assertEquals(4, store.listValid(GBA).stream().filter(r -> r.kind == CheckpointStore.Kind.AUTO).count());
        assertEquals(other.id, store.verify(other.id, otherGame).id);
    }

    private void assertCreateFails(CheckpointStore store, CheckpointStore.Capture capture,
                                   CheckpointStore.Kind kind, String message) throws Exception {
        try { store.create(GBA, capture, kind); fail("Expected checkpoint failure"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains(message)); }
    }

    private void assertVerifyFails(CheckpointStore store, String id, CheckpointStore.Identity identity) throws Exception {
        try { store.verify(id, identity); fail("Expected checkpoint verification failure"); }
        catch (IOException expected) { }
    }

    private static CheckpointStore store(Path root, CheckpointStore.FaultInjector faults,
                                         CheckpointStore.UsableSpace space) {
        return new CheckpointStore(root, CheckpointStoreTest::sync, space, faults);
    }

    static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static CheckpointStore.Capture capture(byte[] machine, byte[] nativeSave) {
        return CheckpointStore.Capture.supported(1, machine, nativeSave);
    }

    private static byte[] bytes(int size, int start) {
        byte[] result = new byte[size];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (start + i * 17);
        return result;
    }

    private static CheckpointStore.Identity identity(String rom, String core, String revision,
                                                       String binary, String abi, int api) {
        String romHash = rom.length() == 64 ? rom : String.valueOf(rom.charAt(0)).repeat(64);
        if (binary.length() != 64) binary = binary.repeat(64).substring(0, 64);
        return new CheckpointStore.Identity("gba", romHash, core, revision, "none", binary, abi, api);
    }

    private static void assertNoPending(Path root) throws IOException {
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(root, ".pending-*")) {
            assertFalse(children.iterator().hasNext());
        }
    }

    private static final class Failure {
        final CheckpointStore.FaultPoint point;
        final int occurrence;
        final boolean newSlotVisible;
        Failure(CheckpointStore.FaultPoint point, int occurrence, boolean newSlotVisible) {
            this.point = point;
            this.occurrence = occurrence;
            this.newSlotVisible = newSlotVisible;
        }
    }
}
