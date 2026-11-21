package dev.plump.emulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Host-testable, immutable-generation prototype for one DS ROM/branch DLDI
 * image. It is deliberately not wired into DsActivity: writable virtual SD
 * remains disabled until the native core and Android durability gates pass.
 */
final class VirtualSdGenerationStore {
    static final String IMAGE_RELATIVE = "melonDS DS/dldi_sd_card.bin";
    private static final String MANIFEST_NAME = "manifest.vsd";
    private static final String FORMAT = "PLUMP_VIRTUAL_SD_GENERATION";
    private static final String HEAD_FORMAT = "PLUMP_VIRTUAL_SD_HEAD";
    private static final int VERSION = 1;
    private static final int MAX_RECORD_BYTES = 16 * 1024;
    private static final String[] MANIFEST_KEYS = {
            "format", "version", "status", "sequence", "generation_id", "console",
            "rom_sha256", "core_id", "core_revision", "core_frontend_revision",
            "core_binary_sha256", "abi", "libretro_api", "branch_id", "image_path",
            "parent_branch_id", "parent_generation_id", "checkpoint_id", "image_bytes",
            "image_sha256", "folder_sync"
    };
    private static final String[] HEAD_KEYS = {
            "format", "version", "sequence", "generation_id", "manifest_sha256"
    };

    enum FaultPoint {
        AFTER_BOOTSTRAP_IMAGE_FORCE,
        AFTER_BOOTSTRAP_GENERATION_RENAME,
        AFTER_WORKING_IMAGE_FORCE,
        AFTER_COMMIT_IMAGE_FORCE,
        AFTER_MANIFEST_FORCE,
        AFTER_GENERATION_RENAME,
        AFTER_HEAD_FILE_FORCE,
        AFTER_HEAD_RENAME,
        AFTER_HEAD_DIRECTORY_SYNC
    }

    interface DirectorySync {
        void sync(Path directory) throws IOException;
    }

    interface ImageValidator {
        /** Must be read-only: validation must never repair or format the image. */
        void validate(Path image) throws IOException;
    }

    interface FaultInjector {
        void hit(FaultPoint point) throws IOException;
    }

    static final class CoreCloseReceipt {
        final int version;
        final int state;
        final int firstError;
        final int lastError;
        final long imageBytes;
        final long readFailures;
        final long writeFailures;
        final long dldiReadFailures;
        final long dldiWriteFailures;
        final boolean readOnly;
        final boolean folderSync;
        final boolean dirty;
        final boolean flushAttempted;
        final boolean flushSucceeded;
        final boolean closeReturned;
        final boolean closeAttempted;
        final boolean closeSucceeded;

        CoreCloseReceipt(int version, int state, int firstError, int lastError,
                         long imageBytes, long readFailures, long writeFailures,
                         long dldiReadFailures, long dldiWriteFailures, boolean readOnly,
                         boolean folderSync, boolean dirty, boolean flushAttempted,
                         boolean flushSucceeded, boolean closeReturned,
                         boolean closeAttempted, boolean closeSucceeded) {
            this.version = version;
            this.state = state;
            this.firstError = firstError;
            this.lastError = lastError;
            this.imageBytes = imageBytes;
            this.readFailures = readFailures;
            this.writeFailures = writeFailures;
            this.dldiReadFailures = dldiReadFailures;
            this.dldiWriteFailures = dldiWriteFailures;
            this.readOnly = readOnly;
            this.folderSync = folderSync;
            this.dirty = dirty;
            this.flushAttempted = flushAttempted;
            this.flushSucceeded = flushSucceeded;
            this.closeReturned = closeReturned;
            this.closeAttempted = closeAttempted;
            this.closeSucceeded = closeSucceeded;
        }

        boolean permitsCommit(long expectedBytes) {
            return version == VERSION && state == 4 && firstError == 0 && lastError == 0 &&
                    imageBytes == expectedBytes && readFailures == 0 && writeFailures == 0 &&
                    dldiReadFailures == 0 && dldiWriteFailures == 0 && !readOnly && !folderSync &&
                    (!dirty || (flushAttempted && flushSucceeded)) && closeReturned &&
                    closeAttempted && closeSucceeded;
        }
    }

