package dev.plump.emulator;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable host-only checkpoint blobs. This class intentionally has no state-load operation. */
final class CheckpointStore {
    static final int FORMAT_VERSION = 1;
    static final int MAX_MACHINE_BYTES = 64 * 1024 * 1024;
    static final int MAX_NATIVE_BYTES = 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4096;
    private static final int AUTO_RECENT = 3;

    enum Kind { MANUAL, AUTO }

    enum FaultPoint {
        BEFORE_TEMP_WRITE, DURING_FILE_WRITE, AFTER_TEMP_WRITE,
        BEFORE_FILE_FSYNC, AFTER_FILE_FSYNC,
        BEFORE_MANIFEST_WRITE, AFTER_MANIFEST_WRITE,
        BEFORE_DIRECTORY_FSYNC, AFTER_DIRECTORY_FSYNC,
        BEFORE_RENAME, AFTER_RENAME,
        BEFORE_MANIFEST_PUBLICATION, AFTER_MANIFEST_PUBLICATION
    }

    interface FaultInjector { void at(FaultPoint point) throws IOException; }
    interface DirectorySync { void sync(Path directory) throws IOException; }
    interface UsableSpace { long bytes(Path directory) throws IOException; }

    static final class Identity {
        final String console;
        final String romSha256;
        final String coreId;
        final String coreRevision;
        final String coreFrontendRevision;
        final String coreBinarySha256;
        final String abi;
        final int libretroApi;

        Identity(String console, String romSha256, String coreId, String coreRevision,
                 String coreFrontendRevision, String coreBinarySha256, String abi, int libretroApi) {
            this.console = require(console, "console", "gba|ds");
            if (!console.equals("gba") && !console.equals("ds"))
                throw new IllegalArgumentException("Unsupported checkpoint console");
            this.romSha256 = requireHex(romSha256, 64, "ROM SHA-256");
            this.coreId = require(coreId, "core ID", "[a-z0-9][a-z0-9._-]{0,63}");
            this.coreRevision = requireHex(coreRevision, 40, "core Git revision");
            this.coreFrontendRevision = "none".equals(coreFrontendRevision) ? "none" :
                    requireHex(coreFrontendRevision, 40, "core frontend Git revision");
            this.coreBinarySha256 = requireHex(coreBinarySha256, 64, "core binary SHA-256");
            this.abi = require(abi, "ABI", "[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
            if (libretroApi < 1 || libretroApi > 255)
                throw new IllegalArgumentException("Invalid Libretro API version");
            this.libretroApi = libretroApi;
        }

        boolean sameAs(Identity other) {
            return other != null && console.equals(other.console) && romSha256.equals(other.romSha256) &&
                    coreId.equals(other.coreId) && coreRevision.equals(other.coreRevision) &&
                    coreFrontendRevision.equals(other.coreFrontendRevision) &&
                    coreBinarySha256.equals(other.coreBinarySha256) && abi.equals(other.abi) &&
                    libretroApi == other.libretroApi;
        }
    }

    static final class Capture {
        final boolean supported;
        final int libretroApi;
        final byte[] machineState;
        final byte[] nativeSave;
        final String error;

        private Capture(boolean supported, int libretroApi, byte[] machineState,
                        byte[] nativeSave, String error) {
            this.supported = supported;
            this.libretroApi = libretroApi;
            this.machineState = machineState;
            this.nativeSave = nativeSave;
            this.error = error;
        }

        static Capture supported(int api, byte[] machineState, byte[] nativeSave) {
            return new Capture(true, api, machineState, nativeSave, null);
        }

        static Capture unsupported(int api, String error) {
            return new Capture(false, api, null, null, error == null ? "Serializer unsupported" : error);
        }
    }

    static final class Record {
        final String id;
        final long generation;
        final Kind kind;
        final Identity identity;
        final long machineLength;
        final String machineSha256;
        final long nativeLength;
        final String nativeSha256;
        final String branchId;
        final long sessionRunToken;

        private Record(String id, long generation, Kind kind, Identity identity,
                       long machineLength, String machineSha256, long nativeLength,
                       String nativeSha256, String branchId, long sessionRunToken) {
            this.id = id;
            this.generation = generation;
            this.kind = kind;
            this.identity = identity;
            this.machineLength = machineLength;
            this.machineSha256 = machineSha256;
            this.nativeLength = nativeLength;
            this.nativeSha256 = nativeSha256;
            this.branchId = branchId;
            this.sessionRunToken = sessionRunToken;
        }
    }

    private static final String[] MANIFEST_KEYS = {
            "format", "status", "id", "generation", "kind", "console", "rom_sha256",
            "core_id", "core_git_revision", "core_frontend_git_revision", "core_binary_sha256", "abi", "libretro_api",
            "machine_length", "machine_sha256", "native_length", "native_sha256",
            "branch_id", "session_run_token"
    };

