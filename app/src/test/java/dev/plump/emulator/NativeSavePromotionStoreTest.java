package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class NativeSavePromotionStoreTest {
    private static final String ROM = repeat('a');
    private static final CheckpointStore.Identity ID = new CheckpointStore.Identity("gba", ROM,
            "mgba-libretro", "d58ea8a86f30598fbf3958585de3d33028535072", "none",
            repeat('b'), "x86_64", 1);
    private static final byte[] MACHINE = bytes(4096, 23);
    private static final byte[] CANONICAL = bytes(32 * 1024, 1);
    private static final byte[] BRANCH = bytes(32 * 1024, 2);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void explicitApprovalPromotesVerifiedBranchAndExplicitRollbackRestoresOriginal() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        assertEquals(f.branch.id, proposal.branchId);
        assertEquals(2, proposal.branchGeneration);
        assertArrayEquals("proposal cannot change canonical PLSV", CANONICAL,
                f.canonical.load(ROM).bytes);
        try {
            f.owner.approveNativeSavePromotion(f.token, proposal, "yes");
            fail("confirmation must match the displayed transaction code");
        } catch (IOException expected) { }
        assertEquals(NativeSavePromotionStore.Classification.NONE,
                f.timeline.promotions().inspect(ID).classification);

        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        NativeSavePromotionStore.Result promoted = f.owner.promoteNativeSaveBranch(f.token, approval);
        assertEquals(NativeSavePromotionStore.Classification.COMMITTED, promoted.classification);
        assertEquals(2, promoted.canonicalGeneration);
        assertArrayEquals(BRANCH, f.canonical.load(ROM).bytes);
        assertArrayEquals("branch journal remains independently available", BRANCH,
                f.branch.saves.load(ROM).bytes);
        assertFalse("the loaded branch core must not resume after canonical selector changes",
                f.owner.resume(f.token));
        assertFalse("retirement must not flush the old branch core into either timeline",
                f.owner.closeHost(f.token));
        assertEquals(ActiveTimelineStore.Classification.CANONICAL,
                f.timeline.resolveCold(true, ID, f.canonical, f.branches).classification);

        assertTrue(Files.exists(f.root.resolve("timelines/native-save-promotions/gba-" + ROM)
                .resolve(approval.transactionId).resolve("terminal.record")));
        assertEquals(NativeSavePromotionStore.Classification.COMMITTED,
                f.timeline.promotions().inspect(ID).classification);
        assertNotNull(f.timeline.promotions().retryCommitted(approval, ID, f.canonical, f.branches));
        assertEquals("exact retry is idempotent", promoted.canonicalGeneration,
                f.owner.promoteNativeSaveBranch(f.token, approval).canonicalGeneration);
        NativeSavePromotionStore.Result rolledBack = f.timeline.promotions()
                .rollback(ID, f.canonical, f.branches, approval.transactionId);
        assertEquals(NativeSavePromotionStore.Classification.ROLLED_BACK, rolledBack.classification);
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
        assertArrayEquals(BRANCH, f.branch.saves.load(ROM).bytes);
        ActiveTimelineStore.Resolution cold = new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync)
                .resolveCold(true, ID, f.canonical, f.branches);
        assertEquals(ActiveTimelineStore.Classification.CANONICAL, cold.classification);
        assertArrayEquals(CANONICAL, cold.nativeSave);
        assertEquals("rollback retry is idempotent", rolledBack.canonicalGeneration,
                f.timeline.promotions().rollback(ID, f.canonical, f.branches, approval.transactionId)
                        .canonicalGeneration);
    }

    @Test public void pendingIntentBlocksColdLoadEveryWriterAndOrdinarySelectionAfterRestart() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), point -> {
            if (point == NativeSavePromotionStore.FaultPoint.AFTER_INTENT)
                throw new IOException("injected stop after intent");
        }, ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        try {
            f.owner.promoteNativeSaveBranch(f.token, approval);
            fail("intent failpoint must stop before canonical PLSV write");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("injected")); }

        ActiveTimelineStore restarted = new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync);
        assertEquals(NativeSavePromotionStore.Classification.RECOVERY_REQUIRED,
                restarted.promotions().inspect(ID).classification);
        ActiveTimelineStore.Resolution cold = restarted.resolveCold(true, ID, f.canonical, f.branches);
        assertEquals(ActiveTimelineStore.Classification.RECOVERY_REQUIRED, cold.classification);
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
        try { restarted.assertWritable(ID, f.branch.id, f.branch.saves); fail("branch write gate must block"); }
        catch (IOException expected) { }
        try { restarted.recordDurableSave(ID, f.branch.id, f.branch.saves, BRANCH); fail("receipt gate must block"); }
        catch (IOException expected) { }
        try { restarted.selectCanonical(ID, f.canonical); fail("normal selector changes must block"); }
        catch (IOException expected) { }

        NativeSavePromotionStore.Result rollback = restarted.promotions()
                .rollback(ID, f.canonical, f.branches, approval.transactionId);
        assertEquals(NativeSavePromotionStore.Classification.ROLLED_BACK, rollback.classification);
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
        assertArrayEquals(BRANCH, f.branch.saves.load(ROM).bytes);
    }

    @Test public void staleCanonicalAndBranchChangesInvalidateApproval() throws Exception {
        Fixture canonicalChanged = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal canonicalProposal = canonicalChanged.owner
                .proposeNativeSavePromotion(canonicalChanged.token);
        canonicalChanged.canonical.write(ROM, bytes(CANONICAL.length, 8));
        try {
            canonicalChanged.owner.approveNativeSavePromotion(canonicalChanged.token, canonicalProposal,
                    canonicalProposal.confirmationText);
            fail("changed canonical generation must invalidate proposal");
        } catch (IOException expected) { }

        Fixture branchChanged = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal branchProposal = branchChanged.owner
                .proposeNativeSavePromotion(branchChanged.token);
        branchChanged.branch.saves.write(ROM, bytes(BRANCH.length, 9));
        try {
            branchChanged.owner.approveNativeSavePromotion(branchChanged.token, branchProposal,
                    branchProposal.confirmationText);
            fail("changed branch generation must invalidate proposal");
        } catch (IOException expected) { }
    }

    @Test public void approvalBecomesStaleWhenEitherTimelineAdvancesBeforeExecution() throws Exception {
        Fixture canonicalChanged = new Fixture(temporary.newFolder().toPath(),
                NativeSavePromotionStore.NO_FAULTS, ignored -> { });
        NativeSavePromotionStore.Proposal requested = canonicalChanged.owner
                .proposeNativeSavePromotion(canonicalChanged.token);
        NativeSavePromotionStore.Approval approved = canonicalChanged.owner.approveNativeSavePromotion(
                canonicalChanged.token, requested, requested.confirmationText);
        byte[] newerCanonical = bytes(CANONICAL.length, 8);
        canonicalChanged.canonical.write(ROM, newerCanonical);
        try {
            canonicalChanged.owner.promoteNativeSaveBranch(canonicalChanged.token, approved);
            fail("canonical write after approval must invalidate promotion");
        } catch (IOException expected) { }
        assertArrayEquals("newer canonical progress must be preserved", newerCanonical,
                canonicalChanged.canonical.load(ROM).bytes);
        assertEquals(NativeSavePromotionStore.Classification.RECOVERY_REQUIRED,
                new ActiveTimelineStore(canonicalChanged.root.resolve("timelines"), Fixture::sync)
                        .promotions().inspect(ID).classification);

        Fixture branchChanged = new Fixture(temporary.newFolder().toPath(),
                NativeSavePromotionStore.NO_FAULTS, ignored -> { });
        requested = branchChanged.owner.proposeNativeSavePromotion(branchChanged.token);
        approved = branchChanged.owner.approveNativeSavePromotion(
                branchChanged.token, requested, requested.confirmationText);
        byte[] newerBranch = bytes(BRANCH.length, 9);
        branchChanged.branch.saves.write(ROM, newerBranch);
        try {
            branchChanged.owner.promoteNativeSaveBranch(branchChanged.token, approved);
            fail("branch advancement after approval must invalidate promotion");
        } catch (IOException expected) { }
        assertArrayEquals(CANONICAL, branchChanged.canonical.load(ROM).bytes);
        assertArrayEquals(newerBranch, branchChanged.branch.saves.load(ROM).bytes);
    }

    @Test public void rollbackRefusesToOverwriteNativeProgressSavedAfterSuccessfulPromotion() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        f.owner.promoteNativeSaveBranch(f.token, approval);
        f.timeline.assertWritable(ID, null, f.canonical);
        byte[] newestProgress = bytes(CANONICAL.length, 3);
        f.canonical.write(ROM, newestProgress);
        f.timeline.recordDurableSave(ID, null, f.canonical, newestProgress);
        try {
            f.timeline.promotions().rollback(ID, f.canonical, f.branches, approval.transactionId);
            fail("rollback cannot destroy subsequent verified canonical progress");
        } catch (IOException expected) { }
        assertArrayEquals(newestProgress, f.canonical.load(ROM).bytes);
        assertArrayEquals(BRANCH, f.branch.saves.load(ROM).bytes);
        Path anchor = f.root.resolve("timelines/native-save-promotions/gba-" + ROM)
                .resolve(approval.transactionId).resolve("anchor/canonical.native");
        assertArrayEquals("original canonical rollback anchor stays untouched", CANONICAL,
                Files.readAllBytes(anchor));
    }

    @Test public void unknownModeAndSymlinkedPromotionRootFailClosed() throws Exception {
        Fixture unknown = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        unknown.host.mode = NativeSavePromotionStore.NativeSaveMode.UNKNOWN;
        try { unknown.owner.proposeNativeSavePromotion(unknown.token); fail("unknown mode must be rejected"); }
        catch (IOException expected) { }

        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        Path real = f.root.resolve("external-promotion-data");
        Files.createDirectory(real);
        Path promotionRoot = f.root.resolve("timelines/native-save-promotions");
        try { Files.createSymbolicLink(promotionRoot, real); }
        catch (UnsupportedOperationException | IOException unavailable) { return; }
        try { f.owner.proposeNativeSavePromotion(f.token); fail("symlinked promotion root must be rejected"); }
        catch (IOException expected) { }
        assertEquals(0, Files.list(real).count());
    }

    @Test public void partialPlsvFailureRetainsIntentAndAllowsManualRollbackRetry() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(false);
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                point -> {
                    if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE && failOnce.getAndSet(false))
                        throw new FileSystemException("canonical", null, "ENOSPC injected");
                });
        failOnce.set(true);
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        try { f.owner.promoteNativeSaveBranch(f.token, approval); fail("PLSV partial write must fail"); }
        catch (IOException expected) { }
        assertEquals(NativeSavePromotionStore.Classification.RECOVERY_REQUIRED,
                new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync)
                        .promotions().inspect(ID).classification);
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
        NativeSavePromotionStore.Result recovery = f.timeline.promotions()
                .recoverRollback(ID, f.canonical, f.branches, approval.transactionId);
        assertEquals(NativeSavePromotionStore.Classification.ROLLED_BACK, recovery.classification);
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
    }

    @Test public void anchorCorruptionRemainsRecoveryRequiredAndIsNeverOverwritten() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), point -> {
            if (point == NativeSavePromotionStore.FaultPoint.AFTER_ANCHOR)
                throw new IOException("injected after verified anchor");
        }, ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        try { f.owner.promoteNativeSaveBranch(f.token, approval); fail("anchor boundary must interrupt"); }
        catch (IOException expected) { }
        Path tx = f.root.resolve("timelines/native-save-promotions/gba-" + ROM)
                .resolve(approval.transactionId);
        Path anchorRecord = tx.resolve("anchor/record1");
        byte[] corrupt = Files.readAllBytes(anchorRecord);
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(anchorRecord, corrupt);
        assertEquals(NativeSavePromotionStore.Classification.CORRUPT,
                f.timeline.promotions().inspect(ID).classification);
        try { f.timeline.promotions().rollback(ID, f.canonical, f.branches, approval.transactionId);
            fail("corrupt anchor cannot authorize rollback"); }
        catch (IOException expected) { }
        assertArrayEquals(CANONICAL, f.canonical.load(ROM).bytes);
    }

    @Test public void aDifferentStoreCannotUseAnotherStoresApproval() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);

        ActiveTimelineStore secondTimeline = new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync);
        EmulatorSessionOwner secondOwner = new EmulatorSessionOwner();
        FakeHost secondHost = new FakeHost(f, secondTimeline);
        EmulatorSessionOwner.HostToken secondToken = secondOwner.attach(EmulatorSessionOwner.Console.GBA,
                secondHost, new SessionReceiptStore(f.root.resolve("second-receipts")));
        assertTrue(secondOwner.initialize(secondToken));
        assertTrue(secondOwner.open(secondToken, ROM, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { return null; }
            @Override public String selectedBranchId() { return f.branch.id; }
            @Override public void discardFailedLoad() { }
        }).opened);
        try { secondOwner.promoteNativeSaveBranch(secondToken, approval); fail("foreign approval must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("fake")); }
    }

    @Test public void advancingCanonicalAfterCommitRejectsUnsafeLateRollbackAndRetainsAnchor() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        assertEquals(NativeSavePromotionStore.Classification.COMMITTED,
                f.owner.promoteNativeSaveBranch(f.token, approval).classification);

        byte[] newerCanonical = bytes(CANONICAL.length, 3);
        f.timeline.assertWritable(ID, null, f.canonical);
        f.canonical.write(ROM, newerCanonical);
        f.timeline.recordDurableSave(ID, null, f.canonical, newerCanonical);
        try {
            f.timeline.promotions().rollback(ID, f.canonical, f.branches, approval.transactionId);
            fail("Undo after newer canonical gameplay cannot destroy the subsequent generation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("outside the original or promoted generation"));
        }
        assertArrayEquals(newerCanonical, f.canonical.load(ROM).bytes);
        assertArrayEquals(BRANCH, f.branch.saves.load(ROM).bytes);
        Path anchoredNative = f.root.resolve("timelines/native-save-promotions/gba-" + ROM)
                .resolve(approval.transactionId).resolve("anchor/canonical.native");
        assertArrayEquals("Original pre-promotion canonical remains independently recoverable",
                CANONICAL, Files.readAllBytes(anchoredNative));
        assertEquals(ActiveTimelineStore.Classification.CANONICAL,
                new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync)
                        .resolveCold(true, ID, f.canonical, f.branches).classification);
    }

    @Test public void corruptTerminalReceiptBlocksColdUseAndNativeWritesWithoutLosingRollback() throws Exception {
        Fixture f = new Fixture(temporary.newFolder().toPath(), NativeSavePromotionStore.NO_FAULTS,
                ignored -> { });
        NativeSavePromotionStore.Proposal proposal = f.owner.proposeNativeSavePromotion(f.token);
        NativeSavePromotionStore.Approval approval = f.owner.approveNativeSavePromotion(
                f.token, proposal, proposal.confirmationText);
        f.owner.promoteNativeSaveBranch(f.token, approval);
        Path tx = f.root.resolve("timelines/native-save-promotions/gba-" + ROM)
                .resolve(approval.transactionId);
        Path terminal = tx.resolve("terminal.record");
        byte[] malformed = Files.readAllBytes(terminal);
        malformed[malformed.length - 4] ^= 1;
        Files.write(terminal, malformed);

        ActiveTimelineStore restarted = new ActiveTimelineStore(f.root.resolve("timelines"), Fixture::sync);
        assertEquals(NativeSavePromotionStore.Classification.CORRUPT,
                restarted.promotions().inspect(ID).classification);
        assertEquals(ActiveTimelineStore.Classification.RECOVERY_REQUIRED,
                restarted.resolveCold(true, ID, f.canonical, f.branches).classification);
        try { restarted.assertWritable(ID, null, f.canonical); fail("Corrupt receipt permits a write"); }
        catch (IOException expected) { }
        try { restarted.selectCanonical(ID, f.canonical); fail("Corrupt receipt permits re-selection"); }
        catch (IOException expected) { }
        assertArrayEquals(BRANCH, f.canonical.load(ROM).bytes);
        assertArrayEquals(BRANCH, f.branch.saves.load(ROM).bytes);
        assertArrayEquals(CANONICAL, Files.readAllBytes(tx.resolve("anchor/canonical.native")));
    }

    private static final class Fixture {
        final Path root;
        final GameSaveStore canonical;
        final CheckpointStore checkpoints;
        final HistoricalBranchStore branches;
        final HistoricalBranchStore.Branch branch;
        final ActiveTimelineStore timeline;
        final EmulatorSessionOwner owner = new EmulatorSessionOwner();
        final FakeHost host;
        final EmulatorSessionOwner.HostToken token;

        Fixture(Path root, NativeSavePromotionStore.FaultInjector promotionFaults,
                GameSaveStore.FailureInjector saveFaults) throws Exception {
            this.root = root;
            canonical = new GameSaveStore(root.resolve("canonical").toFile(), Fixture::syncSave, saveFaults);
            canonical.write(ROM, CANONICAL);
            checkpoints = new CheckpointStore(root.resolve("checkpoints"), Fixture::sync,
                    ignored -> Long.MAX_VALUE, ignored -> { });
            CheckpointStore.Record record = checkpoints.create(ID,
                    CheckpointStore.Capture.supported(1, MACHINE, CANONICAL), CheckpointStore.Kind.MANUAL);
            branches = new HistoricalBranchStore(root.resolve("branches"), Fixture::sync,
                    ignored -> Long.MAX_VALUE, Fixture::syncSave, ignored -> { });
            branch = branches.create(checkpoints, record.id, ID);
            branch.saves.write(ROM, BRANCH);
            timeline = new ActiveTimelineStore(root.resolve("timelines"), Fixture::sync,
                    ActiveTimelineStore.NO_FAULTS, promotionFaults);
            timeline.selectCanonical(ID, canonical);
            timeline.selectBranch(ID, branches, branch);
            host = new FakeHost(this);
            token = owner.attach(EmulatorSessionOwner.Console.GBA, host,
                    new SessionReceiptStore(root.resolve("receipts")));
            if (!owner.initialize(token)) throw new AssertionError("fake host initialization failed");
            EmulatorSessionOwner.OpenResult opened = owner.open(token, ROM,
                    new EmulatorSessionOwner.GameLoader() {
                        @Override public String loadAndRestore() { return null; }
                        @Override public String selectedBranchId() { return branch.id; }
                        @Override public void discardFailedLoad() { }
                    });
            if (!opened.opened) throw new AssertionError("fake paused branch session did not open: " + opened.error);
        }

        static void sync(Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
        static void syncSave(java.io.File directory) throws IOException { sync(directory.toPath()); }
    }

    private static final class FakeHost implements EmulatorSessionOwner.Host {
        final Fixture fixture;
        final ActiveTimelineStore timeline;
        NativeSavePromotionStore.NativeSaveMode mode = NativeSavePromotionStore.NativeSaveMode.GBA_CARTRIDGE_SRAM_32K;
        FakeHost(Fixture fixture) { this(fixture, fixture.timeline); }
        FakeHost(Fixture fixture, ActiveTimelineStore timeline) { this.fixture = fixture; this.timeline = timeline; }
        @Override public boolean initializeCore() { return true; }
        @Override public boolean stopAndSave() { return true; }
        @Override public boolean startLoops(long token) { return true; }
        @Override public void unloadGame() { }
        @Override public void shutdownCore() { }
        @Override public CheckpointStore.Identity checkpointIdentity() { return ID; }
        @Override public ActiveTimelineStore activeTimelineStore() { return timeline; }
        @Override public GameSaveStore canonicalGameSaveStore() { return fixture.canonical; }
        @Override public HistoricalBranchStore historicalBranchStore() { return fixture.branches; }
        @Override public NativeSavePromotionStore.NativeSaveMode nativeSavePromotionMode() { return mode; }
    }

    private static String repeat(char value) {
        char[] result = new char[64];
        Arrays.fill(result, value);
        return new String(result);
    }
    private static byte[] bytes(int length, int value) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