    static final class Generation {
        final long sequence;
        final String id;
        final long imageBytes;
        final String imageSha256;
        final Path imageFile;
        final String parentBranchId;
        final String parentGenerationId;
        final String checkpointId;

        Generation(long sequence, String id, long imageBytes, String imageSha256, Path imageFile,
                   String parentBranchId, String parentGenerationId, String checkpointId) {
            this.sequence = sequence;
            this.id = id;
            this.imageBytes = imageBytes;
            this.imageSha256 = imageSha256;
            this.imageFile = imageFile;
            this.parentBranchId = parentBranchId;
            this.parentGenerationId = parentGenerationId;
            this.checkpointId = checkpointId;
        }
    }

    static final class WorkingSession implements AutoCloseable {
        final String id;
        final long baseSequence;
        final String baseGenerationId;
        final Path saveDirectory;
        final Path imageFile;
        private final VirtualSdGenerationStore owner;
        private final Path directory;
        private final LockLease lease;
        private boolean terminal;

        WorkingSession(VirtualSdGenerationStore owner, String id, Generation base,
                       Path directory, LockLease lease) {
            this.owner = owner;
            this.id = id;
            this.baseSequence = base.sequence;
            this.baseGenerationId = base.id;
            this.directory = directory;
            this.saveDirectory = directory;
            this.imageFile = directory.resolve(IMAGE_RELATIVE);
            this.lease = lease;
        }

        @Override public void close() throws IOException {
            if (!terminal) owner.abort(this);
        }
    }

    private static final class LockLease implements AutoCloseable {
        final FileChannel channel;
        final FileLock lock;
        private boolean closed;

        LockLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try { lock.release(); } catch (IOException error) { failure = error; }
            try { channel.close(); } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    private final Path root;
    private final Path generationsDirectory;
    private final Path workingDirectory;
    private final Path quarantineDirectory;
    private final CheckpointStore.Identity identity;
    private final String branchId;
    private final long maxImageBytes;
    private final DirectorySync directorySync;
    private final ImageValidator imageValidator;
    private final FaultInjector faultInjector;
    private final StorageCapacity capacity;

    VirtualSdGenerationStore(Path root, CheckpointStore.Identity identity, String branchId,
                             long maxImageBytes, DirectorySync directorySync,
                             ImageValidator imageValidator, FaultInjector faultInjector) {
        this(root, identity, branchId, maxImageBytes, directorySync, imageValidator,
                faultInjector, StorageCapacity.host());
    }

    VirtualSdGenerationStore(Path root, CheckpointStore.Identity identity, String branchId,
                             long maxImageBytes, DirectorySync directorySync,
                             ImageValidator imageValidator, FaultInjector faultInjector,
                             StorageCapacity capacity) {
        if (identity == null || !"ds".equals(identity.console))
            throw new IllegalArgumentException("Virtual SD generations require a DS identity");
        if (branchId == null || !("canonical".equals(branchId) || isUuid(branchId)))
            throw new IllegalArgumentException("Invalid virtual-SD branch identity");
        if (maxImageBytes < 512 || maxImageBytes % 512 != 0)
            throw new IllegalArgumentException("Invalid bounded virtual-SD image size");
        if (directorySync == null || imageValidator == null || faultInjector == null || capacity == null)
            throw new IllegalArgumentException("Virtual-SD durability collaborators are required");
        this.root = root.toAbsolutePath().normalize();
        this.generationsDirectory = this.root.resolve("generations");
        this.workingDirectory = this.root.resolve("working");
        this.quarantineDirectory = this.root.resolve("quarantine");
        this.identity = identity;
        this.branchId = branchId;
        this.maxImageBytes = maxImageBytes;
        this.directorySync = directorySync;
        this.imageValidator = imageValidator;
        this.faultInjector = faultInjector;
        this.capacity = capacity;
    }

    synchronized Generation initialize(Path preformattedImage) throws IOException {
        return initialize(preformattedImage, "none", "none");
    }