    private final Path root;
    private final DirectorySync directorySync;
    private final UsableSpace usableSpace;
    private final FaultInjector faultInjector;

    CheckpointStore(Path root) {
        this(root, CheckpointStore::syncDirectory, path -> Files.getFileStore(path).getUsableSpace(), point -> { });
    }

    CheckpointStore(Path root, DirectorySync directorySync, UsableSpace usableSpace,
                    FaultInjector faultInjector) {
        if (root == null || directorySync == null || usableSpace == null || faultInjector == null)
            throw new IllegalArgumentException();
        this.root = root.toAbsolutePath().normalize();
        this.directorySync = directorySync;
        this.usableSpace = usableSpace;
        this.faultInjector = faultInjector;
    }

    synchronized Record create(Identity identity, Capture capture, Kind kind) throws IOException {
        return create(identity, capture, kind, 0L);
    }

    synchronized Record create(Identity identity, Capture capture, Kind kind, long sessionRunToken) throws IOException {
        if (identity == null || capture == null || kind == null) throw new IOException("Checkpoint request is incomplete");
        if (sessionRunToken < 0) throw new IOException("Checkpoint session token is invalid");
        if (!capture.supported) throw new IOException(capture.error);
        if (capture.libretroApi != identity.libretroApi)
            throw new IOException("Libretro API version changed during checkpoint capture");
        byte[] state = capture.machineState;
        byte[] nativeSave = capture.nativeSave;
        if (state == null || state.length == 0 || state.length > MAX_MACHINE_BYTES)
            throw new IOException("Machine state length is unsupported");
        if (nativeSave == null || nativeSave.length == 0 || nativeSave.length > MAX_NATIVE_BYTES)
            throw new IOException("Native cartridge save length is unsupported");

        ensureRoot();
        long required = (long) state.length + nativeSave.length + MAX_MANIFEST_BYTES;
        if (usableSpace.bytes(root) < required) throw new IOException("Insufficient checkpoint storage space");

        List<Record> before = listValid(identity);
        long previousGeneration = before.stream().mapToLong(record -> record.generation).max().orElse(0L);
        if (previousGeneration == Long.MAX_VALUE) throw new IOException("Checkpoint generation is exhausted");
        long generation = Math.max(System.currentTimeMillis(), previousGeneration + 1L);
        String id = UUID.randomUUID().toString();
        Path pending = root.resolve(".pending-" + id);
        Path published = root.resolve("slot-" + id);
        Files.createDirectory(pending);
        boolean renamed = false;
        try {
            hit(FaultPoint.BEFORE_TEMP_WRITE);
            String stateHash = writeBlob(pending.resolve("machine.bin"), state);
            String nativeHash = writeBlob(pending.resolve("native.bin"), nativeSave);
            hit(FaultPoint.AFTER_TEMP_WRITE);

            Record record = new Record(id, generation, kind, identity, state.length,
                    stateHash, nativeSave.length, nativeHash, "canonical", sessionRunToken);
            hit(FaultPoint.BEFORE_MANIFEST_WRITE);
            writeFile(pending.resolve("manifest.pcm"), encode(record));
            hit(FaultPoint.AFTER_MANIFEST_WRITE);

            hit(FaultPoint.BEFORE_DIRECTORY_FSYNC);
            directorySync.sync(pending);
            hit(FaultPoint.AFTER_DIRECTORY_FSYNC);

            hit(FaultPoint.BEFORE_MANIFEST_PUBLICATION);
            hit(FaultPoint.BEFORE_RENAME);
            try { Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Checkpoint publication requires atomic directory rename", unsupported);
            }
            renamed = true;
            hit(FaultPoint.AFTER_RENAME);
            hit(FaultPoint.AFTER_MANIFEST_PUBLICATION);
            hit(FaultPoint.BEFORE_DIRECTORY_FSYNC);
            directorySync.sync(root);
            hit(FaultPoint.AFTER_DIRECTORY_FSYNC);

            Record verified = readAndVerify(published, identity);
            pruneAutos(identity);
            return verified;
        } catch (IOException | RuntimeException failure) {
            if (!renamed) {
                try { deleteTree(pending); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Checkpoint creation failed", failure);
        }
    }

    synchronized Record verify(String id, Identity expected) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}") || expected == null)
            throw new IOException("Invalid checkpoint identity");
        return readAndVerify(root.resolve("slot-" + id), expected);
    }

