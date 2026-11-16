package dev.plump.emulator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Two independently checksummed generations of *native cartridge SRAM*.
 * No save states, no file format conversion, and no destructive fallback.
 * Each write targets the older generation and is published only after fsync.
 */
public final class GameSaveStore {
    private static final int MAGIC = 0x504C5356; // PLSV
    private static final int FORMAT = 1;
    private static final int HEADER_LENGTH = 84;
    private static final int MAX_LENGTH = 1024 * 1024;
    private static final int HISTORY_MAGIC = 0x504C4852; // PLHR
    private static final int HISTORY_FORMAT = 1;
    private static final int HISTORY_LENGTH = 72;

    public interface DirectorySync { void sync(File directory) throws IOException; }
    public interface FailureInjector { void at(WritePoint point) throws IOException; }

    public enum Status { AVAILABLE, KNOWN_MISSING, UNKNOWN, CORRUPT }
    public enum WritePoint {
        RECORD_PARTIAL_WRITE,
        RECORD_FILE_SYNC,
        BEFORE_RECORD_MOVE,
        AFTER_RECORD_MOVE,
        DIRECTORY_SYNC,
        RECEIPT_PARTIAL_WRITE,
        RECEIPT_FILE_SYNC,
        BEFORE_RECEIPT_MOVE,
        AFTER_RECEIPT_MOVE
    }
    public enum FailureReason {
        INVALID_LENGTH,
        RECOVERY_REQUIRED,
        KNOWN_HISTORY_MISSING,
        GENERATION_EXHAUSTED,
        INTERRUPTED,
        STORAGE_FULL,
        STORAGE_IO,
        ATOMIC_PUBLICATION,
        DURABILITY_SYNC
    }

    public static final class SaveFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final FailureReason reason;

