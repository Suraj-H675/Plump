package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Host JVM filesystem tests of native-save generation and corruption handling. */
public class GameSaveStoreTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final int PLSV_HEADER_LENGTH = 84;

    @Rule public TemporaryFolder root = new TemporaryFolder();

    private static byte[] data(int seed) {
        byte[] result = new byte[32768];
        Arrays.fill(result, (byte) seed);
        return result;
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static void writeV1Record(File file, String gameId, long generation, byte[] payload)
            throws Exception {
        byte[] identity = new byte[32];
        for (int index = 0; index < identity.length; index++)
            identity[index] = (byte) Integer.parseInt(gameId.substring(index * 2, index * 2 + 2), 16);
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeInt(0x504C5356);
            output.writeInt(1);
            output.writeLong(generation);
            output.writeInt(payload.length);
            output.write(identity);
            output.write(sha256(payload));
            output.write(payload);
        }
        assertEquals(PLSV_HEADER_LENGTH + payload.length, file.length());
    }

    private static void corrupt(File file) throws IOException {
        try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) {
            long index = handle.length() - 1;
            handle.seek(index);
            int old = handle.read();
            handle.seek(index);
            handle.write(old ^ 0x7F);
        }
    }

    private static File slot(File dir, int index) {
        return new File(dir, A + "." + index + ".sav");
    }

    private static GameSaveStore.SaveFailure writeExpectingFailure(GameSaveStore store, byte[] bytes)
            throws Exception {
        try {
            store.write(A, bytes);
            fail("failed save operation returned success");
        } catch (GameSaveStore.SaveFailure failure) {
            return failure;
        }
        throw new AssertionError("unreachable");
    }

    @Test public void writesTwoGenerationsAndFallsBackFromCorruptNewest() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        byte[] first = data(11), second = data(22);
        store.write(A, first);
        store.write(A, second);
        assertArrayEquals(second, store.load(A).bytes);
        assertEquals(2, store.load(A).generation);

        corrupt(slot(dir, 0)); // generation 2
        GameSaveStore.Recovery fallback = store.load(A);
        assertEquals(1, fallback.generation);
        assertArrayEquals(first, fallback.bytes);
        assertNotNull(fallback.warning);
        store.write(A, data(33));
        assertArrayEquals(data(33), store.load(A).bytes);
        assertTrue(slot(dir, 1).exists());
    }

    @Test public void refusesToOverwriteCorruptRecoveryEvidence() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        writeV1Record(slot(dir, 0), A, 1, data(1));
        writeV1Record(slot(dir, 1), A, 2, data(2));
        corrupt(slot(dir, 0));
        corrupt(slot(dir, 1));
        byte[] first = Files.readAllBytes(slot(dir, 0).toPath());
        byte[] second = Files.readAllBytes(slot(dir, 1).toPath());

        assertEquals(GameSaveStore.Status.CORRUPT, store.load(A).status);
        assertEquals(GameSaveStore.FailureReason.RECOVERY_REQUIRED,
                writeExpectingFailure(store, data(3)).reason);
        assertArrayEquals(first, Files.readAllBytes(slot(dir, 0).toPath()));
        assertArrayEquals(second, Files.readAllBytes(slot(dir, 1).toPath()));
    }

    @Test public void failedDirectorySyncNeverReturnsDurableSuccessOrReplacesOlderCopy() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        File oldSlot = slot(dir, 1);
        byte[] oldGeneration = Files.readAllBytes(oldSlot.toPath());
        AtomicInteger calls = new AtomicInteger();
        GameSaveStore failing = new GameSaveStore(dir, ignored -> {
            calls.incrementAndGet();
            throw new IOException("injected directory fsync error");
        });
        GameSaveStore.SaveFailure failure = writeExpectingFailure(failing, data(2));

        assertEquals(GameSaveStore.FailureReason.DURABILITY_SYNC, failure.reason);
        assertEquals(1, calls.get());
        assertTrue(oldSlot.exists());
        assertArrayEquals(oldGeneration, Files.readAllBytes(oldSlot.toPath()));
    }

    @Test public void partialInterruptedWriteLeavesThePreviousGenerationUsable() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        byte[] oldGeneration = Files.readAllBytes(slot(dir, 1).toPath());
        GameSaveStore interrupted = new GameSaveStore(dir, ignored -> {}, point -> {
            if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE)
                throw new InterruptedIOException("injected process interruption");
        });

        assertEquals(GameSaveStore.FailureReason.INTERRUPTED,
                writeExpectingFailure(interrupted, data(2)).reason);
        assertArrayEquals(oldGeneration, Files.readAllBytes(slot(dir, 1).toPath()));
        assertFalse(slot(dir, 0).exists());
        assertArrayEquals(data(1), good.load(A).bytes);
    }

    @Test public void enospcAndEioDuringPartialWritesAreTypedAndPreserveGoodData() throws Exception {
        for (String injectedError : new String[]{"ENOSPC: No space left on device", "EIO: Input/output error"}) {
            File dir = root.newFolder();
            GameSaveStore good = new GameSaveStore(dir, ignored -> {});
            good.write(A, data(1));
            byte[] oldGeneration = Files.readAllBytes(slot(dir, 1).toPath());
            GameSaveStore failing = new GameSaveStore(dir, ignored -> {}, point -> {
                if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE)
                    throw new FileSystemException("save.pending", null, injectedError);
            });

            GameSaveStore.FailureReason expected = injectedError.startsWith("ENOSPC") ?
                    GameSaveStore.FailureReason.STORAGE_FULL : GameSaveStore.FailureReason.STORAGE_IO;
            assertEquals(expected, writeExpectingFailure(failing, data(2)).reason);
            assertArrayEquals(oldGeneration, Files.readAllBytes(slot(dir, 1).toPath()));
            assertArrayEquals(data(1), good.load(A).bytes);
        }
    }

    @Test public void fileSyncFailureDoesNotPublishTheCandidate() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        byte[] oldGeneration = Files.readAllBytes(slot(dir, 1).toPath());
        GameSaveStore failing = new GameSaveStore(dir, ignored -> {}, point -> {
            if (point == GameSaveStore.WritePoint.RECORD_FILE_SYNC)
                throw new IOException("injected file fsync error");
        });

        assertEquals(GameSaveStore.FailureReason.DURABILITY_SYNC,
                writeExpectingFailure(failing, data(2)).reason);
        assertArrayEquals(oldGeneration, Files.readAllBytes(slot(dir, 1).toPath()));
        assertFalse(slot(dir, 0).exists());
        assertArrayEquals(data(1), good.load(A).bytes);
    }

    @Test public void failureBeforeAtomicPublicationLeavesCandidateUnpublished() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        byte[] oldGeneration = Files.readAllBytes(slot(dir, 1).toPath());
        GameSaveStore failing = new GameSaveStore(dir, ignored -> {}, point -> {
            if (point == GameSaveStore.WritePoint.BEFORE_RECORD_MOVE)
                throw new IOException("injected pre-publication interruption");
        });

        assertEquals(GameSaveStore.FailureReason.ATOMIC_PUBLICATION,
                writeExpectingFailure(failing, data(2)).reason);
        assertArrayEquals(oldGeneration, Files.readAllBytes(slot(dir, 1).toPath()));
        assertFalse(slot(dir, 0).exists());
    }

    @Test public void failureAfterAtomicPublicationIsStillReportedAsFailureAndKeepsOldCopy() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        byte[] oldGeneration = Files.readAllBytes(slot(dir, 1).toPath());
        GameSaveStore failing = new GameSaveStore(dir, ignored -> {}, point -> {
            if (point == GameSaveStore.WritePoint.AFTER_RECORD_MOVE)
                throw new IOException("injected post-publication interruption");
        });

        assertEquals(GameSaveStore.FailureReason.ATOMIC_PUBLICATION,
                writeExpectingFailure(failing, data(2)).reason);
        assertArrayEquals(oldGeneration, Files.readAllBytes(slot(dir, 1).toPath()));
        assertArrayEquals(data(2), good.load(A).bytes);
    }

    @Test public void failedReceiptDirectorySyncCannotReturnDurableSuccess() throws Exception {
        File dir = root.newFolder();
        AtomicInteger calls = new AtomicInteger();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {
            if (calls.incrementAndGet() == 2) throw new IOException("injected receipt directory fsync error");
        });

        assertEquals(GameSaveStore.FailureReason.DURABILITY_SYNC,
                writeExpectingFailure(store, data(1)).reason);
        assertEquals(2, calls.get());
        assertArrayEquals(data(1), store.load(A).bytes);
    }

    @Test public void interruptedReceiptPublicationNeverClaimsSuccessAndKeepsValidSave() throws Exception {
        for (GameSaveStore.WritePoint failedAt : new GameSaveStore.WritePoint[]{
                GameSaveStore.WritePoint.RECEIPT_PARTIAL_WRITE,
                GameSaveStore.WritePoint.RECEIPT_FILE_SYNC,
                GameSaveStore.WritePoint.BEFORE_RECEIPT_MOVE,
                GameSaveStore.WritePoint.AFTER_RECEIPT_MOVE}) {
            File dir = root.newFolder();
            GameSaveStore faulty = new GameSaveStore(dir, ignored -> {}, point -> {
                if (point == failedAt) throw new IOException("injected receipt interruption");
            });
            assertEquals(failedAt == GameSaveStore.WritePoint.RECEIPT_FILE_SYNC ?
                    GameSaveStore.FailureReason.DURABILITY_SYNC :
                    failedAt == GameSaveStore.WritePoint.RECEIPT_PARTIAL_WRITE ?
                    GameSaveStore.FailureReason.STORAGE_IO :
                    GameSaveStore.FailureReason.ATOMIC_PUBLICATION,
                    writeExpectingFailure(faulty, data(61)).reason);
            assertArrayEquals(data(61), faulty.load(A).bytes);
            assertEquals(1, faulty.load(A).generation);
            // The first save is physically present after record publication,
            // but the operation failed and must not claim durable completion.
            // A later independently verified write may repair the receipt.
            GameSaveStore healthy = new GameSaveStore(dir, ignored -> {});
            healthy.write(A, data(62));
            assertArrayEquals(data(62), healthy.load(A).bytes);
            assertTrue(new File(dir, A + ".history").isFile());
        }
    }

    @Test public void noRecordsWithoutAValidReceiptRemainUnknownButReceiptProvesMissingHistory() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        assertEquals(GameSaveStore.Status.UNKNOWN, store.load(A).status);

        store.write(A, data(4));
        assertEquals(GameSaveStore.Status.AVAILABLE, store.load(A).status);
        assertTrue(slot(dir, 1).exists());
        assertTrue(new File(dir, A + ".history").isFile());
        assertTrue(slot(dir, 1).delete());

        assertEquals(GameSaveStore.Status.KNOWN_MISSING, store.load(A).status);
        assertEquals(GameSaveStore.FailureReason.KNOWN_HISTORY_MISSING,
                writeExpectingFailure(store, data(5)).reason);
    }

    @Test public void invalidReceiptDoesNotInventNeverSavedOrKnownMissingStatus() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        Files.write(new File(dir, A + ".history").toPath(), new byte[]{1, 2, 3});

        GameSaveStore.Recovery recovery = store.load(A);
        assertEquals(GameSaveStore.Status.UNKNOWN, recovery.status);
        assertNotNull(recovery.warning);
        assertEquals(GameSaveStore.FailureReason.RECOVERY_REQUIRED,
                writeExpectingFailure(store, data(1)).reason);
    }

    @Test public void legacyV1RecordRemainsByteForByteReadable() throws Exception {
        File dir = root.newFolder();
        File legacy = slot(dir, 0);
        byte[] payload = data(17);
        writeV1Record(legacy, A, 1, payload);
        byte[] original = Files.readAllBytes(legacy.toPath());
        assertEquals(PLSV_HEADER_LENGTH + payload.length, original.length);

        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        GameSaveStore.Recovery recovery = store.load(A);
        assertEquals(GameSaveStore.Status.AVAILABLE, recovery.status);
        assertEquals(1, recovery.generation);
        assertArrayEquals(payload, recovery.bytes);
        assertArrayEquals(original, Files.readAllBytes(legacy.toPath()));
        assertTrue(legacy.delete());
        assertEquals(GameSaveStore.Status.UNKNOWN, store.load(A).status);
    }

    @Test public void slotSelectionUsesTheActualGoodSlotWhenGenerationParityDiffers() throws Exception {
        File dir = root.newFolder();
        byte[] previous = data(21);
        writeV1Record(slot(dir, 0), A, 1, previous); // generation 1 is deliberately in slot 0
        byte[] original = Files.readAllBytes(slot(dir, 0).toPath());
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});

        store.write(A, data(22));

        assertArrayEquals(original, Files.readAllBytes(slot(dir, 0).toPath()));
        assertArrayEquals(data(22), store.load(A).bytes);
        assertEquals(2, store.load(A).generation);
    }

    @Test public void generationExhaustionDoesNotWrapOrReplaceTheLastGoodRecord() throws Exception {
        File dir = root.newFolder();
        File lastGood = slot(dir, 0);
        writeV1Record(lastGood, A, Long.MAX_VALUE, data(31));
        byte[] original = Files.readAllBytes(lastGood.toPath());
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});

        assertEquals(GameSaveStore.FailureReason.GENERATION_EXHAUSTED,
                writeExpectingFailure(store, data(32)).reason);
        assertArrayEquals(original, Files.readAllBytes(lastGood.toPath()));
        assertEquals(Long.MAX_VALUE, store.load(A).generation);
    }

    @Test public void generationRotationKeepsImmediatePreviousValidGeneration() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        store.write(A, data(1));
        store.write(A, data(2));
        store.write(A, data(3));
        assertEquals(3, store.load(A).generation);
        assertArrayEquals(data(3), store.load(A).bytes);

        corrupt(slot(dir, 1)); // newest, generation 3
        assertEquals(2, store.load(A).generation);
        assertArrayEquals(data(2), store.load(A).bytes);
        assertNotNull(store.load(A).warning);
    }

    @Test public void truncationAndChecksumDamageRetainTheOlderGeneration() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        store.write(A, data(7));
        store.write(A, data(8));
        try (RandomAccessFile handle = new RandomAccessFile(slot(dir, 0), "rw")) {
            handle.setLength(PLSV_HEADER_LENGTH + 1L);
        }
        GameSaveStore.Recovery truncated = store.load(A);
        assertEquals(GameSaveStore.Status.AVAILABLE, truncated.status);
        assertArrayEquals(data(7), truncated.bytes);
        assertEquals(1, truncated.generation);
        assertNotNull(truncated.warning);

        corrupt(slot(dir, 1));
        assertEquals(GameSaveStore.Status.CORRUPT, store.load(A).status);
        assertEquals(GameSaveStore.FailureReason.RECOVERY_REQUIRED,
                writeExpectingFailure(store, data(9)).reason);
    }

    @Test public void rejectsUnsafeIdentityAndInvalidLengths() throws Exception {
        GameSaveStore store = new GameSaveStore(root.newFolder(), ignored -> {});
        try { store.load("../../outside"); fail("path traversal identity"); }
        catch (IllegalArgumentException expected) { /* expected */ }
        assertEquals(GameSaveStore.FailureReason.INVALID_LENGTH,
                writeExpectingFailure(store, new byte[0]).reason);
        assertEquals(GameSaveStore.FailureReason.INVALID_LENGTH,
                writeExpectingFailure(store, new byte[1024 * 1024 + 1]).reason);
    }

    @Test public void rejectsRecordIdentityMismatchAndInvalidDeclaredLength() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        writeV1Record(slot(dir, 0), B, 1, data(1));
        assertEquals(GameSaveStore.Status.CORRUPT, store.load(A).status);
        assertEquals(GameSaveStore.FailureReason.RECOVERY_REQUIRED,
                writeExpectingFailure(store, data(2)).reason);

        assertTrue(slot(dir, 0).delete());
        writeV1Record(slot(dir, 0), A, 1, data(3));
        try (RandomAccessFile handle = new RandomAccessFile(slot(dir, 0), "rw")) {
            handle.seek(16); // PLSV v1 payload length follows magic, format, and generation.
            handle.writeInt(0);
        }
        assertEquals(GameSaveStore.Status.CORRUPT, store.load(A).status);
        assertEquals(GameSaveStore.FailureReason.RECOVERY_REQUIRED,
                writeExpectingFailure(store, data(4)).reason);
    }

    @Test public void separateIdentitiesNeverShareSlots() throws Exception {
        GameSaveStore store = new GameSaveStore(root.newFolder(), ignored -> {});
        store.write(A, data(1));
        store.write(B, data(2));
        assertArrayEquals(data(1), store.load(A).bytes);
        assertArrayEquals(data(2), store.load(B).bytes);
    }
}
