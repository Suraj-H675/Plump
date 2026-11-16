package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.Assert.*;

public final class ActiveTimelineStoreTest {
    private static final String ROM = repeat('a');
    private static final CheckpointStore.Identity ID = new CheckpointStore.Identity("gba", ROM,
            "mgba-libretro", "d58ea8a86f30598fbf3958585de3d33028535072", "none",
            repeat('b'), "arm64-v8a", 1);
    private static final byte[] MACHINE = bytes(8192, 17);
    private static final byte[] NATIVE = bytes(256, 29);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void firstEmptyGameGetsCanonicalSelectorAndValidPointersReloadCold() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        HistoricalBranchStore branches = branches(root.resolve("branches"));
        ActiveTimelineStore store = timelines(root.resolve("timelines"));

        ActiveTimelineStore.Resolution first = store.resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, first.classification);
        assertNull(first.nativeSave);
        ActiveTimelineStore.Resolution reopened = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, reopened.classification);

        store.assertWritable(ID, null, canonical);
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore.SelectionResult saved = store.recordDurableSave(ID, null, canonical, NATIVE);
        assertTrue(saved.committed);
        assertEquals(1, saved.nativeGeneration);
        assertEquals(ActiveTimelineStore.sha256(NATIVE), saved.nativeSha256);
        ActiveTimelineStore.Resolution afterWrite = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, afterWrite.classification);
        assertArrayEquals(NATIVE, afterWrite.nativeSave);
        assertFalse(afterWrite.potentialExactStateAvailable());
    }

    @Test public void legacyV1CanonicalSaveWithoutBranchEvidenceGetsVerifiedSelector() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore store = timelines(root.resolve("timelines"));
        HistoricalBranchStore branches = branches(root.resolve("branches"));

        ActiveTimelineStore.Resolution legacy = store.resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, legacy.classification);
        assertArrayEquals(NATIVE, legacy.nativeSave);
        ActiveTimelineStore.Resolution selected = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, selected.classification);
        assertArrayEquals(NATIVE, selected.nativeSave);
    }

    @Test public void unrelatedGamesBranchDoesNotBlockLegacyCanonicalSelectorBootstrap() throws Exception {
        Path root = temporary.newFolder().toPath();
        CheckpointStore.Identity other = new CheckpointStore.Identity("gba", repeat('c'),
                ID.coreId, ID.coreRevision, ID.coreFrontendRevision, ID.coreBinarySha256, ID.abi, ID.libretroApi);
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        CheckpointStore checkpoints = new CheckpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(other,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore branches = branches(root.resolve("branches"));
        branches.create(checkpoints, checkpoint.id, other);

        ActiveTimelineStore.Resolution result = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, result.classification);
        assertArrayEquals(NATIVE, result.nativeSave);
    }

    @Test public void branchColdRecoveryIsExactOnlyWhileLatestPlsvMatchesSourcePair() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, bytes(256, 3));
        ActiveTimelineStore timeline = timelines(root.resolve("timelines"));
        HistoricalBranchStore branches = branches(root.resolve("branches"));
        CheckpointStore checkpoints = new CheckpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(ID,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, ID);

        ActiveTimelineStore.SelectionResult selected = timeline.selectBranch(ID, branches, branch);
        assertTrue(selected.committed);
        ActiveTimelineStore.Resolution exact = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.POTENTIAL_EXACT_STATE, exact.classification);
        assertEquals(branch.id, exact.branch.id);
        assertArrayEquals(NATIVE, exact.nativeSave);

        timeline.assertWritable(ID, branch.id, branch.saves);
        byte[] advanced = bytes(256, 77);
        branch.saves.write(ROM, advanced);
        timeline.recordDurableSave(ID, branch.id, branch.saves, advanced);
        ActiveTimelineStore.Resolution nativeOnly = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.BRANCH_NATIVE_ONLY, nativeOnly.classification);
        assertFalse(nativeOnly.potentialExactStateAvailable());
        assertArrayEquals(advanced, nativeOnly.nativeSave);
        assertArrayEquals("canonical native save remains independently preserved", bytes(256, 3),
                canonical.load(ROM).bytes);

        ActiveTimelineStore.SelectionResult reselected = timeline.selectBranch(ID, branches, branch);
        assertTrue(reselected.committed);
        ActiveTimelineStore.Resolution coldAfterReselection = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.BRANCH_NATIVE_ONLY,
                coldAfterReselection.classification);
        assertFalse("advanced native generation cannot reuse S_state", coldAfterReselection.potentialExactStateAvailable());
        assertArrayEquals(advanced, coldAfterReselection.nativeSave);
    }

    @Test public void preparedSelectionAndUnreadablePointerNeverDefaultToCanonical() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        HistoricalBranchStore branches = branches(root.resolve("branches"));
        CheckpointStore checkpoints = new CheckpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(ID,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore.Branch branch = branches.create(checkpoints, checkpoint.id, ID);
        Path timelinePath = root.resolve("timelines");
        ActiveTimelineStore store = new ActiveTimelineStore(timelinePath, ActiveTimelineStoreTest::sync,
                point -> { if (point == ActiveTimelineStore.FaultPoint.AFTER_PREPARE) throw new IOException("halt point"); });
        try { store.selectBranch(ID, branches, branch); fail("prepare failpoint must interrupt before commit"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("halt point")); }
        assertEquals(ActiveTimelineStore.Classification.AMBIGUOUS,
                timelines(timelinePath).resolveCold(true, ID, canonical, branches).classification);
        assertEquals(ActiveTimelineStore.Classification.ROM_UNAVAILABLE,
                timelines(timelinePath).resolveCold(false, ID, canonical, branches).classification);

        Path slot = timelinePath.resolve("gba-" + ROM + ".0.timeline");
        if (Files.exists(slot)) Files.write(slot, new byte[]{1, 2, 3});
        assertEquals(ActiveTimelineStore.Classification.CORRUPT_POINTER,
                timelines(timelinePath).resolveCold(true, ID, canonical, branches).classification);
        byte[] damagedPointer = Files.readAllBytes(slot);
        try {
            timelines(timelinePath).selectCanonical(ID, canonical);
            fail("Normal selection must not overwrite corrupt-present metadata");
        } catch (IOException expected) { }
        assertArrayEquals("corrupt selector remains available as evidence", damagedPointer, Files.readAllBytes(slot));
        assertTrue(timelines(timelinePath).recoverCanonical(ID, canonical).committed);
        ActiveTimelineStore.Resolution recovered = timelines(timelinePath)
                .resolveCold(true, ID, canonical, branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, recovered.classification);
        assertArrayEquals(damagedPointer, readRecoveryEvidence(timelinePath, damagedPointer));
    }

    @Test public void wrongIdentityAndNativeGenerationMismatchRequireExplicitRecovery() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore store = timelines(root.resolve("timelines"));
        store.selectCanonical(ID, canonical);
        byte[] newer = bytes(256, 98);
        canonical.write(ROM, newer); // Simulates a crash after durable PLSV publication but before selector update.
        assertEquals(ActiveTimelineStore.Classification.INVALID_TARGET,
                timelines(root.resolve("timelines")).resolveCold(true, ID, canonical,
                        branches(root.resolve("branches"))).classification);

        CheckpointStore.Identity wrongAbi = new CheckpointStore.Identity("gba", ROM, ID.coreId,
                ID.coreRevision, ID.coreFrontendRevision, ID.coreBinarySha256, "x86_64", 1);
        assertEquals(ActiveTimelineStore.Classification.CORRUPT_POINTER,
                timelines(root.resolve("timelines")).resolveCold(true, wrongAbi, canonical,
                        branches(root.resolve("branches"))).classification);
    }

    @Test public void branchEvidenceForSameRomBlocksLegacyCanonicalInference() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        HistoricalBranchStore branches = branches(root.resolve("branches"));
        CheckpointStore checkpoints = new CheckpointStore(root.resolve("checkpoints"));
        CheckpointStore.Record checkpoint = checkpoints.create(ID,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        branches.create(checkpoints, checkpoint.id, ID);

        ActiveTimelineStore.Resolution result = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches);
        assertEquals("a same-ROM orphan branch requires an explicit history choice",
                ActiveTimelineStore.Classification.MISSING_POINTER, result.classification);
        assertFalse(Files.exists(root.resolve("timelines/gba-" + ROM + ".0.timeline")));
        assertArrayEquals(NATIVE, canonical.load(ROM).bytes);
    }

    @Test public void selectorAndNativeJournalSymlinkRootsFailClosed() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path realTimelineRoot = root.resolve("real-timeline");
        Files.createDirectory(realTimelineRoot);
        Path selectorRoot = root.resolve("timeline-link");
        try { Files.createSymbolicLink(selectorRoot, realTimelineRoot); }
        catch (UnsupportedOperationException | IOException unavailable) { return; }
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore linkedTimeline = timelines(selectorRoot);
        assertEquals(ActiveTimelineStore.Classification.CORRUPT_POINTER,
                linkedTimeline.resolveCold(true, ID, canonical, branches(root.resolve("branches"))).classification);
        try {
            linkedTimeline.selectCanonical(ID, canonical);
            fail("selector publication must refuse a symlinked metadata root");
        } catch (IOException expected) { }
        assertEquals(0, Files.list(realTimelineRoot).count());

        Path realSaveRoot = root.resolve("real-save");
        Files.createDirectory(realSaveRoot);
        Path saveLink = root.resolve("save-link");
        Files.createSymbolicLink(saveLink, realSaveRoot);
        GameSaveStore linkedSave = new GameSaveStore(saveLink.toFile(), ActiveTimelineStoreTest::syncSave);
        assertTrue("save root symlink must be rejected before selection", linkedSave.hasUnsafePaths(ROM));
        assertEquals(ActiveTimelineStore.Classification.MISSING_POINTER,
                timelines(root.resolve("other-timelines"))
                        .resolveCold(true, ID, linkedSave, branches(root.resolve("other-branches"))).classification);
        assertEquals(0, Files.list(realSaveRoot).count());
    }

    @Test public void interruptedNativeJournalPublicationCannotAdvanceOrMisrouteSelector() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path canonicalRoot = root.resolve("canonical");
        GameSaveStore canonical = saves(canonicalRoot);
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore timeline = timelines(root.resolve("timelines"));
        timeline.selectCanonical(ID, canonical);
        byte[] advanced = bytes(256, 111);

        GameSaveStore beforePublish = new GameSaveStore(canonicalRoot.toFile(), ActiveTimelineStoreTest::syncSave,
                point -> { if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE) throw new IOException("partial journal"); });
        try { beforePublish.write(ROM, advanced); fail("partial temporary record must fail"); }
        catch (GameSaveStore.SaveFailure expected) { }
        ActiveTimelineStore.Resolution intact = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches(root.resolve("branches")));
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, intact.classification);
        assertEquals(1, intact.nativeGeneration);
        assertArrayEquals(NATIVE, intact.nativeSave);

        GameSaveStore afterPublish = new GameSaveStore(canonicalRoot.toFile(), ActiveTimelineStoreTest::syncSave,
                point -> { if (point == GameSaveStore.WritePoint.AFTER_RECORD_MOVE) throw new IOException("published before pointer"); });
        try { afterPublish.write(ROM, advanced); fail("post-rename failure must surface"); }
        catch (GameSaveStore.SaveFailure expected) { }
        ActiveTimelineStore.Resolution uncertain = timelines(root.resolve("timelines"))
                .resolveCold(true, ID, canonical, branches(root.resolve("branches")));
        assertEquals("new PLSV bytes without selector publication require explicit recovery",
                ActiveTimelineStore.Classification.INVALID_TARGET, uncertain.classification);
        try {
            timeline.assertWritable(ID, null, canonical);
            fail("a stale selector must block further canonical writes");
        } catch (IOException expected) { }
        assertArrayEquals("latest journal bytes remain available for explicit recovery", advanced,
                afterPublish.load(ROM).bytes);
    }

    @Test public void symlinkedTimelineAndPlsvAreRejectedWithoutFollowingThem() throws Exception {
        Path root = temporary.newFolder().toPath();
        GameSaveStore canonical = saves(root.resolve("canonical"));
        canonical.write(ROM, NATIVE);
        ActiveTimelineStore timeline = timelines(root.resolve("timelines"));
        timeline.selectCanonical(ID, canonical);
        Path external = root.resolve("external");
        byte[] sentinel = bytes(256, 4);
        Files.write(external, sentinel);
        Path activeSlot = root.resolve("timelines/gba-" + ROM + ".0.timeline");
        Files.deleteIfExists(activeSlot);
        try { Files.createSymbolicLink(activeSlot, external); }
        catch (UnsupportedOperationException | IOException unavailable) { return; }
        assertEquals(ActiveTimelineStore.Classification.CORRUPT_POINTER,
                timelines(root.resolve("timelines")).resolveCold(true, ID, canonical,
                        branches(root.resolve("branches"))).classification);
        assertArrayEquals(sentinel, Files.readAllBytes(external));
    }

    private static GameSaveStore saves(Path path) throws IOException {
        return new GameSaveStore(path.toFile(), ActiveTimelineStoreTest::syncSave);
    }
    private static ActiveTimelineStore timelines(Path path) {
        return new ActiveTimelineStore(path, ActiveTimelineStoreTest::sync);
    }
    private static HistoricalBranchStore branches(Path path) {
        return new HistoricalBranchStore(path, ActiveTimelineStoreTest::sync,
                ignored -> Long.MAX_VALUE, ActiveTimelineStoreTest::syncSave, ignored -> { });
    }
    private static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private static void syncSave(java.io.File directory) throws IOException { sync(directory.toPath()); }
    private static byte[] readRecoveryEvidence(Path root, byte[] expected) throws IOException {
        Path evidenceRoot = root.resolve("recovery-evidence");
        try (java.nio.file.DirectoryStream<Path> attempts = Files.newDirectoryStream(evidenceRoot)) {
            for (Path attempt : attempts) {
                try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(attempt, "*.timeline.evidence")) {
                    for (Path file : files) {
                        byte[] candidate = Files.readAllBytes(file);
                        if (Arrays.equals(candidate, expected)) return candidate;
                    }
                }
            }
        }
        throw new IOException("Selector recovery evidence was not preserved");
    }
    private static byte[] bytes(int length, int seed) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i * 31);
        return result;
    }
    private static String repeat(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
