package dev.plump.emulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Small durable indication of the last lifecycle boundary for one console and ROM. */
final class SessionReceiptStore {
    enum Console { GBA, DS }
    enum State { UNKNOWN, RUNNING, PAUSED, CLOSED }

    enum Failpoint {
        BEFORE_TEMP_WRITE,
        DURING_TEMP_WRITE,
        AFTER_TEMP_WRITE,
        BEFORE_FILE_FSYNC,
        AFTER_FILE_FSYNC,
        BEFORE_RENAME,
        AFTER_RENAME,
        BEFORE_DIRECTORY_FSYNC,
        DURING_DIRECTORY_FSYNC,
        AFTER_DIRECTORY_FSYNC
    }

    interface FaultInjector {
        void hit(Failpoint point) throws IOException;
    }

    static final FaultInjector NO_FAULTS = point -> { };
    private static final String MAGIC = "PLUMP_SESSION_RECEIPT";
    private static final int VERSION = 1;
    private static final long MAX_RECORD_BYTES = 512;
    private static final AtomicLong TEMP_SEQUENCE = new AtomicLong();

    static final class Receipt {
        final Console console;
        final String gameId;
        final State state;
        final long generation;

        Receipt(Console console, String gameId, State state, long generation) {
            this.console = console;
            this.gameId = gameId;
            this.state = state;
            this.generation = generation;
        }
    }

    static final class ReadResult {
        final State state;
        final Receipt receipt;
        final boolean corrupt;

        ReadResult(State state, Receipt receipt, boolean corrupt) {
            this.state = state;
            this.receipt = receipt;
            this.corrupt = corrupt;
        }
    }

    private static final class Slot {
        final int index;
        final Receipt receipt;

        Slot(int index, Receipt receipt) {
            this.index = index;
            this.receipt = receipt;
        }
    }

    private final Path directory;
    private final FaultInjector faults;

    SessionReceiptStore(Path directory) {
        this(directory, NO_FAULTS);
    }

    SessionReceiptStore(Path directory, FaultInjector faults) {
        this.directory = directory.toAbsolutePath().normalize();
        this.faults = faults == null ? NO_FAULTS : faults;
    }

    synchronized ReadResult read(Console console, String gameId) {
        validateIdentity(console, gameId);
        List<Slot> valid = new ArrayList<>();
        boolean corrupt = false;
        for (int index = 0; index < 2; index++) {
            Path path = slotPath(console, gameId, index);
            if (!Files.exists(path)) continue;
            try {
                if (Files.size(path) > MAX_RECORD_BYTES)
                    throw new IOException("Session receipt exceeds the supported size");
                Receipt receipt = decode(Files.readAllBytes(path), console, gameId);
                valid.add(new Slot(index, receipt));
            } catch (IOException | RuntimeException failure) {
                corrupt = true;
            }
        }
        if (valid.isEmpty()) return new ReadResult(State.UNKNOWN, null, corrupt);
        valid.sort(Comparator.comparingLong(slot -> slot.receipt.generation));
        if (valid.size() == 2 && valid.get(0).receipt.generation == valid.get(1).receipt.generation &&
                valid.get(0).receipt.state != valid.get(1).receipt.state) {
            return new ReadResult(State.UNKNOWN, null, true);
        }
        Receipt newest = valid.get(valid.size() - 1).receipt;
        return new ReadResult(newest.state, newest, corrupt);
    }

    synchronized ReadResult publish(Console console, String gameId, State state) throws IOException {
        if (state == null || state == State.UNKNOWN) throw new IllegalArgumentException("Receipt state must be durable");
        validateIdentity(console, gameId);
        boolean directoryExisted = Files.isDirectory(directory);
        Files.createDirectories(directory);
        if (!directoryExisted && directory.getParent() != null) fsyncDirectory(directory.getParent());

        List<Slot> valid = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Path target = slotPath(console, gameId, index);
            if (!Files.exists(target)) continue;
            try {
                if (Files.size(target) > MAX_RECORD_BYTES)
                    throw new IOException("Session receipt exceeds the supported size");
                valid.add(new Slot(index, decode(Files.readAllBytes(target), console, gameId)));
            } catch (IOException | RuntimeException ignored) {
                // The other generation remains the recovery source.
            }
        }
        int targetIndex;
        long generation;
        if (valid.isEmpty()) {
            targetIndex = 0;
            generation = 1;
        } else if (valid.size() == 1) {
            targetIndex = 1 - valid.get(0).index;
            if (valid.get(0).receipt.generation == Long.MAX_VALUE)
                throw new IOException("Session receipt generation exhausted; valid receipt preserved");
            generation = valid.get(0).receipt.generation + 1;
        } else {
            Slot oldest = valid.get(0).receipt.generation <= valid.get(1).receipt.generation
                    ? valid.get(0) : valid.get(1);
            targetIndex = oldest.index;
            long newest = Math.max(valid.get(0).receipt.generation, valid.get(1).receipt.generation);
            if (newest == Long.MAX_VALUE)
                throw new IOException("Session receipt generation exhausted; valid receipt preserved");
            generation = newest + 1;
        }

