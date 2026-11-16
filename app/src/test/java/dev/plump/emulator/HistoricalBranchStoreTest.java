package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public final class HistoricalBranchStoreTest {
    private static final CheckpointStore.Identity IDENTITY = new CheckpointStore.Identity("gba",
            repeat('a', 64), "mgba-libretro", "d58ea8a86f30598fbf3958585de3d33028535072", "none",
            repeat('b', 64), "arm64-v8a", 1);
    private static final byte[] MACHINE = bytes(4096, 17);
    private static final byte[] NATIVE = bytes(256, 29);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void branchOwnsInitialJournalAndLaterWritesWithoutTouchingCanonical() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path canonicalPath = root.resolve("native-saves");
        GameSaveStore canonical = new GameSaveStore(canonicalPath.toFile(), ignored -> { });
        canonical.write(IDENTITY.romSha256, bytes(256, 4));
        Map<String, byte[]> before = snapshot(canonicalPath);

        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branchStore(root.resolve("branches"), ignored -> { }, path -> Long.MAX_VALUE);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, IDENTITY);

        assertNotEquals("canonical", branch.id);
        assertEquals(checkpoint.id, branch.checkpointId);
        assertArrayEquals(MACHINE, branch.machineState());
        assertArrayEquals(NATIVE, branch.nativeSave());
        byte[] exposed = branch.machineState();
        exposed[0] ^= 1;
        assertArrayEquals("branch pair access must return a defensive copy", MACHINE, branch.machineState());
        assertArrayEquals(NATIVE, branch.saves.load(IDENTITY.romSha256).bytes);
        assertTrue(Files.isDirectory(branch.coreDataDirectory));
        assertTrue(Files.isDirectory(branch.systemDirectory));
        assertArrayEquals(NATIVE, branches.open(branch.id, IDENTITY).nativeSave());

        branch.saves.write(IDENTITY.romSha256, bytes(256, 51));
        assertArrayEquals("later journal generations do not rewrite the immutable pair", MACHINE,
                branches.open(branch.id, IDENTITY).machineState());
        assertArrayEquals("cold-open must retain later writable branch progress", bytes(256, 51),
                branches.open(branch.id, IDENTITY).saves.load(IDENTITY.romSha256).bytes);
        assertArrayEquals(bytes(256, 4), canonical.load(IDENTITY.romSha256).bytes);
        assertByteMapsEqual(before, snapshot(canonicalPath));
    }

    @Test public void reopeningRejectsJournalWithUnexpectedNativeGeometry() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branchStore(root.resolve("branches"), ignored -> { }, path -> Long.MAX_VALUE);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, IDENTITY);
        branch.saves.write(IDENTITY.romSha256, bytes(64, 81));
        assertArrayEquals(bytes(64, 81), branch.saves.load(IDENTITY.romSha256).bytes);
        assertBranchOpenFails(branches, branch.id, IDENTITY);
        assertArrayEquals("immutable source native snapshot must survive a bad journal", NATIVE,
                Files.readAllBytes(branch.nativeFile));
    }

    @Test public void reopeningRejectsSymlinkedJournalGenerationWithoutTouchingTarget() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branchStore(root.resolve("branches"), ignored -> { }, path -> Long.MAX_VALUE);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, IDENTITY);
        Path external = root.resolve("untouchable-external");
        byte[] sentinel = bytes(64, 74);
        Files.write(external, sentinel);
        Path slot = branch.journalDirectory.resolve(IDENTITY.romSha256 + ".1.sav");
        try { Files.createSymbolicLink(slot, external); }
        catch (UnsupportedOperationException | IOException unsupported) { return; }
        assertBranchOpenFails(branches, branch.id, IDENTITY);
        assertArrayEquals("external sentinel was modified", sentinel, Files.readAllBytes(external));
    }

    @Test public void wrongIdentityCorruptionAndTraversalFailClosed() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branchStore(root.resolve("branches"), ignored -> { }, path -> Long.MAX_VALUE);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, IDENTITY);
        CheckpointStore.Identity wrongRom = new CheckpointStore.Identity("gba", repeat('c', 64),
                IDENTITY.coreId, IDENTITY.coreRevision, IDENTITY.coreFrontendRevision,
                IDENTITY.coreBinarySha256, IDENTITY.abi, IDENTITY.libretroApi);
        assertBranchOpenFails(branches, branch.id, wrongRom);
        CheckpointStore.Identity wrongCore = new CheckpointStore.Identity("gba", IDENTITY.romSha256,
                "other-core", IDENTITY.coreRevision, IDENTITY.coreFrontendRevision,
                IDENTITY.coreBinarySha256, IDENTITY.abi, IDENTITY.libretroApi);
        assertBranchOpenFails(branches, branch.id, wrongCore);
        assertBranchOpenFails(branches, "../" + branch.id, IDENTITY);

        Files.write(branch.machineFile, bytes(MACHINE.length, 91));
        assertBranchOpenFails(branches, branch.id, IDENTITY);
    }

    @Test public void symlinkedBranchComponentsAreRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branchStore(root.resolve("branches"), ignored -> { }, path -> Long.MAX_VALUE);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, IDENTITY);
        Path journal = branch.directory.resolve("journal");
        Path replacement = root.resolve("journal-replacement");
        Files.move(journal, replacement);
        try {
            Files.createSymbolicLink(journal, replacement);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Files.move(replacement, journal);
            return;
        }
        assertBranchOpenFails(branches, branch.id, IDENTITY);
    }

    @Test public void lowSpaceAndPublicationFailuresNeverExposeAnUnverifiedBranch() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore checkpoints = checkpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(IDENTITY,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Path lowSpaceRoot = root.resolve("low-space");
        HistoricalBranchStore lowSpace = branchStore(lowSpaceRoot, ignored -> { }, path -> 0);
        try {
            lowSpace.create(checkpoints, checkpoint.id, IDENTITY);
            fail("Expected storage exhaustion");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("space")); }
        assertNoPublishedBranches(lowSpaceRoot);

        for (HistoricalBranchStore.FaultPoint point : HistoricalBranchStore.FaultPoint.values()) {
            if (point == HistoricalBranchStore.FaultPoint.BEFORE_QUARANTINE) continue;
            Path faultRoot = root.resolve("fault-" + point.name().toLowerCase());
            HistoricalBranchStore failing = branchStore(faultRoot, candidate -> {
                if (candidate == point) throw new IOException("injected " + point);
            }, path -> Long.MAX_VALUE);
            try {
                failing.create(checkpoints, checkpoint.id, IDENTITY);
                fail("Expected publication interruption at " + point);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("injected") || point == HistoricalBranchStore.FaultPoint.AFTER_READBACK);
            }
            assertNoPublishedBranches(faultRoot);
            boolean shouldRetainStage = point == HistoricalBranchStore.FaultPoint.AFTER_MACHINE ||
                    point == HistoricalBranchStore.FaultPoint.AFTER_NATIVE ||
                    point == HistoricalBranchStore.FaultPoint.AFTER_JOURNAL ||
                    point == HistoricalBranchStore.FaultPoint.BEFORE_MANIFEST ||
                    point == HistoricalBranchStore.FaultPoint.AFTER_MANIFEST ||
                    point == HistoricalBranchStore.FaultPoint.AFTER_STAGE_FSYNC ||
                    point == HistoricalBranchStore.FaultPoint.BEFORE_PUBLISH;
            assertEquals("Staged evidence retention at " + point, shouldRetainStage,
                    hasEntries(faultRoot, ".pending-*"));
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(faultRoot, "quarantine-*")) {
                for (Path quarantined : entries) assertTrue(Files.isDirectory(quarantined));
            } catch (IOException missingRoot) {
                assertFalse(Files.exists(faultRoot));
            }
        }
    }

    private static CheckpointStore checkpointStore(Path root) {
        return new CheckpointStore(root, ignored -> { }, path -> Long.MAX_VALUE, ignored -> { });
    }
    private static HistoricalBranchStore branchStore(Path root, HistoricalBranchStore.FaultInjector faults,
                                                      HistoricalBranchStore.UsableSpace space) {
        return new HistoricalBranchStore(root, ignored -> { }, space, ignored -> { }, faults);
    }
    private static void assertBranchOpenFails(HistoricalBranchStore store, String id,
                                               CheckpointStore.Identity identity) throws Exception {
        try { store.open(id, identity); fail("Expected invalid branch rejection"); }
        catch (IOException expected) { }
    }
    private static void assertNoPublishedBranches(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(root, "branch-*")) {
            assertFalse("Unverified branch was published", entries.iterator().hasNext());
        }
    }
    private static boolean hasEntries(Path root, String glob) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(root, glob)) {
            return entries.iterator().hasNext();
        }
    }
    private static Map<String, byte[]> snapshot(Path directory) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) result.put(file.getFileName().toString(), Files.readAllBytes(file));
        }
        return result;
    }
    private static void assertByteMapsEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) assertArrayEquals("canonical file changed: " + name,
                expected.get(name), actual.get(name));
    }
    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (i * 31 + seed);
        return result;
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