    synchronized Generation initialize(Path preformattedImage, String parentBranchId,
                                        String parentGenerationId) throws IOException {
        ensureDirectories();
        validateParent(parentBranchId, parentGenerationId);
        try (LockLease ignored = acquireLock()) {
            quarantineAbandonedWorking();
            if (Files.exists(root.resolve("HEAD.0"), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(root.resolve("HEAD.1"), LinkOption.NOFOLLOW_LINKS) ||
                    hasGenerationDirectories())
                throw new IOException("Virtual-SD store is already initialized or has orphan generations");

            Path source = preformattedImage.toAbsolutePath().normalize();
            requireRegularFile(source);
            long imageBytes = Files.size(source);
            validateImageBounds(imageBytes);
            capacity.require(root, imageBytes, "the immutable virtual-SD baseline");
            imageValidator.validate(source);

            String generationId = UUID.randomUUID().toString();
            Path stage = workingDirectory.resolve("bootstrap-" + generationId);
            Path image = stage.resolve(IMAGE_RELATIVE);
            Files.createDirectories(image.getParent());
            copyAndForce(source, image);
            imageValidator.validate(image);
            faultInjector.hit(FaultPoint.AFTER_BOOTSTRAP_IMAGE_FORCE);

            String imageSha = sha256(image);
            byte[] manifest = encodeManifest(1, generationId, imageBytes, imageSha,
                    parentBranchId, parentGenerationId, "none");
            writeVerified(stage.resolve(MANIFEST_NAME), manifest);
            directorySync.sync(image.getParent());
            directorySync.sync(stage);

            Path published = generationsDirectory.resolve("gen-" + generationId);
            moveAtomic(stage, published, false);
            directorySync.sync(workingDirectory);
            directorySync.sync(generationsDirectory);
            faultInjector.hit(FaultPoint.AFTER_BOOTSTRAP_GENERATION_RENAME);
            writeHead(1, generationId, sha256(manifest));
            Generation current = readCurrentLocked();
            if (current == null || current.sequence != 1 || !current.id.equals(generationId))
                throw new IOException("Bootstrap generation did not become the verified head");
            return current;
        }
    }

    synchronized WorkingSession openSession() throws IOException {
        ensureDirectories();
        LockLease lease = acquireLock();
        try {
            Generation base = readCurrentLocked();
            if (base == null) throw new IOException("Virtual-SD store has no committed baseline");
            quarantineAbandonedWorking();
            capacity.require(root, base.imageBytes, "an isolated virtual-SD working image");

            String sessionId = UUID.randomUUID().toString();
            Path directory = workingDirectory.resolve("session-" + sessionId);
            Path image = directory.resolve(IMAGE_RELATIVE);
            Files.createDirectories(image.getParent());
            copyAndForce(base.imageFile, image);
            if (!base.imageSha256.equals(sha256(image)))
                throw new IOException("Working virtual-SD copy failed independent readback");
            directorySync.sync(image.getParent());
            directorySync.sync(directory);
            directorySync.sync(workingDirectory);
            faultInjector.hit(FaultPoint.AFTER_WORKING_IMAGE_FORCE);
            return new WorkingSession(this, sessionId, base, directory, lease);
        } catch (IOException | RuntimeException failure) {
            try { lease.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    synchronized Generation commit(WorkingSession session, CoreCloseReceipt receipt) throws IOException {
        return commit(session, receipt, "none");
    }

    synchronized Generation commit(WorkingSession session, CoreCloseReceipt receipt,
                                   String checkpointId) throws IOException {
        requireSession(session);
        try {
            Generation current = readCurrentLocked();
            if (current == null || current.sequence != session.baseSequence ||
                    !current.id.equals(session.baseGenerationId))
                throw new IOException("Virtual-SD base generation changed during the active session");
            if (receipt == null || !receipt.permitsCommit(current.imageBytes))
                throw new IOException("Native DLDI close receipt is not safe to commit");
            if (!("none".equals(checkpointId) || isUuid(checkpointId)))
                throw new IOException("Invalid checkpoint reference for virtual-SD generation");

            validateWorkingTree(session.directory, false);
            requireRegularFile(session.imageFile);
            long imageBytes = Files.size(session.imageFile);
            validateImageBounds(imageBytes);
            if (imageBytes != receipt.imageBytes)
                throw new IOException("Native close receipt and working image size disagree");
            forceFile(session.imageFile);
            faultInjector.hit(FaultPoint.AFTER_COMMIT_IMAGE_FORCE);
            imageValidator.validate(session.imageFile);
            String imageSha = sha256(session.imageFile);

            long sequence = current.sequence + 1;
            String generationId = UUID.randomUUID().toString();
            byte[] manifest = encodeManifest(sequence, generationId, imageBytes, imageSha,
                    branchId, current.id, checkpointId);
            writeVerified(session.directory.resolve(MANIFEST_NAME), manifest);
            directorySync.sync(session.imageFile.getParent());
            directorySync.sync(session.directory);
            faultInjector.hit(FaultPoint.AFTER_MANIFEST_FORCE);

            Path published = generationsDirectory.resolve("gen-" + generationId);
            moveAtomic(session.directory, published, false);
            session.terminal = true;
            directorySync.sync(workingDirectory);
            directorySync.sync(generationsDirectory);
            faultInjector.hit(FaultPoint.AFTER_GENERATION_RENAME);

            writeHead(sequence, generationId, sha256(manifest));
            Generation committed = readCurrentLocked();
            if (committed == null || committed.sequence != sequence || !committed.id.equals(generationId))
                throw new IOException("New virtual-SD generation failed committed-head readback");
            return committed;
        } finally {
            session.terminal = true;
            session.lease.close();
        }
    }

    synchronized void abort(WorkingSession session) throws IOException {
        requireSession(session);
        IOException failure = null;
        try {
            if (Files.exists(session.directory, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(session.directory);
                Path quarantined = quarantineDirectory.resolve("aborted-" + session.id);
                moveAtomic(session.directory, quarantined, false);
                directorySync.sync(quarantineDirectory);
                directorySync.sync(workingDirectory);
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            session.terminal = true;
            try { session.lease.close(); } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    synchronized Generation current() throws IOException {
        ensureDirectories();
        try (LockLease ignored = acquireLock()) { return readCurrentLocked(); }
    }

    private void writeHead(long sequence, String generationId, String manifestSha) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("format", HEAD_FORMAT);
        fields.put("version", Integer.toString(VERSION));
        fields.put("sequence", Long.toString(sequence));
        fields.put("generation_id", generationId);
        fields.put("manifest_sha256", manifestSha);
        byte[] encoded = encodeRecord(HEAD_KEYS, fields, "head_sha256");
        int slot = (int) (sequence & 1L);
        Path temporary = root.resolve(".HEAD." + slot + "." + UUID.randomUUID() + ".tmp");
        writeVerified(temporary, encoded);
        faultInjector.hit(FaultPoint.AFTER_HEAD_FILE_FORCE);
        Path target = root.resolve("HEAD." + slot);
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symlinked virtual-SD head slot");
        moveAtomic(temporary, target, true);
        faultInjector.hit(FaultPoint.AFTER_HEAD_RENAME);
        directorySync.sync(root);
        faultInjector.hit(FaultPoint.AFTER_HEAD_DIRECTORY_SYNC);
    }

    private Generation readCurrentLocked() throws IOException {
        quarantineStaleHeadTemps();
        List<Generation> valid = new ArrayList<>();
        boolean anyHead = false;
        IOException firstFailure = null;
        for (int slot = 0; slot < 2; slot++) {
            Path path = root.resolve("HEAD." + slot);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            anyHead = true;
            try { valid.add(readHead(path)); }
            catch (IOException error) { if (firstFailure == null) firstFailure = error; }
        }
        if (valid.isEmpty()) {
            if (anyHead) throw new IOException("Both virtual-SD head slots are invalid", firstFailure);
            if (hasGenerationDirectories())
                throw new IOException("Orphan virtual-SD generations exist without a committed head");
            return null;
        }
        if (valid.size() == 2 && valid.get(0).sequence == valid.get(1).sequence &&
                !valid.get(0).id.equals(valid.get(1).id))
            throw new IOException("Conflicting virtual-SD head slots have the same sequence");
        valid.sort((left, right) -> Long.compare(right.sequence, left.sequence));
        return valid.get(0);
    }

    private Generation readHead(Path headFile) throws IOException {
        requireRegularFile(headFile);
        Map<String, String> fields = decodeRecord(headFile, HEAD_KEYS, "head_sha256");
        if (!HEAD_FORMAT.equals(fields.get("format")) ||
                !Integer.toString(VERSION).equals(fields.get("version")))
            throw new IOException("Unsupported virtual-SD head format");
        long sequence = parsePositiveLong(fields.get("sequence"), "head sequence");
        String generationId = requireUuid(fields.get("generation_id"));
        String manifestSha = requireSha256(fields.get("manifest_sha256"));
        return readGeneration(sequence, generationId, manifestSha);
    }

    private Generation readGeneration(long expectedSequence, String generationId,
                                      String expectedManifestSha) throws IOException {
        Path directory = generationsDirectory.resolve("gen-" + generationId);
        requireDirectory(directory);
        Path manifestFile = directory.resolve(MANIFEST_NAME);
        requireRegularFile(manifestFile);
        byte[] manifestBytes = readBounded(manifestFile);
        if (!sha256(manifestBytes).equals(expectedManifestSha))
            throw new IOException("Virtual-SD manifest hash mismatch");
        Map<String, String> fields = decodeRecord(manifestFile, MANIFEST_KEYS, "manifest_sha256");
        validateManifestIdentity(fields, expectedSequence, generationId);
        Path image = directory.resolve(IMAGE_RELATIVE);
        requireRegularFile(image);
        long imageBytes = parsePositiveLong(fields.get("image_bytes"), "image size");
        validateImageBounds(imageBytes);
        if (Files.size(image) != imageBytes || !sha256(image).equals(fields.get("image_sha256")))
            throw new IOException("Virtual-SD generation payload failed size/hash verification");
        validateGenerationTree(directory);
        imageValidator.validate(image);
        return new Generation(expectedSequence, generationId, imageBytes,
                fields.get("image_sha256"), image, fields.get("parent_branch_id"),
                fields.get("parent_generation_id"), fields.get("checkpoint_id"));
    }

    private void validateManifestIdentity(Map<String, String> fields, long sequence,
                                          String generationId) throws IOException {
        if (!FORMAT.equals(fields.get("format")) ||
                !Integer.toString(VERSION).equals(fields.get("version")) ||
                !"committed".equals(fields.get("status")) ||
                parsePositiveLong(fields.get("sequence"), "manifest sequence") != sequence ||
                !generationId.equals(fields.get("generation_id")) ||
                !identity.console.equals(fields.get("console")) ||
                !identity.romSha256.equals(fields.get("rom_sha256")) ||
                !identity.coreId.equals(fields.get("core_id")) ||
                !identity.coreRevision.equals(fields.get("core_revision")) ||
                !identity.coreFrontendRevision.equals(fields.get("core_frontend_revision")) ||
                !identity.coreBinarySha256.equals(fields.get("core_binary_sha256")) ||
                !identity.abi.equals(fields.get("abi")) ||
                !Integer.toString(identity.libretroApi).equals(fields.get("libretro_api")) ||
                !branchId.equals(fields.get("branch_id")) ||
                !IMAGE_RELATIVE.equals(fields.get("image_path")) ||
                !"disabled".equals(fields.get("folder_sync")))
            throw new IOException("Virtual-SD generation belongs to a different ROM/core/branch");
        validateParent(fields.get("parent_branch_id"), fields.get("parent_generation_id"));
        String checkpointId = fields.get("checkpoint_id");
        if (!("none".equals(checkpointId) || isUuid(checkpointId)))
            throw new IOException("Invalid checkpoint reference in virtual-SD generation");
        if (sequence > 1 && (!branchId.equals(fields.get("parent_branch_id")) ||
                !isUuid(fields.get("parent_generation_id"))))
            throw new IOException("Virtual-SD generation parent chain is inconsistent");
        requireSha256(fields.get("image_sha256"));
    }

    private byte[] encodeManifest(long sequence, String generationId, long imageBytes,
                                  String imageSha, String parentBranchId,
                                  String parentGenerationId, String checkpointId) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("format", FORMAT);
        fields.put("version", Integer.toString(VERSION));
        fields.put("status", "committed");
        fields.put("sequence", Long.toString(sequence));
        fields.put("generation_id", generationId);
        fields.put("console", identity.console);
        fields.put("rom_sha256", identity.romSha256);
        fields.put("core_id", identity.coreId);
        fields.put("core_revision", identity.coreRevision);
        fields.put("core_frontend_revision", identity.coreFrontendRevision);
        fields.put("core_binary_sha256", identity.coreBinarySha256);
        fields.put("abi", identity.abi);
        fields.put("libretro_api", Integer.toString(identity.libretroApi));
        fields.put("branch_id", branchId);
        fields.put("image_path", IMAGE_RELATIVE);
        fields.put("parent_branch_id", parentBranchId);
        fields.put("parent_generation_id", parentGenerationId);
        fields.put("checkpoint_id", checkpointId);
        fields.put("image_bytes", Long.toString(imageBytes));
        fields.put("image_sha256", imageSha);
        fields.put("folder_sync", "disabled");
        return encodeRecord(MANIFEST_KEYS, fields, "manifest_sha256");
    }

    private static byte[] encodeRecord(String[] keys, Map<String, String> fields,
                                       String checksumKey) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String key : keys) {
            String value = fields.get(key);
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('=') >= 0)
                throw new IOException("Invalid virtual-SD record field: " + key);
            body.append(key).append('=').append(value).append('\n');
        }
        String prefix = body.toString();
        return (prefix + checksumKey + "=" + sha256(prefix.getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> decodeRecord(Path file, String[] keys,
                                                     String checksumKey) throws IOException {
        byte[] bytes = readBounded(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length != keys.length + 2 || !lines[lines.length - 1].isEmpty())
            throw new IOException("Malformed virtual-SD record");
        Map<String, String> fields = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            String prefix = keys[i] + "=";
            if (!lines[i].startsWith(prefix)) throw new IOException("Unexpected virtual-SD record field");
            String value = lines[i].substring(prefix.length());
            if (value.isEmpty() || fields.put(keys[i], value) != null)
                throw new IOException("Empty or duplicate virtual-SD record field");
            body.append(lines[i]).append('\n');
        }
        String checksumPrefix = checksumKey + "=";
        if (!lines[keys.length].startsWith(checksumPrefix) ||
                !sha256(body.toString().getBytes(StandardCharsets.UTF_8))
                        .equals(lines[keys.length].substring(checksumPrefix.length())))
            throw new IOException("Virtual-SD record checksum mismatch");
        return fields;
    }

    private void validateWorkingTree(Path directory, boolean hasManifest) throws IOException {
        requireDirectory(directory);
        Set<String> expectedRoot = hasManifest ? Set.of("melonDS DS", MANIFEST_NAME) : Set.of("melonDS DS");
        assertOnlyChildren(directory, expectedRoot);
        Path imageDirectory = directory.resolve("melonDS DS");
        requireDirectory(imageDirectory);
        assertOnlyChildren(imageDirectory, Set.of("dldi_sd_card.bin"));
    }

    private void validateGenerationTree(Path directory) throws IOException {
        validateWorkingTree(directory, true);
    }

    private void quarantineAbandonedWorking() throws IOException {
        boolean moved = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workingDirectory)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) throw new IOException("Symlink found in virtual-SD working area");
                Path target = quarantineDirectory.resolve("abandoned-" + entry.getFileName() + "-" + UUID.randomUUID());
                moveAtomic(entry, target, false);
                moved = true;
            }
        }
        if (moved) {
            directorySync.sync(quarantineDirectory);
            directorySync.sync(workingDirectory);
        }
    }

    private void quarantineStaleHeadTemps() throws IOException {
        boolean moved = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".HEAD.") || !name.endsWith(".tmp")) continue;
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected object in virtual-SD head staging area");
                Path target = quarantineDirectory.resolve("head-temp-" + UUID.randomUUID());
                moveAtomic(entry, target, false);
                moved = true;
            }
        }
        if (moved) {
            directorySync.sync(quarantineDirectory);
            directorySync.sync(root);
        }
    }

    private boolean hasGenerationDirectories() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(generationsDirectory)) {
            for (Path entry : entries) return true;
        }
        return false;
    }

    private void ensureDirectories() throws IOException {
        rejectSymlinkPath(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = root.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createDirectory(root);
            if (parent != null) directorySync.sync(parent);
        }
        requireDirectory(root);
        createDirectory(generationsDirectory);
        createDirectory(workingDirectory);
        createDirectory(quarantineDirectory);
        directorySync.sync(root);
    }

    private void createDirectory(Path directory) throws IOException {
        rejectSymlinkPath(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        requireDirectory(directory);
    }

    private LockLease acquireLock() throws IOException {
        Path lockPath = root.resolve("ACTIVE.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Refusing symlinked virtual-SD lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Virtual-SD store already has an active writer");
            return new LockLease(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("Virtual-SD store already has an active writer", busy);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void requireSession(WorkingSession session) throws IOException {
        if (session == null || session.owner != this || session.terminal || !session.lease.lock.isValid())
            throw new IOException("Invalid or already-closed virtual-SD working session");
    }

    private void validateImageBounds(long bytes) throws IOException {
        if (bytes < 512 || bytes > maxImageBytes || bytes % 512 != 0)
            throw new IOException("Virtual-SD image size is outside its bounded sector-aligned range");
    }

    private static void validateParent(String parentBranchId, String parentGenerationId)
            throws IOException {
        if ("none".equals(parentBranchId) && "none".equals(parentGenerationId)) return;
        if (parentBranchId == null || !("canonical".equals(parentBranchId) || isUuid(parentBranchId)) ||
                !isUuid(parentGenerationId))
            throw new IOException("Invalid virtual-SD parent generation reference");
    }

    private static void copyAndForce(Path source, Path destination) throws IOException {
        requireRegularFile(source);
        Files.copy(source, destination);
        if (!destination.toFile().setWritable(true, true) && !Files.isWritable(destination))
            throw new IOException("Virtual-SD working image is not writable");
        forceFile(destination);
        if (Files.size(source) != Files.size(destination) || !sha256(source).equals(sha256(destination)))
            throw new IOException("Virtual-SD image copy failed readback verification");
    }

    private static void writeVerified(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        if (!Arrays.equals(bytes, Files.readAllBytes(target)))
            throw new IOException("Virtual-SD metadata failed write/readback verification");
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveAtomic(Path source, Path destination, boolean replace) throws IOException {
        try {
            if (replace)
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Virtual-SD transaction requires same-filesystem atomic rename", unsupported);
        }
    }

    private static void assertOnlyChildren(Path directory, Set<String> expected) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!expected.contains(entry.getFileName().toString()) || Files.isSymbolicLink(entry))
                    throw new IOException("Unexpected or symlinked virtual-SD payload entry");
            }
        }
        for (String name : expected) {
            if (!Files.exists(directory.resolve(name), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing virtual-SD payload entry: " + name);
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        rejectSymlinkPath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a regular virtual-SD file");
    }

    private static void requireDirectory(Path path) throws IOException {
        rejectSymlinkPath(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected a virtual-SD directory");
    }

    private static void rejectSymlinkPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Symlink in virtual-SD storage path");
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        requireRegularFile(path);
        long size = Files.size(path);
        if (size < 1 || size > MAX_RECORD_BYTES) throw new IOException("Invalid virtual-SD metadata size");
        return Files.readAllBytes(path);
    }

    private static long parsePositiveLong(String value, String label) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid " + label, invalid);
        }
    }

    private static String requireUuid(String value) throws IOException {
        if (!isUuid(value)) throw new IOException("Invalid virtual-SD generation identifier");
        return value;
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String requireSha256(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid virtual-SD SHA-256");
        return value;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