    synchronized List<Record> listValid(Identity expected) throws IOException {
        if (expected == null) throw new IOException("Expected checkpoint identity is missing");
        ensureRoot();
        List<Record> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root, "slot-*")) {
            for (Path child : children) {
                try { result.add(readAndVerify(child, expected)); }
                catch (IOException ignored) { }
            }
        }
        result.sort(Comparator.comparingLong(record -> record.generation));
        return result;
    }

    private String writeBlob(Path file, byte[] bytes) throws IOException {
        writeFile(file, bytes);
        String expectedHash = sha256(bytes);
        if (Files.size(file) != bytes.length || !expectedHash.equals(hashFile(file, bytes.length)))
            throw new IOException("Checkpoint temporary readback did not match");
        return expectedHash;
    }

    private void writeFile(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            if (source.remaining() > 1) {
                int oldLimit = source.limit();
                source.limit(source.position() + Math.max(1, source.remaining() / 2));
                if (channel.write(source) <= 0) throw new IOException("Checkpoint short write");
                source.limit(oldLimit);
                hit(FaultPoint.DURING_FILE_WRITE);
            }
            while (source.hasRemaining()) {
                if (channel.write(source) <= 0) throw new IOException("Checkpoint short write");
            }
            hit(FaultPoint.BEFORE_FILE_FSYNC);
            channel.force(true);
            hit(FaultPoint.AFTER_FILE_FSYNC);
        }
    }

    private Record readAndVerify(Path directory, Identity expected) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                !directory.getFileName().toString().startsWith("slot-"))
            throw new IOException("Checkpoint is not published");
        Path manifest = directory.resolve("manifest.pcm");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Checkpoint manifest is not a regular file");
        long manifestSize = Files.size(manifest);
        if (manifestSize <= 0 || manifestSize > MAX_MANIFEST_BYTES)
            throw new IOException("Checkpoint manifest length is invalid");
        byte[] content = readBounded(manifest, MAX_MANIFEST_BYTES);
        Record record = decode(content);
        if (!directory.getFileName().toString().equals("slot-" + record.id))
            throw new IOException("Checkpoint directory and manifest IDs differ");
        if (!expected.sameAs(record.identity)) throw new IOException("Checkpoint game/core identity mismatch");
        Path machine = directory.resolve("machine.bin");
        Path nativeSave = directory.resolve("native.bin");
        if (!Files.isRegularFile(machine, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(nativeSave, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Checkpoint blobs are not regular files");
        if (record.machineLength <= 0 || record.machineLength > MAX_MACHINE_BYTES ||
                record.nativeLength <= 0 || record.nativeLength > MAX_NATIVE_BYTES)
            throw new IOException("Checkpoint blob length is unsupported");
        if (Files.size(machine) != record.machineLength ||
                !record.machineSha256.equals(hashFile(machine, record.machineLength)))
            throw new IOException("Checkpoint machine state checksum mismatch");
        if (Files.size(nativeSave) != record.nativeLength ||
                !record.nativeSha256.equals(hashFile(nativeSave, record.nativeLength)))
            throw new IOException("Checkpoint native save checksum mismatch");
        return record;
    }

    private byte[] encode(Record record) {
        String[] values = {
                Integer.toString(FORMAT_VERSION), "committed", record.id, Long.toString(record.generation),
                record.kind.name().toLowerCase(Locale.ROOT),
                record.identity.console, record.identity.romSha256, record.identity.coreId,
                record.identity.coreRevision, record.identity.coreFrontendRevision,
                record.identity.coreBinarySha256, record.identity.abi,
                Integer.toString(record.identity.libretroApi), Long.toString(record.machineLength),
                record.machineSha256, Long.toString(record.nativeLength), record.nativeSha256,
                record.branchId, Long.toString(record.sessionRunToken)
        };
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < MANIFEST_KEYS.length; i++)
            text.append(MANIFEST_KEYS[i]).append('=').append(values[i]).append('\n');
        String checksum = sha256(text.toString().getBytes(StandardCharsets.UTF_8));
        text.append("manifest_sha256=").append(checksum).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Record decode(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\n", -1);
        if (lines.length != MANIFEST_KEYS.length + 2 || !lines[lines.length - 1].isEmpty())
            throw new IOException("Checkpoint manifest structure is invalid");
        String[] values = new String[MANIFEST_KEYS.length];
        StringBuilder authenticatedBody = new StringBuilder();
        for (int i = 0; i < MANIFEST_KEYS.length; i++) {
            String prefix = MANIFEST_KEYS[i] + '=';
            if (!lines[i].startsWith(prefix) || lines[i].indexOf('=', prefix.length()) >= 0)
                throw new IOException("Checkpoint manifest field is invalid: " + MANIFEST_KEYS[i]);
            values[i] = lines[i].substring(prefix.length());
            authenticatedBody.append(lines[i]).append('\n');
        }
        String checksumLine = lines[MANIFEST_KEYS.length];
        if (!checksumLine.startsWith("manifest_sha256="))
            throw new IOException("Checkpoint manifest checksum is missing");
        String checksum = requireHash(checksumLine.substring("manifest_sha256=".length()), "manifest checksum");
        if (!checksum.equals(sha256(authenticatedBody.toString().getBytes(StandardCharsets.UTF_8))))
            throw new IOException("Checkpoint manifest checksum mismatch");
        if (!values[0].equals(Integer.toString(FORMAT_VERSION)) || !values[1].equals("committed"))
            throw new IOException("Checkpoint format or commit status is unsupported");
        String id = values[2];
        if (!id.matches("[0-9a-f-]{36}")) throw new IOException("Checkpoint ID is invalid");
        long generation = parseLong(values[3], "generation");
        if (generation <= 0) throw new IOException("Checkpoint generation is invalid");
        Kind kind;
        try { kind = Kind.valueOf(values[4].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Checkpoint kind is invalid", invalid); }
        Identity identity;
        try {
            identity = new Identity(values[5], values[6], values[7], values[8], values[9], values[10],
                    values[11], Integer.parseInt(values[12]));
        } catch (RuntimeException invalid) {
            throw new IOException("Checkpoint core or ROM identity is invalid", invalid);
        }
        long machineLength = parseLong(values[13], "machine length");
        String machineHash = requireHash(values[14], "machine checksum");
        long nativeLength = parseLong(values[15], "native length");
        String nativeHash = requireHash(values[16], "native checksum");
        if (!"canonical".equals(values[17]))
            throw new IOException("Checkpoint branch identity is unsupported");
        long sessionRunToken = parseLong(values[18], "session run token");
        if (sessionRunToken < 0) throw new IOException("Checkpoint session run token is invalid");
        return new Record(id, generation, kind, identity, machineLength, machineHash,
                nativeLength, nativeHash, "canonical", sessionRunToken);
    }

    private void pruneAutos(Identity identity) throws IOException {
        List<Record> autos = new ArrayList<>();
        for (Record record : listValid(identity)) if (record.kind == Kind.AUTO) autos.add(record);
        if (autos.size() <= AUTO_RECENT + 1) return;
        autos.sort(Comparator.comparingLong((Record record) -> record.generation).reversed());
        List<Record> keep = new ArrayList<>(autos.subList(0, AUTO_RECENT));
        keep.add(autos.get(autos.size() - 1));
        for (Record record : autos) {
            if (keep.contains(record)) continue;
            Path slot = root.resolve("slot-" + record.id);
            try {
                if (!record.id.equals(readAndVerify(slot, identity).id)) continue;
                deleteTree(slot);
            } catch (IOException ignored) {
                // A retention failure may leave extra autos; it must never fail a durable checkpoint.
            }
        }
        try { directorySync.sync(root); }
        catch (IOException ignored) {
            // Retention is best-effort; the verified new record remains committed.
        }
    }

    private void ensureRoot() throws IOException {
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Checkpoint root is not a private directory");
        Path cursor = root;
        List<Path> created = new ArrayList<>();
        while (cursor != null && !Files.exists(cursor)) {
            created.add(cursor);
            cursor = cursor.getParent();
        }
        Files.createDirectories(root);
        for (int i = created.size() - 1; i >= 0; i--) {
            Path directory = created.get(i);
            if (directory.getParent() != null) directorySync.sync(directory.getParent());
            directorySync.sync(directory);
        }
    }

    private void hit(FaultPoint point) throws IOException { faultInjector.at(point); }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String hashFile(Path file, long limit) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > limit) throw new IOException("Checkpoint blob exceeded its declared size");
                    digest.update(buffer, 0, count);
                }
            }
            if (total != limit) throw new IOException("Checkpoint blob was truncated");
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] readBounded(Path file, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > limit - count) throw new IOException("Checkpoint manifest exceeds its limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
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

    /** Delete only recognized files inside a Plump-owned staging/published slot; never recurse. */
    private static void deleteTree(Path slot) throws IOException {
        if (!Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) return;
        String name = slot.getFileName().toString();
        if ((!name.matches("slot-[0-9a-f-]{36}") && !name.matches("\\.pending-[0-9a-f-]{36}")) ||
                !Files.isDirectory(slot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to remove an unrecognized checkpoint path");
        List<Path> owned = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(slot)) {
            for (Path child : children) {
                String leaf = child.getFileName().toString();
                if (!("machine.bin".equals(leaf) || "native.bin".equals(leaf) || "manifest.pcm".equals(leaf)) ||
                        !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Refusing to remove unknown checkpoint content");
                owned.add(child);
            }
        }
        for (Path file : owned) Files.delete(file);
        Files.delete(slot);
    }

    private static long parseLong(String value, String field) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IOException("Checkpoint " + field + " is invalid", invalid); }
    }

    private static String requireHash(String value, String name) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IOException("Checkpoint " + name + " is invalid");
        return value;
    }

    private static String requireHex(String value, int size, String name) {
        if (value == null || !value.matches("[0-9a-f]{" + size + "}"))
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static String require(String value, String name, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