        SaveFailure(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        SaveFailure(FailureReason reason, String message, IOException cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    public static final class Recovery {
        public final byte[] bytes;
        public final String warning;
        public final long generation;
        public final Status status;

        Recovery(byte[] bytes, String warning, long generation, Status status) {
            this.bytes = bytes;
            this.warning = warning;
            this.generation = generation;
            this.status = status;
        }
    }

    private static final class Record {
        final int slot;
        final long generation;
        final byte[] bytes;

        Record(int slot, long generation, byte[] bytes) {
            this.slot = slot;
            this.generation = generation;
            this.bytes = bytes;
        }
    }

    private static final class Scan {
        final File firstFile;
        final File secondFile;
        final Record first;
        final Record second;

        Scan(File firstFile, File secondFile, Record first, Record second) {
            this.firstFile = firstFile;
            this.secondFile = secondFile;
            this.first = first;
            this.second = second;
        }

        boolean hasFiles() { return firstFile.exists() || secondFile.exists(); }
        boolean hasInvalidFile() {
            return (firstFile.exists() && first == null) ||
                    (secondFile.exists() && second == null);
        }
        Record newest() {
            if (first == null) return second;
            if (second == null) return first;
            return first.generation >= second.generation ? first : second;
        }
    }

    private final File directory;
    private final DirectorySync directorySync;
    private final FailureInjector failureInjector;

    public GameSaveStore(File directory, DirectorySync directorySync) throws IOException {
        this(directory, directorySync, ignored -> {});
    }

    public GameSaveStore(File directory, DirectorySync directorySync,
                         FailureInjector failureInjector) throws IOException {
        this.directory = directory;
        this.directorySync = directorySync;
        this.failureInjector = failureInjector;
        Files.createDirectories(directory.toPath());
    }

    private static void validateGameId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Game identity must be a SHA-256 hex digest");
    }

    private File slot(String gameId, int index) {
        return new File(directory, gameId + "." + index + ".sav");
    }

    private File historyReceipt(String gameId) {
        return new File(directory, gameId + ".history");
    }

    /** Rejects paths that would make PLSV validation follow substituted or non-regular files. */
    synchronized boolean hasUnsafePaths(String gameId) {
        validateGameId(gameId);
        Path root = directory.toPath();
        if (Files.isSymbolicLink(root) ||
                (Files.exists(root, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))) return true;
        for (Path path : new Path[]{slot(gameId, 0).toPath(), slot(gameId, 1).toPath(),
                historyReceipt(gameId).toPath()}) {
            if (Files.isSymbolicLink(path)) return true;
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
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

    private Scan scan(String gameId) {
        File first = slot(gameId, 0);
        File second = slot(gameId, 1);
        return new Scan(first, second, readRecord(first, gameId, 0),
                readRecord(second, gameId, 1));
    }

    private static Record readRecord(File file, String gameId, int index) {
        if (!file.isFile() || file.length() > MAX_LENGTH + HEADER_LENGTH) return null;
        try (DataInputStream stream = new DataInputStream(new BufferedInputStream(new java.io.FileInputStream(file)))) {
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
            return new Record(index, generation, payload);
        } catch (IOException exception) {
            return null; // Invalid/truncated generation; never copy it into emulator memory.
        }
    }

    private boolean hasValidHistoryReceipt(String gameId) {
        File receipt = historyReceipt(gameId);
        if (!receipt.isFile() || receipt.length() != HISTORY_LENGTH) return false;
        try {
            byte[] bytes = Files.readAllBytes(receipt.toPath());
            if (bytes.length != HISTORY_LENGTH) return false;
            ByteBuffer input = ByteBuffer.wrap(bytes);
            if (input.getInt() != HISTORY_MAGIC || input.getInt() != HISTORY_FORMAT) return false;
            byte[] embeddedIdentity = new byte[32];
            input.get(embeddedIdentity);
            if (!MessageDigest.isEqual(embeddedIdentity, gameDigest(gameId))) return false;
            byte[] expected = new byte[32];
            input.get(expected);
            return MessageDigest.isEqual(expected, sha256(Arrays.copyOf(bytes, HISTORY_LENGTH - 32)));
        } catch (IOException exception) {
            return false;
        }
    }

    private static byte[] encodeHistoryReceipt(String gameId) {
        ByteBuffer output = ByteBuffer.allocate(HISTORY_LENGTH);
        output.putInt(HISTORY_MAGIC);
        output.putInt(HISTORY_FORMAT);
        output.put(gameDigest(gameId));
        byte[] header = Arrays.copyOf(output.array(), HISTORY_LENGTH - 32);
        output.put(sha256(header));
        return output.array();
    }

    public synchronized Recovery load(String gameId) {
        validateGameId(gameId);
        Scan scan = scan(gameId);
        Record selected = scan.newest();
        boolean rejected = scan.hasInvalidFile();
        if (selected != null) {
            String warning = rejected ?
                    "A damaged native-save generation was rejected; an older valid copy was retained." : null;
            return new Recovery(Arrays.copyOf(selected.bytes, selected.bytes.length), warning,
                    selected.generation, Status.AVAILABLE);
        }
        if (scan.hasFiles()) {
            return new Recovery(null, "No valid native-save generation could be loaded; recovery evidence was retained.",
                    0, Status.CORRUPT);
        }
        File receipt = historyReceipt(gameId);
        if (hasValidHistoryReceipt(gameId)) {
            return new Recovery(null, "A previously saved native save is missing.", 0, Status.KNOWN_MISSING);
        }
        String warning = receipt.exists() ?
                "The native-save history receipt is invalid; save history is unknown." : null;
        return new Recovery(null, warning, 0, Status.UNKNOWN);
    }

    public synchronized void write(String gameId, byte[] bytes) throws IOException {
        validateGameId(gameId);
        if (hasUnsafePaths(gameId))
            throw new SaveFailure(FailureReason.RECOVERY_REQUIRED,
                    "Native-save storage contains a symlink or non-regular path; refusing to write");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_LENGTH)
            throw new SaveFailure(FailureReason.INVALID_LENGTH, "Core returned invalid SRAM length");

        Scan scan = scan(gameId);
        Record newest = scan.newest();
        boolean receiptExists = historyReceipt(gameId).exists();
        boolean validReceipt = hasValidHistoryReceipt(gameId);
        if (newest == null && scan.hasFiles())
            throw new SaveFailure(FailureReason.RECOVERY_REQUIRED,
                    "Both existing native-save slots are invalid; refusing to overwrite recovery evidence");
        if (newest == null && receiptExists) {
            if (validReceipt)
                throw new SaveFailure(FailureReason.KNOWN_HISTORY_MISSING,
                        "A prior native save is known to be missing; refusing to publish new data over that history");
            throw new SaveFailure(FailureReason.RECOVERY_REQUIRED,
                    "The native-save history receipt is invalid; refusing to overwrite recovery evidence");
        }
        long previousGeneration = newest == null ? 0 : newest.generation;
        if (previousGeneration == Long.MAX_VALUE)
            throw new SaveFailure(FailureReason.GENERATION_EXHAUSTED, "Save generation number exhausted");

        long generation = previousGeneration + 1;
        int targetSlot = chooseTargetSlot(scan, generation);
        publishRecord(gameId, generation, targetSlot, bytes);
        if (!validReceipt) publishHistoryReceipt(gameId);
    }

    private static int chooseTargetSlot(Scan scan, long generation) {
        if (scan.first != null && scan.second != null)
            return scan.first.generation <= scan.second.generation ? scan.first.slot : scan.second.slot;
        if (scan.first != null) return 1 - scan.first.slot;
        if (scan.second != null) return 1 - scan.second.slot;
        return (int) (generation % 2);
    }

    private void publishRecord(String gameId, long generation, int targetSlot, byte[] bytes)
            throws IOException {
        Path temp = null;
        SaveFailure failure = null;
        try {
            temp = Files.createTempFile(directory.toPath(), gameId + ".", ".pending");
            try (FileOutputStream raw = new FileOutputStream(temp.toFile());
                 DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(raw))) {
                stream.writeInt(MAGIC);
                stream.writeInt(FORMAT);
                stream.writeLong(generation);
                stream.writeInt(bytes.length);
                stream.write(gameDigest(gameId));
                stream.write(sha256(bytes));
                int partialLength = Math.max(1, bytes.length / 2);
                stream.write(bytes, 0, partialLength);
                stream.flush();
                inject(WritePoint.RECORD_PARTIAL_WRITE, FailureReason.STORAGE_IO);
                stream.write(bytes, partialLength, bytes.length - partialLength);
                stream.flush();
                inject(WritePoint.RECORD_FILE_SYNC, FailureReason.DURABILITY_SYNC);
                // BufferedOutputStream flushes before fd sync; no success before durability.
                try {
                    raw.getFD().sync();
                } catch (IOException exception) {
                    throw asFailure(exception, FailureReason.DURABILITY_SYNC);
                }
            }
            Path target = slot(gameId, targetSlot).toPath();
            moveAtomically(temp, target, WritePoint.BEFORE_RECORD_MOVE,
                    WritePoint.AFTER_RECORD_MOVE);
            syncDirectory();
        } catch (IOException exception) {
            failure = asFailure(exception, FailureReason.STORAGE_IO);
            throw failure;
        } finally {
            cleanupTemp(temp, failure);
        }
    }

    private void publishHistoryReceipt(String gameId) throws IOException {
        Path temp = null;
        SaveFailure failure = null;
        try {
            temp = Files.createTempFile(directory.toPath(), gameId + ".", ".pending");
            byte[] receipt = encodeHistoryReceipt(gameId);
            try (FileOutputStream raw = new FileOutputStream(temp.toFile());
                 DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(raw))) {
                stream.writeInt(HISTORY_MAGIC);
                stream.writeInt(HISTORY_FORMAT);
                stream.write(gameDigest(gameId));
                stream.flush();
                inject(WritePoint.RECEIPT_PARTIAL_WRITE, FailureReason.STORAGE_IO);
                stream.write(receipt, HISTORY_LENGTH - 32, 32);
                stream.flush();
                inject(WritePoint.RECEIPT_FILE_SYNC, FailureReason.DURABILITY_SYNC);
                try {
                    raw.getFD().sync();
                } catch (IOException exception) {
                    throw asFailure(exception, FailureReason.DURABILITY_SYNC);
                }
            }
            moveAtomically(temp, historyReceipt(gameId).toPath(),
                    WritePoint.BEFORE_RECEIPT_MOVE, WritePoint.AFTER_RECEIPT_MOVE);
            syncDirectory();
        } catch (IOException exception) {
            failure = asFailure(exception, FailureReason.STORAGE_IO);
            throw failure;
        } finally {
            cleanupTemp(temp, failure);
        }
    }