        byte[] bytes = encode(new Receipt(console, gameId, state, generation));
        Path target = slotPath(console, gameId, targetIndex);
        Path temporary = directory.resolve(target.getFileName() + ".tmp-" +
                Long.toUnsignedString(TEMP_SEQUENCE.incrementAndGet()));
        boolean moved = false;
        try {
            faults.hit(Failpoint.BEFORE_TEMP_WRITE);
            try (FileChannel file = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                buffer.limit(Math.max(1, buffer.capacity() / 2));
                while (buffer.hasRemaining()) file.write(buffer);
                buffer.limit(buffer.capacity());
                faults.hit(Failpoint.DURING_TEMP_WRITE);
                while (buffer.hasRemaining()) file.write(buffer);
                faults.hit(Failpoint.AFTER_TEMP_WRITE);
                faults.hit(Failpoint.BEFORE_FILE_FSYNC);
                file.force(true);
                faults.hit(Failpoint.AFTER_FILE_FSYNC);
            }
            faults.hit(Failpoint.BEFORE_RENAME);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Receipt filesystem does not support atomic rename", unsupported);
            }
            moved = true;
            faults.hit(Failpoint.AFTER_RENAME);
            faults.hit(Failpoint.BEFORE_DIRECTORY_FSYNC);
            fsyncDirectory(directory, faults);
            faults.hit(Failpoint.AFTER_DIRECTORY_FSYNC);
            return new ReadResult(state, new Receipt(console, gameId, state, generation), false);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void fsyncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void fsyncDirectory(Path directory, FaultInjector faults) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            faults.hit(Failpoint.DURING_DIRECTORY_FSYNC);
            channel.force(true);
        }
    }

    private Path slotPath(Console console, String gameId, int index) {
        return directory.resolve(console.name().toLowerCase(Locale.ROOT) + "-" + gameId + "." + index);
    }

    private static byte[] encode(Receipt receipt) {
        String body = MAGIC + "|" + VERSION + "|" + receipt.console.name() + "|" +
                receipt.gameId + "|" + receipt.state.name() + "|" + receipt.generation;
        String complete = body + "|" + sha256(body.getBytes(StandardCharsets.UTF_8)) + "\n";
        return complete.getBytes(StandardCharsets.UTF_8);
    }

    private static Receipt decode(byte[] bytes, Console expectedConsole, String expectedGameId) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.endsWith("\n\n")) throw new IOException("Receipt terminator is invalid");
        String[] fields = text.substring(0, text.length() - 1).split("\\|", -1);
        if (fields.length != 7 || !fields[0].equals(MAGIC) || !fields[1].equals(Integer.toString(VERSION)) ||
                !fields[2].equals(expectedConsole.name()) ||
                !fields[3].equals(expectedGameId)) throw new IOException("Receipt header or identity is invalid");
        String body = String.join("|", fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
        if (!MessageDigest.isEqual(fields[6].getBytes(StandardCharsets.US_ASCII),
                sha256(body.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Receipt checksum mismatch");
        }
        State state;
        long generation;
        try {
            state = State.valueOf(fields[4]);
            generation = Long.parseLong(fields[5]);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Receipt state or generation is invalid", invalid);
        }
        if (state == State.UNKNOWN || generation <= 0) throw new IOException("Receipt fields are invalid");
        return new Receipt(expectedConsole, expectedGameId, state, generation);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(64);
            for (byte value : hash) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void validateIdentity(Console console, String gameId) {
        if (console == null || gameId == null || !gameId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Receipt identity must be a console and lowercase SHA-256");
    }
}
