package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Host JVM filesystem tests of native-save generation and corruption handling. */
public class GameSaveStoreTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Rule public TemporaryFolder root = new TemporaryFolder();

    private static byte[] data(int seed) {
        byte[] result = new byte[32768];
        Arrays.fill(result, (byte) seed);
        return result;
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

    @Test public void writesTwoGenerationsAndFallsBackFromCorruptNewest() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        byte[] first = data(11), second = data(22);
        store.write(A, first);
        store.write(A, second);
        assertArrayEquals(second, store.load(A).bytes);
        assertEquals(2, store.load(A).generation);

        corrupt(new File(dir, A + ".0.sav")); // generation 2
        GameSaveStore.Recovery fallback = store.load(A);
        assertEquals(1, fallback.generation);
        assertArrayEquals(first, fallback.bytes);
        assertNotNull(fallback.warning);
        store.write(A, data(33));
        assertArrayEquals(data(33), store.load(A).bytes);
        assertTrue(new File(dir, A + ".1.sav").exists());
    }

    @Test public void refusesToOverwriteTwoInvalidGenerations() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        try (FileOutputStream out = new FileOutputStream(new File(dir, A + ".0.sav"))) {
            out.write(new byte[]{1, 2, 3});
        }
        assertNull(store.load(A).bytes);
        assertNotNull(store.load(A).warning);
        try {
            store.write(A, data(1));
            fail("must never overwrite the only corrupt candidate without recovery choice");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalid"));
        }
    }

    @Test public void failedDurabilitySyncNeverReturnsSuccessAndPreservesOlderCopy() throws Exception {
        File dir = root.newFolder();
        GameSaveStore good = new GameSaveStore(dir, ignored -> {});
        good.write(A, data(1));
        File oldSlot = new File(dir, A + ".1.sav");
        byte[] oldGeneration = Files.readAllBytes(oldSlot.toPath());
        AtomicInteger calls = new AtomicInteger();
        GameSaveStore failing = new GameSaveStore(dir, ignored -> {
            calls.incrementAndGet();
            throw new IOException("injected directory fsync error");
        });
        try {
            failing.write(A, data(2));
            fail("failed directory fsync may not be reported as a durable save");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("injected"));
        }
        assertEquals(1, calls.get());
        // Even if the successor was renamed before the injected failure, the older
        // independent generation must remain physically present and recoverable.
        assertTrue(oldSlot.exists());
        assertArrayEquals(oldGeneration, Files.readAllBytes(oldSlot.toPath()));
    }

    @Test public void separateIdentitiesNeverShareSlots() throws Exception {
        GameSaveStore store = new GameSaveStore(root.newFolder(), ignored -> {});
        store.write(A, data(1));
        store.write(B, data(2));
        assertArrayEquals(data(1), store.load(A).bytes);
        assertArrayEquals(data(2), store.load(B).bytes);
    }

    @Test public void thirdGenerationRetainsImmediatePreviousCopy() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        store.write(A, data(1));
        store.write(A, data(2));
        store.write(A, data(3));
        assertEquals(3, store.load(A).generation);
        assertArrayEquals(data(3), store.load(A).bytes);

        corrupt(new File(dir, A + ".1.sav")); // newest, generation 3
        assertEquals(2, store.load(A).generation);
        assertArrayEquals(data(2), store.load(A).bytes);
        assertNotNull(store.load(A).warning);
    }

    @Test public void truncationOfNewestCopyRetainsOlderAndPreventsFalseSuccess() throws Exception {
        File dir = root.newFolder();
        GameSaveStore store = new GameSaveStore(dir, ignored -> {});
        store.write(A, data(7));
        store.write(A, data(8));
        try (RandomAccessFile handle = new RandomAccessFile(new File(dir, A + ".0.sav"), "rw")) {
            handle.setLength(18);
        }
        GameSaveStore.Recovery older = store.load(A);
        assertArrayEquals(data(7), older.bytes);
        assertEquals(1, older.generation);
        assertNotNull(older.warning);
    }

    @Test public void rejectsUnsafeIdentityAndInvalidBytes() throws Exception {
        GameSaveStore store = new GameSaveStore(root.newFolder(), ignored -> {});
        try { store.load("../../outside"); fail("path traversal identity"); }
        catch (IllegalArgumentException expected) { /* expected */ }
        try { store.write(A, new byte[0]); fail("empty SRAM"); }
        catch (IOException expected) { /* expected */ }
    }
}