    private void moveAtomically(Path temp, Path target, WritePoint before, WritePoint after)
            throws IOException {
        inject(before, FailureReason.ATOMIC_PUBLICATION);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new SaveFailure(FailureReason.ATOMIC_PUBLICATION,
                    "Atomic native-save publication is unsupported", exception);
        } catch (IOException exception) {
            throw asFailure(exception, FailureReason.ATOMIC_PUBLICATION);
        }
        inject(after, FailureReason.ATOMIC_PUBLICATION);
    }

    private void syncDirectory() throws IOException {
        inject(WritePoint.DIRECTORY_SYNC, FailureReason.DURABILITY_SYNC);
        try {
            directorySync.sync(directory);
        } catch (IOException exception) {
            throw asFailure(exception, FailureReason.DURABILITY_SYNC);
        }
    }

    private void inject(WritePoint point, FailureReason fallback) throws SaveFailure {
        try {
            failureInjector.at(point);
        } catch (IOException exception) {
            throw asFailure(exception, fallback);
        }
    }

    private static void cleanupTemp(Path temp, SaveFailure failure) throws IOException {
        if (temp == null) return;
        try {
            Files.deleteIfExists(temp);
        } catch (IOException exception) {
            if (failure != null) failure.addSuppressed(exception);
            else throw asFailure(exception, FailureReason.STORAGE_IO);
        }
    }

    private static SaveFailure asFailure(IOException exception, FailureReason fallback) {
        if (exception instanceof SaveFailure saveFailure) return saveFailure;
        if (exception instanceof InterruptedIOException)
            return new SaveFailure(FailureReason.INTERRUPTED,
                    "Native-save write was interrupted", exception);
        String message = exception.getMessage();
        String reason = exception instanceof FileSystemException fileSystemException ?
                fileSystemException.getReason() : null;
        String details = ((reason == null ? "" : reason + " ") +
                (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        if (details.contains("enospc") || details.contains("no space left"))
            return new SaveFailure(FailureReason.STORAGE_FULL, "Native-save storage is full", exception);
        if (details.contains("eio") || details.contains("input/output error") ||
                details.contains("i/o error"))
            return new SaveFailure(FailureReason.STORAGE_IO, "Native-save storage I/O failed", exception);
        return new SaveFailure(fallback, exception.getMessage() == null ?
                "Native-save operation failed" : exception.getMessage(), exception);
    }
}
