package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class SessionReceiptStoreTest {
    private static final String GAME_A = repeat('a');
    private static final String GAME_B = repeat('b');

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void absentAndCorruptAreUnknownRatherThanClean() throws Exception {
        Path root = temporary.newFolder().toPath();
        SessionReceiptStore store = new SessionReceiptStore(root);
        assertEquals(SessionReceiptStore.State.UNKNOWN,
                store.read(SessionReceiptStore.Console.GBA, GAME_A).state);
        assertFalse(store.read(SessionReceiptStore.Console.GBA, GAME_A).corrupt);

        Files.createDirectories(root);
        Files.write(root.resolve("gba-" + GAME_A + ".0"), new byte[] {1, 2, 3});
        SessionReceiptStore.ReadResult corrupt = store.read(SessionReceiptStore.Console.GBA, GAME_A);
        assertEquals(SessionReceiptStore.State.UNKNOWN, corrupt.state);
        assertTrue(corrupt.corrupt);
    }

    @Test public void cleanStatesInterruptedStateAndIdentityAreSeparate() throws Exception {
        Path root = temporary.newFolder().toPath();
        SessionReceiptStore store = new SessionReceiptStore(root);
        assertEquals(SessionReceiptStore.State.PAUSED,
                store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.PAUSED).state);
        assertEquals(SessionReceiptStore.State.RUNNING,
                store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.RUNNING).state);
        assertEquals(SessionReceiptStore.State.CLOSED,
                store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.CLOSED).state);
        assertEquals(SessionReceiptStore.State.CLOSED,
                store.read(SessionReceiptStore.Console.GBA, GAME_A).state);
        assertEquals(SessionReceiptStore.State.UNKNOWN,
                store.read(SessionReceiptStore.Console.DS, GAME_A).state);
        assertEquals(SessionReceiptStore.State.UNKNOWN,
                store.read(SessionReceiptStore.Console.GBA, GAME_B).state);

        store.publish(SessionReceiptStore.Console.DS, GAME_B, SessionReceiptStore.State.RUNNING);
        SessionReceiptStore afterProcessRestart = new SessionReceiptStore(root);
        assertEquals(SessionReceiptStore.State.RUNNING,
                afterProcessRestart.read(SessionReceiptStore.Console.DS, GAME_B).state);
    }

    @Test public void newestCorruptGenerationFallsBackToOlderValidSlot() throws Exception {
        Path root = temporary.newFolder().toPath();
        SessionReceiptStore store = new SessionReceiptStore(root);
        store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.PAUSED);
        store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.RUNNING);
        Path newest = Arrays.stream(new Path[] {root.resolve("gba-" + GAME_A + ".0"),
                root.resolve("gba-" + GAME_A + ".1")})
                .filter(Files::exists)
                .max((left, right) -> Long.compare(generation(left), generation(right)))
                .orElseThrow();
        byte[] contents = Files.readAllBytes(newest);
        contents[contents.length - 3] ^= 1;
        Files.write(newest, contents);

        SessionReceiptStore.ReadResult recovered = store.read(SessionReceiptStore.Console.GBA, GAME_A);
        assertEquals(SessionReceiptStore.State.PAUSED, recovered.state);
        assertTrue(recovered.corrupt);
    }

    @Test public void allAtomicPublicationBoundariesFailWithoutReportingSuccess() throws Exception {
        for (SessionReceiptStore.Failpoint boundary : SessionReceiptStore.Failpoint.values()) {
            Path root = temporary.newFolder().toPath();
            SessionReceiptStore baseline = new SessionReceiptStore(root);
            baseline.publish(SessionReceiptStore.Console.DS, GAME_A, SessionReceiptStore.State.PAUSED);
            AtomicReference<SessionReceiptStore.Failpoint> fail = new AtomicReference<>(boundary);
            SessionReceiptStore failing = new SessionReceiptStore(root, point -> {
                if (fail.compareAndSet(point, null)) throw new IOException("injected " + point);
            });
            try {
                failing.publish(SessionReceiptStore.Console.DS, GAME_A, SessionReceiptStore.State.RUNNING);
                fail("publication passed injected failpoint " + boundary);
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("injected"));
            }
            assertNull("failpoint must be reached: " + boundary, fail.get());
            SessionReceiptStore.ReadResult read = new SessionReceiptStore(root)
                    .read(SessionReceiptStore.Console.DS, GAME_A);
            assertTrue("visible generation is either prior or unconfirmed candidate at " + boundary,
                    read.state == SessionReceiptStore.State.PAUSED || read.state == SessionReceiptStore.State.RUNNING);
            assertNotNull(read.receipt);
            assertTrue("failed publication must preserve a valid checksummed generation", read.receipt.generation >= 1);
        }
    }

    @Test public void diskFullAndIoFailuresAreSurfaced() throws Exception {
        Path root = temporary.newFolder().toPath();
        SessionReceiptStore baseline = new SessionReceiptStore(root);
        baseline.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.PAUSED);
        SessionReceiptStore store = new SessionReceiptStore(root, point -> {
            if (point == SessionReceiptStore.Failpoint.DURING_TEMP_WRITE)
                throw new IOException("ENOSPC: disk full");
        });
        IOException failure = assertThrows(IOException.class,
                () -> store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.RUNNING));
        assertTrue(failure.getMessage().contains("ENOSPC"));
        assertEquals(SessionReceiptStore.State.PAUSED,
                new SessionReceiptStore(root).read(SessionReceiptStore.Console.GBA, GAME_A).state);
    }

    @Test public void generationExhaustionPreservesLastValidReceiptAndFailsClosed() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path old = root.resolve("gba-" + GAME_A + ".0");
        String body = "PLUMP_SESSION_RECEIPT|1|GBA|" + GAME_A + "|PAUSED|" + Long.MAX_VALUE;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        byte[] original = (body + "|" + hash + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(old, original);
        SessionReceiptStore store = new SessionReceiptStore(root);
        assertEquals(SessionReceiptStore.State.PAUSED, store.read(SessionReceiptStore.Console.GBA, GAME_A).state);
        assertThrows(IOException.class,
                () -> store.publish(SessionReceiptStore.Console.GBA, GAME_A, SessionReceiptStore.State.RUNNING));
        assertArrayEquals(original, Files.readAllBytes(old));
        assertFalse(Files.exists(root.resolve("gba-" + GAME_A + ".1")));
    }

    @Test public void oversizedReceiptIsCorruptAndCannotBeUsedAsRecoveryEvidence() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.write(root.resolve("gba-" + GAME_A + ".0"), new byte[4096]);
        SessionReceiptStore.ReadResult read = new SessionReceiptStore(root)
                .read(SessionReceiptStore.Console.GBA, GAME_A);
        assertEquals(SessionReceiptStore.State.UNKNOWN, read.state);
        assertTrue(read.corrupt);
    }

    private static long generation(Path path) {
        try {
            String[] fields = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
            return Long.parseLong(fields[5]);
        } catch (Exception invalid) { return -1; }
    }

    private static String repeat(char value) {
        char[] bytes = new char[64];
        Arrays.fill(bytes, value);
        return new String(bytes);
    }
}
