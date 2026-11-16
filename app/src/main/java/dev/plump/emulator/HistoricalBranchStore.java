package dev.plump.emulator;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable host-only historical forks. Published branches never point at canonical save storage. */
final class HistoricalBranchStore {
    private static final int MANIFEST_LIMIT = 4096;
    private static final String[] KEYS = {"format", "status", "branch_id", "checkpoint_id", "console",
            "rom_sha256", "core_id", "core_git_revision", "core_frontend_git_revision",
            "core_binary_sha256", "abi", "libretro_api", "machine_length", "machine_sha256",
            "native_length", "native_sha256"};

    enum FaultPoint { BEFORE_STAGE, AFTER_MACHINE, AFTER_NATIVE, AFTER_JOURNAL,
        BEFORE_MANIFEST, AFTER_MANIFEST, AFTER_STAGE_FSYNC, BEFORE_PUBLISH,
        AFTER_PUBLISH, AFTER_READBACK, BEFORE_QUARANTINE }
    enum Evidence { NONE, MATCHING_ROM, UNKNOWN }
    interface FaultInjector { void at(FaultPoint point) throws IOException; }
    interface DirectorySync { void sync(Path directory) throws IOException; }
    interface UsableSpace { long bytes(Path directory) throws IOException; }

    static final class Branch {
        final String id;
        final String checkpointId;
        final CheckpointStore.Identity identity;
        final Path directory;
        final Path machineFile;
        final Path nativeFile;
        final Path coreDataDirectory;
        final Path systemDirectory;
        final Path journalDirectory;
        private final byte[] machineState;
        private final byte[] nativeSave;
        final GameSaveStore saves;

        private Branch(String id, String checkpointId, CheckpointStore.Identity identity,
                       Path directory, byte[] machineState, byte[] nativeSave,
                       GameSaveStore saves) {
            this.id = id;
            this.checkpointId = checkpointId;
            this.identity = identity;
            this.directory = directory;
            this.machineFile = directory.resolve("machine.bin");
            this.nativeFile = directory.resolve("native.bin");
            this.coreDataDirectory = directory.resolve("core-data");
            this.systemDirectory = directory.resolve("system");
            this.journalDirectory = directory.resolve("journal");
            this.machineState = machineState.clone();
            this.nativeSave = nativeSave.clone();
            this.saves = saves;
        }

        byte[] machineState() { return machineState.clone(); }
        byte[] nativeSave() { return nativeSave.clone(); }
    }

    private final Path root;
    private final DirectorySync directorySync;
    private final UsableSpace usableSpace;
    private final FaultInjector faults;
    private final GameSaveStore.DirectorySync journalSync;

    HistoricalBranchStore(Path root, DirectorySync directorySync, UsableSpace usableSpace,
                           GameSaveStore.DirectorySync journalSync, FaultInjector faults) {
        if (root == null || directorySync == null || usableSpace == null || journalSync == null || faults == null)
            throw new IllegalArgumentException();
        this.root = root.toAbsolutePath().normalize();
        this.directorySync = directorySync;
        this.usableSpace = usableSpace;
        this.journalSync = journalSync;
        this.faults = faults;
    }

    synchronized Branch create(CheckpointStore checkpoints, String checkpointId,
                               CheckpointStore.Identity expected) throws IOException {
        if (checkpoints == null || expected == null || checkpointId == null)
            throw new IOException("Historical restore identity is incomplete");
        CheckpointStore.Pair pair = checkpoints.readPair(checkpointId, expected);
        ensureRoot();
        long required = (long) pair.machineState.length + pair.nativeSave.length + MANIFEST_LIMIT + 8192L;
        if (usableSpace.bytes(root) < required) throw new IOException("Insufficient historical branch storage space");

        String id = UUID.randomUUID().toString();
        Path stage = root.resolve(".pending-" + id);
        Path published = root.resolve("branch-" + id);
        boolean moved = false;
        try {
            hit(FaultPoint.BEFORE_STAGE);
            Files.createDirectory(stage);
            Path journal = stage.resolve("journal");
            Path coreData = stage.resolve("core-data");
            Path system = stage.resolve("system");
            Files.createDirectory(journal);
            Files.createDirectory(coreData);
            Files.createDirectory(system);
            writeVerified(stage.resolve("machine.bin"), pair.machineState);
            hit(FaultPoint.AFTER_MACHINE);
            writeVerified(stage.resolve("native.bin"), pair.nativeSave);
            hit(FaultPoint.AFTER_NATIVE);
            GameSaveStore stagedJournal = new GameSaveStore(journal.toFile(), journalSync);
            stagedJournal.write(expected.romSha256, pair.nativeSave);
            hit(FaultPoint.AFTER_JOURNAL);

            byte[] manifest = encode(id, checkpointId, pair.record, pair.machineState, pair.nativeSave);
            hit(FaultPoint.BEFORE_MANIFEST);
            writeVerified(stage.resolve("manifest.hbr"), manifest);
            hit(FaultPoint.AFTER_MANIFEST);
            directorySync.sync(journal);
            directorySync.sync(coreData);
            directorySync.sync(system);
            directorySync.sync(stage);
            hit(FaultPoint.AFTER_STAGE_FSYNC);
            hit(FaultPoint.BEFORE_PUBLISH);
            try { Files.move(stage, published, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Historical branch publication requires atomic directory rename", unsupported);
            }
            moved = true;
            hit(FaultPoint.AFTER_PUBLISH);
            directorySync.sync(root);
            hit(FaultPoint.AFTER_READBACK);
            return openPublished(published, id, expected);
        } catch (IOException | RuntimeException failure) {
            if (moved) {
                try { quarantine(id); } catch (IOException quarantineFailure) { failure.addSuppressed(quarantineFailure); }
            } else if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                // Failed staging is retained as evidence; it is never considered a branch.
                try { directorySync.sync(root); } catch (IOException syncFailure) { failure.addSuppressed(syncFailure); }
            }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Historical branch publication failed", failure);
        }
    }

