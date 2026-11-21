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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

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

    @Test public void threeNormalWritesAdvanceBaselineThroughExactParentChain() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation current = store.initialize(createImage(root.resolve("base.img"), 12));
        assertEquals(1, current.sequence);

        for (int write = 0; write < 3; write++) {
            String priorSha = sha(current.imageFile);
            VirtualSdGenerationStore.WorkingSession session = store.openSession();
            writeAt(session.imageFile, 32768L * (write + 1), (byte) (90 + write));
            String candidateId = UUID.randomUUID().toString();
            VirtualSdGenerationStore.TransactionResult result = store.commitTransaction(session,
                    goodReceipt(), "none", candidateId, VirtualSdGenerationStore.NO_CANCELLATION);

            assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED, result.outcome);
            assertTrue(result.cleanupResolved);
            assertEquals(candidateId, result.identity.candidateGenerationId);
            assertEquals(current.sequence, result.identity.expectedHeadSequence);
            assertEquals(current.id, result.identity.expectedHeadGenerationId);
            assertEquals(current.id, result.generation.parentGenerationId);
            assertEquals(current.sequence + 1, result.generation.sequence);
            assertEquals(priorSha, sha(current.imageFile));

            current = store.currentForWrite();
            assertEquals(candidateId, current.id);
        }

        assertEquals("baseline plus three verified writes advances 1 → 2 → 3 → 4",
                4, current.sequence);
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
        String priorSha = sha(first.imageFile);
        VirtualSdGenerationStore.WorkingSession session = firstStore.openSession();
        writeAt(session.imageFile, 16384, (byte) 123);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_GENERATION_RENAME;
        String candidateId = UUID.randomUUID().toString();
        VirtualSdGenerationStore.TransactionResult tx = firstStore.commitTransaction(
                session, goodReceipt(), "none", candidateId, VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, tx.outcome);
        assertTrue(tx.cleanupResolved);
        assertEquals("the old head remains authoritative until pointer publication", first.id,
                firstStore.current().id);
        assertEquals("the exact unpublished candidate is quarantined, never promoted", 1,
                countChildren(storeRoot.resolve("generations")));
        assertEquals(1, countChildren(storeRoot.resolve("quarantine")));
        assertEquals(priorSha, sha(firstStore.current().imageFile));
        VirtualSdGenerationStore.WorkingSession retry = firstStore.openSession();
        assertEquals(first.imageSha256, sha(retry.imageFile));
        firstStore.abort(retry);
    }

    @Test public void uncertainHeadRenameRetainsExactIdentityUntilStrictReconciliation() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore store = store(storeRoot, "canonical", point -> {
            if (point == failAt[0]) throw new IOException("injected after head rename");
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 41));
        String priorSha = sha(first.imageFile);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 32768, (byte) 101);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME;
        String candidateId = UUID.randomUUID().toString();
        VirtualSdGenerationStore.TransactionResult uncertain = store.commitTransaction(
                session, goodReceipt(), "none", candidateId, VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, uncertain.outcome);
        assertFalse(uncertain.cleanupResolved);
        assertTrue(uncertain.headRenameAttempted);
        assertFalse(uncertain.directorySyncConfirmed);
        assertEquals(candidateId, uncertain.identity.candidateGenerationId);
        assertEquals(first.sequence, uncertain.identity.expectedHeadSequence);
        assertEquals(first.id, uncertain.identity.expectedHeadGenerationId);
        assertEquals(first.headSha256, uncertain.identity.expectedHeadSha256);
        assertEquals(2, uncertain.identity.candidateSequence);
        assertTrue(Files.exists(storeRoot.resolve("PENDING_TRANSACTION.vsd")));
        assertTrue("ordinary head reading cannot hide an indeterminate rename",
                assertThrows(IOException.class, store::current).getMessage().contains("recovery"));
        assertThrows(IOException.class, store::currentForWrite);

        VirtualSdGenerationStore.TransactionResult reconciled = store.reconcileStrict(uncertain.identity);
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED, reconciled.outcome);
        assertTrue(reconciled.cleanupResolved);
        assertNotNull(reconciled.generation);
        assertEquals(candidateId, reconciled.generation.id);
        assertEquals(first.id, reconciled.generation.parentGenerationId);
        assertEquals(2, reconciled.generation.sequence);
        assertFalse(Files.exists(storeRoot.resolve("PENDING_TRANSACTION.vsd")));
        assertEquals(priorSha, sha(first.imageFile));
        assertEquals(candidateId, store.currentForWrite().id);
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
        assertTrue(countChildren(root.resolve("canonical/quarantine")) >= 2);
        assertFalse(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
    }

    @Test public void directorySyncBoundaryDeterminesTheReportedCommitOutcome() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", point -> {
            if (point == failAt[0]) {
                failAt[0] = null;
                throw new IOException("injected " + point);
            }
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 47));

        VirtualSdGenerationStore.WorkingSession beforeSync = store.openSession();
        failAt[0] = VirtualSdGenerationStore.FaultPoint.DURING_HEAD_DIRECTORY_SYNC;
        VirtualSdGenerationStore.TransactionResult uncertain = store.commitTransaction(beforeSync,
                goodReceipt(), "none", UUID.randomUUID().toString(), VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, uncertain.outcome);
        assertFalse(uncertain.directorySyncConfirmed);
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED,
                store.reconcileStrict(uncertain.identity).outcome);

        VirtualSdGenerationStore.Generation beforeAfterSync = store.current();
        VirtualSdGenerationStore.WorkingSession afterSync = store.openSession();
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_DIRECTORY_SYNC;
        VirtualSdGenerationStore.TransactionResult committed = store.commitTransaction(afterSync,
                goodReceipt(), "none", UUID.randomUUID().toString(), VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED, committed.outcome);
        assertTrue(committed.directorySyncConfirmed);
        assertTrue(committed.cleanupResolved);
        assertEquals(beforeAfterSync.id, store.current().parentGenerationId);
    }

    @Test public void corruptCandidateAfterHeadRenameRemainsIndeterminateWithoutFallback() throws Exception {
        Path root = temporary.newFolder().toPath();
        VirtualSdGenerationStore.FaultPoint[] failAt = {null};
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", point -> {
            if (point == failAt[0]) throw new IOException("injected after HEAD rename");
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 54));
        String oldSha = sha(first.imageFile);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 49152, (byte) 76);
        failAt[0] = VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME;
        VirtualSdGenerationStore.TransactionResult uncertain = store.commitTransaction(session,
                goodReceipt(), "none", UUID.randomUUID().toString(), VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, uncertain.outcome);
        writeAt(uncertain.generation == null
                ? root.resolve("canonical/generations/gen-" + uncertain.identity.candidateGenerationId)
                        .resolve(VirtualSdGenerationStore.IMAGE_RELATIVE)
                : uncertain.generation.imageFile, 49152, (byte) 77);

        VirtualSdGenerationStore.TransactionResult reconciled = store.reconcileStrict(uncertain.identity);
        assertEquals(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, reconciled.outcome);
        assertFalse(reconciled.cleanupResolved);
        assertTrue(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
        assertEquals(oldSha, sha(first.imageFile));
        assertThrows(IOException.class, store::current);
        assertThrows(IOException.class, store::currentForWrite);
    }

    @Test public void faultsBeforeGenerationAndHeadRenameProveTheOldHeadAndQuarantineCandidate() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<VirtualSdGenerationStore.FaultPoint> failAt = new AtomicReference<>();
        VirtualSdGenerationStore store = store(root.resolve("canonical"), "canonical", point -> {
            if (failAt.compareAndSet(point, null)) throw new IOException("injected " + point);
        });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 50));
        String originalSha = sha(first.imageFile);

        VirtualSdGenerationStore.WorkingSession beforeGeneration = store.openSession();
        writeAt(beforeGeneration.imageFile, 49152, (byte) 73);
        failAt.set(VirtualSdGenerationStore.FaultPoint.BEFORE_GENERATION_RENAME);
        VirtualSdGenerationStore.TransactionResult generationResult = store.commitTransaction(beforeGeneration,
                goodReceipt(), "none", UUID.randomUUID().toString(), VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, generationResult.outcome);
        assertTrue(generationResult.cleanupResolved);
        assertFalse(generationResult.headRenameAttempted);
        assertEquals(originalSha, sha(store.current().imageFile));

        VirtualSdGenerationStore.WorkingSession beforeHead = store.openSession();
        writeAt(beforeHead.imageFile, 65536, (byte) 74);
        failAt.set(VirtualSdGenerationStore.FaultPoint.BEFORE_HEAD_RENAME);
        VirtualSdGenerationStore.TransactionResult headResult = store.commitTransaction(beforeHead,
                goodReceipt(), "none", UUID.randomUUID().toString(), VirtualSdGenerationStore.NO_CANCELLATION);
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, headResult.outcome);
        assertTrue(headResult.cleanupResolved);
        assertFalse(headResult.headRenameAttempted);
        assertEquals(originalSha, sha(store.current().imageFile));
        assertEquals(1, countChildren(root.resolve("canonical/generations")));
        assertFalse(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
    }

    @Test public void cancellationBeforeBootstrapCopyLeavesNoAuthorityOrPendingMarker() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<VirtualSdGenerationStore.FaultPoint> cancelAt = new AtomicReference<>(
                VirtualSdGenerationStore.FaultPoint.BEFORE_BOOTSTRAP_IMAGE_COPY);
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root.resolve("canonical"), IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage, point -> {
                    if (cancelAt.compareAndSet(point, null)) cancelled.set(true);
                });
        VirtualSdGenerationStore.TransactionControl control = transactionControl(cancelled);
        VirtualSdGenerationStore.TransactionResult result = store.initializeTransaction(
                createImage(root.resolve("base.img"), 48), "none", "none", UUID.randomUUID().toString(), control);
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, result.outcome);
        assertTrue(result.cleanupResolved);
        assertNull(store.currentForWrite());
        assertFalse(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
        assertEquals(0, countChildren(root.resolve("canonical/generations")));
    }

    @Test public void cancellationDuringWorkingCopyQuarantinesPartialImageAndKeepsBaseline() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<VirtualSdGenerationStore.FaultPoint> cancelAt = new AtomicReference<>();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root.resolve("canonical"), IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage,
                point -> { if (cancelAt.compareAndSet(point, null)) cancelled.set(true); });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 52));
        String priorSha = sha(first.imageFile);
        cancelAt.set(VirtualSdGenerationStore.FaultPoint.BEFORE_WORKING_IMAGE_COPY);
        try {
            store.openSession(transactionControl(cancelled));
            fail("canceled working copy must not escape as a live session");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancellation"));
        }
        assertEquals(first.id, store.currentForWrite().id);
        assertEquals(priorSha, sha(store.currentForWrite().imageFile));
        assertEquals(0, countChildren(root.resolve("canonical/working")));
        assertEquals(1, countChildren(root.resolve("canonical/quarantine")));
    }

    @Test public void cancellationDuringCommitValidationDoesNotPublishOrReleaseOldAuthority() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<VirtualSdGenerationStore.FaultPoint> cancelAt = new AtomicReference<>();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root.resolve("canonical"), IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage,
                point -> { if (cancelAt.compareAndSet(point, null)) cancelled.set(true); });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 53));
        String priorSha = sha(first.imageFile);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 65536, (byte) 75);
        cancelAt.set(VirtualSdGenerationStore.FaultPoint.BEFORE_COMMIT_IMAGE_FORCE);
        VirtualSdGenerationStore.TransactionResult result = store.commitTransaction(session,
                goodReceipt(), "none", UUID.randomUUID().toString(), transactionControl(cancelled));
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, result.outcome);
        assertTrue(result.cleanupResolved);
        assertFalse(result.headRenameAttempted);
        assertEquals(first.id, store.currentForWrite().id);
        assertEquals(priorSha, sha(store.currentForWrite().imageFile));
        assertFalse(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
    }

    @Test public void cancellationAfterHeadStagingButBeforeAuthorizationLeavesOldHead() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        AtomicReference<Boolean> armed = new AtomicReference<>(false);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root.resolve("canonical"), IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage,
                point -> { if (armed.get() && point == VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_FILE_FORCE)
                    cancelled.set(true); });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 55));
        String priorSha = sha(first.imageFile);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 98304, (byte) 78);
        armed.set(true);
        VirtualSdGenerationStore.TransactionResult result = store.commitTransaction(session,
                goodReceipt(), "none", UUID.randomUUID().toString(), transactionControl(cancelled));
        assertEquals(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, result.outcome);
        assertTrue(result.cleanupResolved);
        assertFalse(result.headRenameAttempted);
        assertEquals(first.id, store.currentForWrite().id);
        assertEquals(priorSha, sha(store.currentForWrite().imageFile));
        assertFalse(Files.exists(root.resolve("canonical/PENDING_TRANSACTION.vsd")));
    }

    @Test public void cancellationAfterAuthorizationDoesNotReverseVerifiedCommit() throws Exception {
        Path root = temporary.newFolder().toPath();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        AtomicReference<Boolean> armed = new AtomicReference<>(false);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root.resolve("canonical"), IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreTest::syncDirectory,
                VirtualSdGenerationStoreTest::validateImage,
                point -> { if (armed.get() && point == VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME)
                    cancelled.set(true); });
        VirtualSdGenerationStore.Generation first = store.initialize(createImage(root.resolve("base.img"), 56));
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        writeAt(session.imageFile, 114688, (byte) 79);
        armed.set(true);
        VirtualSdGenerationStore.TransactionResult result = store.commitTransaction(session,
                goodReceipt(), "none", UUID.randomUUID().toString(), transactionControl(cancelled));
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED, result.outcome);
        assertTrue(result.cleanupResolved);
        assertTrue(result.headRenameAttempted);
        assertTrue(result.directorySyncConfirmed);
        assertEquals(first.id, result.generation.parentGenerationId);
        assertEquals(result.generation.id, store.currentForWrite().id);
    }

    @Test public void coldProcessReconcilesPersistedExactCandidateAndParent() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore firstStore = store(storeRoot, "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = firstStore.initialize(createImage(root.resolve("base.img"), 49));
        String firstSha = sha(first.imageFile);
        String candidateId = UUID.randomUUID().toString();
        Process child = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                VirtualSdGenerationStoreCrashProbe.class.getName(), storeRoot.toString(), candidateId)
                .redirectErrorStream(true).start();
        byte[] output;
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                fail("crash probe did not reach its cold-recovery boundary");
            }
            output = child.getInputStream().readAllBytes();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            throw new IOException("Interrupted during cold-recovery probe", interrupted);
        }
        assertEquals(new String(output, java.nio.charset.StandardCharsets.UTF_8), 0, child.exitValue());
        assertTrue(Files.exists(storeRoot.resolve("PENDING_TRANSACTION.vsd")));

        VirtualSdGenerationStore coldStore = store(storeRoot, "canonical", ignored -> { });
        VirtualSdGenerationStore.TransactionResult recovered = coldStore.reconcilePendingStrict();
        assertEquals(VirtualSdGenerationStore.Outcome.COMMITTED, recovered.outcome);
        assertTrue(recovered.cleanupResolved);
        assertEquals(candidateId, recovered.identity.candidateGenerationId);
        assertEquals(first.id, recovered.identity.expectedHeadGenerationId);
        assertEquals(first.id, recovered.generation.parentGenerationId);
        assertEquals(2, recovered.generation.sequence);
        assertEquals(firstSha, sha(first.imageFile));
        assertEquals(candidateId, coldStore.currentForWrite().id);
        assertFalse(Files.exists(storeRoot.resolve("PENDING_TRANSACTION.vsd")));
    }

    @Test public void freshReconcileWithoutPendingMarkerDoesNotClaimNotCommitted() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path storeRoot = root.resolve("canonical");
        VirtualSdGenerationStore writer = store(storeRoot, "canonical", ignored -> { });
        VirtualSdGenerationStore.Generation first = writer.initialize(createImage(root.resolve("base.img"), 50));
        VirtualSdGenerationStore.WorkingSession session = writer.openSession();
        writeAt(session.imageFile, 65536, (byte) 87);
        VirtualSdGenerationStore.Generation committed = writer.commit(session, goodReceipt());
        assertEquals(2, committed.sequence);
        assertFalse(Files.exists(storeRoot.resolve("PENDING_TRANSACTION.vsd")));

        VirtualSdGenerationStore freshProcessView = store(storeRoot, "canonical", ignored -> { });
        VirtualSdGenerationStore.TransactionResult result = freshProcessView.reconcilePendingStrict();
        assertEquals("absence of a marker is not proof that a transaction did not publish",
                VirtualSdGenerationStore.Outcome.NO_PENDING, result.outcome);
        assertNull(result.identity);
        assertNull(result.generation);
        assertTrue(result.cleanupResolved);
        assertEquals(first.id, committed.parentGenerationId);
        assertEquals(committed.id, freshProcessView.currentForWrite().id);
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
        byte[] corruptedHead = Files.readAllBytes(root.resolve("canonical/HEAD.0"));
        assertThrows("writer reads must not use ordinary fallback", IOException.class,
                store::currentForWrite);
        assertArrayEquals("strict rejection must not rewrite either authority slot", corruptedHead,
                Files.readAllBytes(root.resolve("canonical/HEAD.0")));
        assertEquals(first.id, store.current().id);
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

    private static VirtualSdGenerationStore.TransactionControl transactionControl(
            AtomicReference<Boolean> cancelled) {
        return new VirtualSdGenerationStore.TransactionControl() {
            @Override public void checkCancellation() throws IOException {
                if (cancelled.get()) throw new IOException("test cancellation");
            }
            @Override public boolean authorizePublication(String candidateGenerationId) {
                return !cancelled.get();
            }
            @Override public void publicationResolved(String candidateGenerationId,
                                                      VirtualSdGenerationStore.Outcome outcome) { }
        };
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

    private static String javaExecutable() {
        return java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }
}
