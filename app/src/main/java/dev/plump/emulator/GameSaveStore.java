package dev.plump.emulator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Two independently checksummed generations of *native cartridge SRAM*.
 * No save states, no file format conversion, and no destructive fallback.
 * Each write targets the older generation and is published only after fsync.
 */
public final class GameSaveStore {
    private static final int MAGIC = 0x504C5356; // PLSV
    private static final int FORMAT = 1;
    private static final int MAX_LENGTH = 1024 * 1024;

    public interface DirectorySync { void sync(File directory) throws IOException; }

    public static final class Recovery {
        public final byte[] bytes;
        public final String warning;
        public final long generation;
        Recovery(byte[] bytes, String warning, long generation) {
            this.bytes = bytes;
            this.warning = warning;
            this.generation = generation;
        }
    }

    private static final class Record {
        final long generation;
        final byte[] bytes;
        Record(long generation, byte[] bytes) { this.generation = generation; this.bytes = bytes; }
    }

    private final File directory;
    private final DirectorySync directorySync;

    public GameSaveStore(File directory, DirectorySync directorySync) throws IOException {
        this.directory = directory;
        this.directorySync = directorySync;
        Files.createDirectories(directory.toPath());
    }

    private static void validateGameId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Game identity must be a SHA-256 hex digest");
    }

    private File slot(String gameId, int index) {
        return new File(directory, gameId + "." + index + ".sav");
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }

    private static byte[] gameDigest(String gameId) {
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; ++i)
            out[i] = (byte) Integer.parseInt(gameId.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static Record readRecord(File file, String gameId) {
        if (!file.isFile() || file.length() > MAX_LENGTH + 128L) return null;
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (stream.readInt() != MAGIC || stream.readInt() != FORMAT) return null;
            long generation = stream.readLong();
            int length = stream.readInt();
            if (generation <= 0 || length <= 0 || length > MAX_LENGTH) return null;
            byte[] embeddedIdentity = new byte[32];
            stream.readFully(embeddedIdentity);
            if (!MessageDigest.isEqual(embeddedIdentity, gameDigest(gameId))) return null;
            byte[] expected = new byte[32];
            stream.readFully(expected);
            byte[] payload = new byte[length];
            stream.readFully(payload);
            if (stream.read() != -1 || !MessageDigest.isEqual(expected, sha256(payload))) return null;
            return new Record(generation, payload);
        } catch (IOException exception) {
            return null; // Invalid/truncated generation; never copy it into emulator memory.
        }
    }

    public synchronized Recovery load(String gameId) {
        validateGameId(gameId);
        File a = slot(gameId, 0);
        File b = slot(gameId, 1);
        Record first = readRecord(a, gameId);
        Record second = readRecord(b, gameId);
        Record selected = first == null ? second : second == null ? first :
                (first.generation >= second.generation ? first : second);
        boolean rejected = (a.exists() && first == null) || (b.exists() && second == null);
        String warning = rejected ? "A damaged native-save generation was rejected; an older valid copy was retained." : null;
        return new Recovery(selected == null ? null : Arrays.copyOf(selected.bytes, selected.bytes.length),
                warning, selected == null ? 0 : selected.generation);
    }

    public synchronized void write(String gameId, byte[] bytes) throws IOException {
        validateGameId(gameId);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_LENGTH)
            throw new IOException("Core returned invalid SRAM length");

        Recovery previous = load(gameId);
        if (previous.bytes == null && (slot(gameId, 0).exists() || slot(gameId, 1).exists()))
            throw new IOException("Both existing native-save slots are invalid; refusing to overwrite recovery evidence");
        if (previous.generation == Long.MAX_VALUE)
            throw new IOException("Save generation number exhausted");

        long generation = previous.generation + 1;
        File target = slot(gameId, (int) (generation % 2));
        Path temp = Files.createTempFile(directory.toPath(), gameId + ".", ".pending");
        try {
            try (FileOutputStream raw = new FileOutputStream(temp.toFile());
                 DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(raw))) {
                stream.writeInt(MAGIC);
                stream.writeInt(FORMAT);
                stream.writeLong(generation);
                stream.writeInt(bytes.length);
                stream.write(gameDigest(gameId));
                stream.write(sha256(bytes));
                stream.write(bytes);
                stream.flush();
                // BufferedOutputStream flushes before fd sync; no success before durability.
                raw.getFD().sync();
            }
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic native-save publication is unsupported", exception);
            }
            directorySync.sync(directory);
        } finally {
            // Only a uniquely owned new temp path, never a previous save generation.
            Files.deleteIfExists(temp);
        }
    }
}