    synchronized Branch open(String id, CheckpointStore.Identity expected) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || expected == null)
            throw new IOException("Invalid historical branch identity");
        ensureRoot();
        return openPublished(root.resolve("branch-" + id), id, expected);
    }

    synchronized void quarantine(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Invalid branch quarantine identity");
        ensureRoot();
        hit(FaultPoint.BEFORE_QUARANTINE);
        Path branch = root.resolve("branch-" + id);
        Path quarantine = root.resolve("quarantine-" + id);
        if (!Files.isDirectory(branch, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Historical branch quarantine destination already exists");
        rejectSymlink(branch);
        try { Files.move(branch, quarantine, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Branch quarantine requires atomic directory rename", unsupported);
        }
        directorySync.sync(root);
    }

    /** Scans published/quarantined branch metadata for a missing-selector recovery decision. */
    synchronized Evidence scanPublishedEvidence(CheckpointStore.Identity expected) {
        if (expected == null) return Evidence.UNKNOWN;
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Evidence.NONE;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Evidence.UNKNOWN;
        boolean unknown = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith("branch-") && !name.startsWith("quarantine-")) continue;
                if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    unknown = true;
                    continue;
                }
                try {
                    String[] fields = decode(readBounded(entry.resolve("manifest.hbr"), MANIFEST_LIMIT));
                    if (expected.romSha256.equals(fields[5])) return Evidence.MATCHING_ROM;
                } catch (IOException | RuntimeException unreadable) { unknown = true; }
            }
            return unknown ? Evidence.UNKNOWN : Evidence.NONE;
        } catch (IOException failure) {
            return Evidence.UNKNOWN;
        }
    }

    private Branch openPublished(Path directory, String id, CheckpointStore.Identity expected) throws IOException {
        rejectSymlink(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                !directory.getFileName().toString().equals("branch-" + id))
            throw new IOException("Historical branch is not published");
        Path manifestPath = directory.resolve("manifest.hbr");
        requireRegular(manifestPath);
        byte[] manifest = readBounded(manifestPath, MANIFEST_LIMIT);
        String[] values = decode(manifest);
        if (!id.equals(values[2])) throw new IOException("Historical branch directory identity mismatch");
        CheckpointStore.Identity actual;
        try {
            actual = new CheckpointStore.Identity(values[4], values[5], values[6], values[7], values[8],
                    values[9], values[10], Integer.parseInt(values[11]));
        } catch (RuntimeException invalid) { throw new IOException("Historical branch core identity is invalid", invalid); }
        if (!expected.sameAs(actual)) throw new IOException("Historical branch ROM or core identity mismatch");
        long machineLength = parseLength(values[12]);
        long nativeLength = parseLength(values[14]);
        if (machineLength == 0 || machineLength > CheckpointStore.MAX_MACHINE_BYTES ||
                nativeLength == 0 || nativeLength > CheckpointStore.MAX_NATIVE_BYTES)
            throw new IOException("Historical branch blob length is unsupported");
        Path machinePath = directory.resolve("machine.bin");
        Path nativePath = directory.resolve("native.bin");
        requireRegular(machinePath);
        requireRegular(nativePath);
        byte[] machine = readBounded(machinePath, (int) machineLength);
        byte[] nativeSave = readBounded(nativePath, (int) nativeLength);
        if (machine.length != machineLength || nativeSave.length != nativeLength ||
                !values[13].equals(sha256(machine)) || !values[15].equals(sha256(nativeSave)))
            throw new IOException("Historical branch immutable pair checksum mismatch");
        Path journal = directory.resolve("journal");
        Path coreData = directory.resolve("core-data");
        Path system = directory.resolve("system");
        rejectSymlink(journal);
        rejectSymlink(coreData);
        rejectSymlink(system);
        if (!Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(coreData, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(system, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Historical branch save directories are unavailable");
        // Journal generations belong solely to this branch. Never read through a
        // substituted link, even when a different generation remains recoverable.
        for (String suffix : new String[] {".0.sav", ".1.sav", ".history"})
            rejectSymlink(journal.resolve(actual.romSha256 + suffix));
        GameSaveStore saves = new GameSaveStore(journal.toFile(), journalSync);
        GameSaveStore.Recovery recovery = saves.load(actual.romSha256);
        if (recovery.bytes == null || recovery.warning != null || recovery.bytes.length != nativeSave.length)
            throw new IOException("Historical branch journal has no valid recoverable generation");
        return new Branch(id, values[3], actual, directory, machine, nativeSave, saves);
    }

    private byte[] encode(String id, String checkpointId, CheckpointStore.Record record,
                          byte[] machine, byte[] nativeSave) {
        String[] values = {"1", "committed", id, checkpointId, record.identity.console,
                record.identity.romSha256, record.identity.coreId, record.identity.coreRevision,
                record.identity.coreFrontendRevision, record.identity.coreBinarySha256,
                record.identity.abi, Integer.toString(record.identity.libretroApi),
                Integer.toString(machine.length), sha256(machine), Integer.toString(nativeSave.length), sha256(nativeSave)};
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) body.append(KEYS[i]).append('=').append(values[i]).append('\n');
        String checksum = sha256(body.toString().getBytes(StandardCharsets.UTF_8));
        body.append("manifest_sha256=").append(checksum).append('\n');
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String[] decode(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\n", -1);
        if (lines.length != KEYS.length + 2 || !lines[lines.length - 1].isEmpty())
            throw new IOException("Historical branch manifest structure is invalid");
        StringBuilder body = new StringBuilder();
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String prefix = KEYS[i] + '=';
            if (!lines[i].startsWith(prefix) || lines[i].indexOf('=', prefix.length()) >= 0)
                throw new IOException("Historical branch manifest field is invalid");
            values[i] = lines[i].substring(prefix.length());
            body.append(lines[i]).append('\n');
        }
        if (!lines[KEYS.length].startsWith("manifest_sha256=") ||
                !lines[KEYS.length].substring("manifest_sha256=".length()).equals(
                        sha256(body.toString().getBytes(StandardCharsets.UTF_8))))
            throw new IOException("Historical branch manifest checksum mismatch");
        if (!"1".equals(values[0]) || !"committed".equals(values[1]) ||
                !values[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
                !values[3].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Historical branch commit marker is invalid");
        return values;
    }

    private void writeVerified(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw new IOException("Short branch write");
            channel.force(true);
        }
        if (Files.size(path) != bytes.length || !sha256(bytes).equals(hashFile(path)))
            throw new IOException("Historical branch staged readback mismatch");
    }

    private void ensureRoot() throws IOException {
        rejectPathSymlinks(root);
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Historical branch root is not a directory");
        Files.createDirectories(root);
        rejectPathSymlinks(root);
        if (root.getParent() != null) directorySync.sync(root.getParent());
        directorySync.sync(root);
    }

    private void hit(FaultPoint point) throws IOException { faults.at(point); }
    private static void requireRegular(Path path) throws IOException {
        rejectSymlink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Historical branch file is missing");
    }
    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Historical branch path contains a symbolic link");
    }
    private static void rejectPathSymlinks(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Historical branch path contains a symbolic link");
        }
    }
    private static byte[] readBounded(Path path, int limit) throws IOException {
        if (Files.size(path) > limit) throw new IOException("Historical branch file exceeds its bound");
        byte[] value = Files.readAllBytes(path);
        if (value.length > limit) throw new IOException("Historical branch file exceeds its bound");
        return value;
    }
    private static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            try (java.io.InputStream input = Files.newInputStream(path)) {
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static long parseLength(String value) throws IOException {
        try { long parsed = Long.parseLong(value); return parsed > 0 ? parsed : 0; }
        catch (NumberFormatException invalid) { throw new IOException("Historical branch length is invalid", invalid); }
    }
}
