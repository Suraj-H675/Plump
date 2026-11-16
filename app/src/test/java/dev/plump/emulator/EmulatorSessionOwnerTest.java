package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public final class EmulatorSessionOwnerTest {
    private static final String GAME_A = repeat('a');
    private static final String GAME_B = repeat('b');

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void initializationAndRepeatedLifecycleCallsAreIdempotent() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(owner.initialize(token));
        assertEquals(1, host.initializations.get());
        assertTrue(open(owner, token, GAME_A).opened);

        assertTrue(owner.resume(token));
        long firstRun = host.lastRunToken;
        assertTrue(owner.resume(token));
        assertEquals(1, host.starts.get());
        assertTrue(owner.isCurrentRun(token, firstRun));
        assertTrue(owner.pause(token));
        assertTrue(owner.pause(token));
        assertEquals(1, host.stops.get());
        assertTrue(owner.resume(token));
        assertEquals(2, host.starts.get());
        assertFalse(owner.isCurrentRun(token, firstRun));
        assertFalse(owner.noteSaveFailure(token, firstRun));
        assertTrue(owner.isCurrentRun(token, host.lastRunToken));
    }

    @Test public void serializedConcurrentResumeAndPauseCannotDuplicateWorkers() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.startEntered = new CountDownLatch(1);
        host.allowStart = new CountDownLatch(1);
        host.stopEntered = new CountDownLatch(1);
        host.allowStop = new CountDownLatch(1);
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.DS, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> resumes = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) resumes.add(pool.submit(() -> owner.resume(token)));
            assertTrue(host.startEntered.await(2, TimeUnit.SECONDS));
            host.allowStart.countDown();
            for (Future<Boolean> future : resumes) assertTrue(future.get(2, TimeUnit.SECONDS));
            assertEquals(1, host.starts.get());
            assertEquals(1, host.activeWorkers.get());

            List<Future<Boolean>> pauses = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) pauses.add(pool.submit(() -> owner.pause(token)));
            assertTrue(host.stopEntered.await(2, TimeUnit.SECONDS));
            host.allowStop.countDown();
            for (Future<Boolean> future : pauses) assertTrue(future.get(2, TimeUnit.SECONDS));
            assertEquals(1, host.stops.get());
            assertEquals(0, host.activeWorkers.get());
        } finally {
            host.allowStart.countDown();
            host.allowStop.countDown();
            pool.shutdownNow();
        }
    }

    @Test public void timeoutOrSaveFailureBlocksResumeSwitchAndUnloadUntilRetry() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost gba = new FakeHost();
        EmulatorSessionOwner.HostToken gbaToken = attach(owner, root, EmulatorSessionOwner.Console.GBA, gba);
        FakeHost ds = new FakeHost();
        EmulatorSessionOwner.HostToken dsToken = attach(owner, root, EmulatorSessionOwner.Console.DS, ds);
        assertTrue(owner.initialize(gbaToken));
        assertTrue(owner.initialize(dsToken));
        assertTrue(open(owner, gbaToken, GAME_A).opened);
        assertTrue(owner.resume(gbaToken));
        gba.stopResult = false; // Represents a join timeout or native-save write error.

        assertFalse(owner.pause(gbaToken));
        assertEquals(0, gba.successfulSaves.get());
        assertFalse(owner.resume(gbaToken));
        assertFalse(open(owner, gbaToken, GAME_B).opened);
        assertFalse(open(owner, dsToken, GAME_B).opened);
        assertEquals(0, gba.unloads.get());
        assertEquals(0, ds.starts.get());

        gba.stopResult = true;
        assertTrue(owner.pause(gbaToken));
        assertTrue(open(owner, gbaToken, GAME_B).opened);
        assertEquals(1, gba.unloads.get());
    }

    @Test public void gbaRemainsPausedUnderDsAndReturnsOnlyAfterDsPause() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost gba = new FakeHost();
        EmulatorSessionOwner.HostToken gbaToken = attach(owner, root, EmulatorSessionOwner.Console.GBA, gba);
        FakeHost ds = new FakeHost();
        EmulatorSessionOwner.HostToken dsToken = attach(owner, root, EmulatorSessionOwner.Console.DS, ds);
        assertTrue(owner.initialize(gbaToken));
        assertTrue(owner.initialize(dsToken));
        assertTrue(open(owner, gbaToken, GAME_A).opened);
        assertTrue(owner.resume(gbaToken));
        assertTrue(owner.pause(gbaToken));
        assertTrue(owner.hasLoadedGame(gbaToken));
        assertTrue(open(owner, dsToken, GAME_B).opened);
        assertTrue(owner.resume(dsToken));
        assertTrue(owner.isCurrentRun(dsToken, ds.lastRunToken));
        assertFalse(owner.isCurrentRun(gbaToken, gba.lastRunToken));
        assertFalse(owner.resume(gbaToken));

        assertTrue(owner.pause(dsToken));
        assertTrue(owner.resume(gbaToken));
        assertTrue(owner.isCurrentRun(gbaToken, gba.lastRunToken));
        assertFalse(owner.isCurrentRun(dsToken, ds.lastRunToken));
        assertTrue(owner.pause(gbaToken));
        assertTrue(owner.resume(dsToken));

        // Closing a parked GBA host must not revoke DS ownership or kill its callbacks.
        assertTrue(owner.pause(dsToken));
        assertTrue(owner.resume(dsToken));
        assertTrue(owner.closeHost(gbaToken));
        assertTrue(owner.isCurrentRun(dsToken, ds.lastRunToken));
        assertTrue(owner.pause(dsToken));
        assertTrue(owner.resume(dsToken));
        assertTrue(owner.pause(dsToken));
        assertTrue(owner.resume(dsToken));
        assertTrue(owner.isCurrentRun(dsToken, ds.lastRunToken));
    }

    @Test public void multipleDsRomReloadsAndGbaReturnNeverDuplicateOwnedWorkers() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost gba = new FakeHost();
        FakeHost ds = new FakeHost();
        EmulatorSessionOwner.HostToken gbaToken = attach(owner, root, EmulatorSessionOwner.Console.GBA, gba);
        EmulatorSessionOwner.HostToken dsToken = attach(owner, root, EmulatorSessionOwner.Console.DS, ds);
        assertTrue(owner.initialize(gbaToken));
        assertTrue(owner.initialize(dsToken));

        assertTrue(open(owner, gbaToken, GAME_A).opened);
        assertTrue(owner.resume(gbaToken));
        assertTrue(owner.pause(gbaToken));
        assertTrue(open(owner, dsToken, GAME_A).opened);
        assertTrue(owner.resume(dsToken));
        long firstDsRun = ds.lastRunToken;

        assertTrue(open(owner, dsToken, GAME_B).opened);
        assertEquals(1, ds.unloads.get());
        assertEquals(SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.DS, GAME_A).state);
        assertFalse(owner.isCurrentRun(dsToken, firstDsRun));
        assertTrue(owner.resume(dsToken));
        assertTrue(owner.pause(dsToken));
        assertTrue(owner.resume(gbaToken));
        assertFalse(owner.resume(dsToken));
        assertEquals(0, ds.activeWorkers.get());
        assertEquals(1, gba.activeWorkers.get());
        assertTrue(owner.pause(gbaToken));

        assertTrue(open(owner, dsToken, GAME_A).opened);
        assertEquals(2, ds.unloads.get());
        assertEquals(SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.DS, GAME_B).state);
        assertTrue(owner.resume(dsToken));
        assertEquals(1, ds.activeWorkers.get());
        assertEquals(0, gba.activeWorkers.get());
        assertEquals(3, ds.starts.get());
        assertEquals(2, gba.starts.get());
        assertEquals(1, ds.initializations.get());
        assertEquals(1, gba.initializations.get());
        assertTrue(ds.successfulSaves.get() >= 2);
        assertTrue(owner.isCurrentRun(dsToken, ds.lastRunToken));
        assertEquals(SessionReceiptStore.State.RUNNING,
                owner.readReceipt(EmulatorSessionOwner.Console.DS, GAME_A).state);
    }

    @Test public void switchIsOrderedAndOldReceiptClosesBeforeNewSessionReceipt() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        assertTrue(owner.resume(token));
        EmulatorSessionOwner.OpenResult switched = owner.open(token, GAME_B, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { host.events.add("load-new"); return null; }
            @Override public void discardFailedLoad() { host.events.add("discard"); }
        });
        assertTrue(switched.error, switched.opened);
        assertFalse(owner.isCurrentRun(token, host.lastRunToken));
        assertTrue(host.events.indexOf("stop-save") < host.events.indexOf("unload"));
        assertTrue(host.events.indexOf("unload") < host.events.indexOf("load-new"));
        assertEquals(SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.GBA, GAME_A).state);
        assertEquals(SessionReceiptStore.State.PAUSED,
                owner.readReceipt(EmulatorSessionOwner.Console.GBA, GAME_B).state);
    }

    @Test public void staleRunAndHostTokensCannotMutateReattachedActivity() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost first = new FakeHost();
        EmulatorSessionOwner.HostToken oldToken = attach(owner, root, EmulatorSessionOwner.Console.DS, first);
        assertTrue(owner.initialize(oldToken));
        assertTrue(open(owner, oldToken, GAME_A).opened);
        assertTrue(owner.resume(oldToken));
        long oldRun = first.lastRunToken;
        assertTrue(owner.pause(oldToken));
        assertTrue(owner.resume(oldToken));
        long newRun = first.lastRunToken;
        assertFalse(owner.isCurrentRun(oldToken, oldRun));
        assertTrue(owner.isCurrentRun(oldToken, newRun));
        assertTrue(owner.closeHost(oldToken));

        FakeHost second = new FakeHost();
        EmulatorSessionOwner.HostToken newToken = attach(owner, root, EmulatorSessionOwner.Console.DS, second);
        assertTrue(owner.initialize(newToken));
        assertFalse(owner.pause(oldToken));
        assertFalse(owner.isCurrentRun(oldToken, newRun));
        assertTrue(open(owner, newToken, GAME_A).opened);
        assertTrue(owner.resume(newToken));
        assertTrue(owner.isCurrentRun(newToken, second.lastRunToken));
    }

    @Test public void receiptFailureBlocksStartAndUnresolvedPauseUntilPublicationRetry() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<SessionReceiptStore.Failpoint> fail = new AtomicReference<>();
        SessionReceiptStore store = new SessionReceiptStore(root, point -> {
            if (fail.compareAndSet(point, null)) throw new IOException("injected " + point);
        });
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = owner.attach(EmulatorSessionOwner.Console.GBA, host, store);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);

        fail.set(SessionReceiptStore.Failpoint.BEFORE_TEMP_WRITE);
        assertFalse(owner.resume(token));
        assertEquals(0, host.starts.get());
        assertTrue(owner.resume(token));
        long run = host.lastRunToken;
        assertTrue(owner.isCurrentRun(token, run));
        assertTrue(owner.noteSaveFailure(token, run));
        assertFalse(owner.isCurrentRun(token, run));
        assertFalse(owner.resume(token));

        fail.set(SessionReceiptStore.Failpoint.BEFORE_RENAME);
        assertFalse(owner.pause(token));
        assertFalse(owner.resume(token));
        assertEquals(0, host.unloads.get());
        assertTrue(owner.pause(token));
        assertFalse(owner.isCurrentRun(token, host.lastRunToken));
    }

    @Test public void loaderRuntimeWithoutMessageCannotBeTreatedAsSuccessfulOpen() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        AtomicInteger discarded = new AtomicInteger();
        EmulatorSessionOwner.OpenResult result = owner.open(token, GAME_A, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { throw new IllegalStateException(); }
            @Override public void discardFailedLoad() { discarded.incrementAndGet(); }
        });
        assertFalse("A message-less loader crash must not publish a playable session", result.opened);
        assertEquals(1, discarded.get());
        assertFalse(owner.hasLoadedGame(token));
        assertFalse(owner.resume(token));
        assertTrue(open(owner, token, GAME_B).opened);
    }

    @Test public void failedPartialRomCleanupMustBlockFurtherGamesAndOtherConsole() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost gba = new FakeHost();
        FakeHost ds = new FakeHost();
        EmulatorSessionOwner.HostToken gbaToken = attach(owner, root, EmulatorSessionOwner.Console.GBA, gba);
        EmulatorSessionOwner.HostToken dsToken = attach(owner, root, EmulatorSessionOwner.Console.DS, ds);
        assertTrue(owner.initialize(gbaToken));
        assertTrue(owner.initialize(dsToken));
        EmulatorSessionOwner.OpenResult broken = owner.open(gbaToken, GAME_A, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { return "Native restore failed"; }
            @Override public void discardFailedLoad() { throw new IllegalStateException("Native unload failed"); }
        });
        assertFalse(broken.opened);
        assertFalse("Unknown native core ownership cannot start another GBA ROM", open(owner, gbaToken, GAME_B).opened);
        assertFalse("Unknown native core ownership cannot switch to DS", open(owner, dsToken, GAME_B).opened);
        assertFalse(owner.resume(gbaToken));
        assertFalse(owner.pause(gbaToken));
        assertFalse("Do not attempt to shut down a partially loaded core after cleanup failed",
                owner.closeHost(gbaToken));
        assertEquals(0, gba.starts.get());
        assertEquals(0, gba.unloads.get());
    }

    @Test public void failedNativeUnloadCannotBeRecordedAsCleanShutdown() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        assertTrue(owner.resume(token));
        host.failUnload = true;
        assertFalse(owner.closeHost(token));
        assertNotEquals("Native unload failed: must not pretend the session closed cleanly",
                SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.GBA, GAME_A).state);
        assertFalse(owner.resume(token));
        host.failUnload = false;
        assertTrue(owner.closeHost(token));
        assertEquals(SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.GBA, GAME_A).state);
    }

    @Test public void uncertainCoreShutdownCannotBeReusedOrClaimClosed() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        EmulatorSessionOwner.HostToken token = attach(owner, root, EmulatorSessionOwner.Console.DS, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        assertTrue(owner.resume(token));
        host.failShutdown = true;
        assertFalse(owner.closeHost(token));
        assertNotEquals(SessionReceiptStore.State.CLOSED,
                owner.readReceipt(EmulatorSessionOwner.Console.DS, GAME_A).state);
        assertFalse("A partially shut-down native core cannot accept a new game",
                open(owner, token, GAME_B).opened);
        assertFalse(owner.resume(token));
        assertFalse(owner.pause(token));
        assertFalse("A failed shutdown remains quarantined until process restart", owner.closeHost(token));
    }

    @Test public void checkpointRequiresPausedOwnedGameAndPauseCreatesAnAutoRecord() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.checkpointStore = new CheckpointStore(root.resolve("checkpoints"));
        host.checkpointIdentity = checkpointIdentity(GAME_A);
        host.checkpointCapture = CheckpointStore.Capture.supported(1, new byte[]{1, 2, 3}, new byte[]{4, 5});
        EmulatorSessionOwner.HostToken token = attach(owner, root.resolve("receipts"), EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);

        CheckpointStore.Record manual = owner.createManualCheckpoint(token);
        assertEquals(CheckpointStore.Kind.MANUAL, manual.kind);
        assertEquals(1, host.checkpointCaptures.get());
        assertTrue(owner.resume(token));
        try { owner.createManualCheckpoint(token); fail("Running session capture must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("paused")); }
        assertEquals(1, host.checkpointCaptures.get());

        assertTrue(owner.pause(token));
        assertEquals(2, host.checkpointCaptures.get());
        List<CheckpointStore.Record> records = host.checkpointStore.listValid(host.checkpointIdentity);
        assertEquals(1, records.stream().filter(r -> r.kind == CheckpointStore.Kind.MANUAL).count());
        assertEquals(1, records.stream().filter(r -> r.kind == CheckpointStore.Kind.AUTO).count());
    }

    @Test public void exclusiveOwnerLockPreventsCloseDuringPausedCapture() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.checkpointStore = new CheckpointStore(root.resolve("checkpoints"));
        host.checkpointIdentity = new CheckpointStore.Identity("ds", GAME_A, "melonds-libretro-ds",
                "16f127dbb587a73371827ce79689c5d237c4b59b", "f394adbacb5722ee97c1b37c8064da9a25818310",
                "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30", "arm64-v8a", 1);
        host.checkpointCapture = CheckpointStore.Capture.supported(1, new byte[]{7}, new byte[]{8});
        host.captureEntered = new CountDownLatch(1);
        host.allowCapture = new CountDownLatch(1);
        EmulatorSessionOwner.HostToken token = attach(owner, root.resolve("receipts"), EmulatorSessionOwner.Console.DS, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CheckpointStore.Record> capture = pool.submit(() -> owner.createManualCheckpoint(token));
            assertTrue(host.captureEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            Future<Boolean> close = pool.submit(() -> { closeStarted.countDown(); return owner.closeHost(token); });
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse("Close waits until the session-owned capture releases the transition lock", close.isDone());
            assertEquals(0, host.unloads.get());
            host.allowCapture.countDown();
            CheckpointStore.Record manual = capture.get(2, TimeUnit.SECONDS);
            assertEquals(CheckpointStore.Kind.MANUAL, manual.kind);
            assertTrue(close.get(2, TimeUnit.SECONDS));
            assertEquals(1, host.unloads.get());
            assertEquals(1, host.checkpointStore.listValid(host.checkpointIdentity).stream()
                    .filter(r -> r.kind == CheckpointStore.Kind.MANUAL).count());
        } finally {
            host.allowCapture.countDown();
            pool.shutdownNow();
        }
    }

    @Test public void identityMismatchAndUnsupportedSerializerDoNotPublish() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.checkpointStore = new CheckpointStore(root.resolve("checkpoints"));
        host.checkpointIdentity = checkpointIdentity(GAME_B);
        host.checkpointCapture = CheckpointStore.Capture.supported(1, new byte[]{1}, new byte[]{2});
        EmulatorSessionOwner.HostToken token = attach(owner, root.resolve("receipts"), EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        try { owner.createManualCheckpoint(token); fail("Wrong game identity must be rejected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("identity")); }
        assertEquals(0, host.checkpointCaptures.get());

        host.checkpointIdentity = checkpointIdentity(GAME_A);
        host.checkpointCapture = CheckpointStore.Capture.unsupported(1, "pinned core has no serializer");
        try { owner.createManualCheckpoint(token); fail("Unsupported serializer must fail closed"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("no serializer")); }
        assertTrue(host.checkpointStore.listValid(host.checkpointIdentity).isEmpty());
    }

    @Test public void failedAutomaticCheckpointDoesNotUndoDurablePauseOrNativeSave() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.checkpointStore = new CheckpointStore(root.resolve("checkpoints"), CheckpointStoreTest::sync,
                path -> Long.MAX_VALUE, point -> {
                    if (point == CheckpointStore.FaultPoint.AFTER_TEMP_WRITE) throw new IOException("injected disk error");
                });
        host.checkpointIdentity = checkpointIdentity(GAME_A);
        host.checkpointCapture = CheckpointStore.Capture.supported(1, new byte[]{1}, new byte[]{2});
        EmulatorSessionOwner.HostToken token = attach(owner, root.resolve("receipts"), EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        assertTrue(owner.resume(token));
        assertTrue(owner.pause(token));
        assertEquals(1, host.successfulSaves.get());
        assertTrue(host.checkpointFailure.contains("injected disk error"));
        assertEquals(SessionReceiptStore.State.PAUSED,
                owner.readReceipt(EmulatorSessionOwner.Console.GBA, GAME_A).state);
        assertTrue(owner.resume(token));
    }

    @Test public void repeatedGameSwitchesKeepManualAndAutomaticRecordsIndependent() throws Exception {
        Path root = temporary.newFolder().toPath();
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        FakeHost host = new FakeHost();
        host.checkpointStore = new CheckpointStore(root.resolve("checkpoints"));
        host.checkpointIdentity = checkpointIdentity(GAME_A);
        host.checkpointCapture = CheckpointStore.Capture.supported(1, new byte[]{1}, new byte[]{2});
        EmulatorSessionOwner.HostToken token = attach(owner, root.resolve("receipts"), EmulatorSessionOwner.Console.GBA, host);
        assertTrue(owner.initialize(token));
        assertTrue(open(owner, token, GAME_A).opened);
        CheckpointStore.Record manualA = owner.createManualCheckpoint(token);
        assertTrue(owner.resume(token));
        assertTrue(owner.open(token, GAME_B, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { host.checkpointIdentity = checkpointIdentity(GAME_B); return null; }
            @Override public void discardFailedLoad() { }
        }).opened);
        CheckpointStore.Record manualB = owner.createManualCheckpoint(token);
        assertTrue(owner.resume(token));
        assertTrue(owner.open(token, GAME_A, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { host.checkpointIdentity = checkpointIdentity(GAME_A); return null; }
            @Override public void discardFailedLoad() { }
        }).opened);
        assertTrue(owner.resume(token));
        assertTrue(owner.pause(token));

        List<CheckpointStore.Record> gameA = host.checkpointStore.listValid(checkpointIdentity(GAME_A));
        List<CheckpointStore.Record> gameB = host.checkpointStore.listValid(checkpointIdentity(GAME_B));
        assertEquals(manualA.id, gameA.stream().filter(r -> r.kind == CheckpointStore.Kind.MANUAL)
                .findFirst().orElseThrow().id);
        assertEquals(manualB.id, gameB.stream().filter(r -> r.kind == CheckpointStore.Kind.MANUAL)
                .findFirst().orElseThrow().id);
        assertTrue(gameA.stream().anyMatch(r -> r.kind == CheckpointStore.Kind.AUTO));
        assertTrue(gameB.stream().anyMatch(r -> r.kind == CheckpointStore.Kind.AUTO));
        assertFalse(gameA.stream().anyMatch(r -> r.identity.romSha256.equals(GAME_B)));
        assertFalse(gameB.stream().anyMatch(r -> r.identity.romSha256.equals(GAME_A)));
    }

    private EmulatorSessionOwner.HostToken attach(EmulatorSessionOwner owner, Path root,
                                                   EmulatorSessionOwner.Console console, FakeHost host) {
        return owner.attach(console, host, new SessionReceiptStore(root));
    }

    private EmulatorSessionOwner.OpenResult open(EmulatorSessionOwner owner,
                                                  EmulatorSessionOwner.HostToken token, String game) {
        return owner.open(token, game, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { return null; }
            @Override public void discardFailedLoad() { }
        });
    }

    private static String repeat(char value) {
        char[] bytes = new char[64];
        Arrays.fill(bytes, value);
        return new String(bytes);
    }

    private static CheckpointStore.Identity checkpointIdentity(String gameId) {
        return new CheckpointStore.Identity("gba", gameId, "mgba-libretro",
                "d58ea8a86f30598fbf3958585de3d33028535072", "none",
                "05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429",
                "arm64-v8a", 1);
    }

    private static final class FakeHost implements EmulatorSessionOwner.Host {
        final AtomicInteger initializations = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger successfulSaves = new AtomicInteger();
        final AtomicInteger unloads = new AtomicInteger();
        final AtomicInteger activeWorkers = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile boolean stopResult = true;
        volatile boolean failUnload;
        volatile boolean failShutdown;
        volatile long lastRunToken;
        volatile CountDownLatch startEntered;
        volatile CountDownLatch allowStart;
        volatile CountDownLatch stopEntered;
        volatile CountDownLatch allowStop;
        final AtomicInteger checkpointCaptures = new AtomicInteger();
        volatile CheckpointStore checkpointStore;
        volatile CheckpointStore.Identity checkpointIdentity;
        volatile CheckpointStore.Capture checkpointCapture = CheckpointStore.Capture.unsupported(1, "not configured");
        volatile CountDownLatch captureEntered;
        volatile CountDownLatch allowCapture;
        volatile String checkpointFailure;

        @Override public boolean initializeCore() {
            initializations.incrementAndGet();
            events.add("initialize");
            return true;
        }

        @Override public boolean stopAndSave() {
            stops.incrementAndGet();
            events.add("stop-save");
            if (stopEntered != null) stopEntered.countDown();
            await(allowStop);
            if (!stopResult) return false;
            if (activeWorkers.get() > 0) activeWorkers.set(0);
            successfulSaves.incrementAndGet();
            return true;
        }

        @Override public boolean startLoops(long runToken) {
            lastRunToken = runToken;
            starts.incrementAndGet();
            events.add("start");
            int count = activeWorkers.incrementAndGet();
            if (count > 1) throw new AssertionError("duplicate worker pair");
            if (startEntered != null) startEntered.countDown();
            await(allowStart);
            return true;
        }

        @Override public void unloadGame() {
            if (failUnload) throw new IllegalStateException("injected native unload failure");
            unloads.incrementAndGet();
            events.add("unload");
            activeWorkers.set(0);
        }

        @Override public void shutdownCore() {
            if (failShutdown) throw new IllegalStateException("injected shutdown failure");
            shutdowns.incrementAndGet();
        }

        @Override public CheckpointStore checkpointStore() { return checkpointStore; }
        @Override public CheckpointStore.Identity checkpointIdentity() { return checkpointIdentity; }
        @Override public CheckpointStore.Capture captureCheckpoint() {
            checkpointCaptures.incrementAndGet();
            if (captureEntered != null) captureEntered.countDown();
            await(allowCapture);
            return checkpointCapture;
        }
        @Override public void checkpointFailed(String message) { checkpointFailure = message; }

        private static void await(CountDownLatch latch) {
            if (latch == null) return;
            try {
                if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("transition gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }
}
