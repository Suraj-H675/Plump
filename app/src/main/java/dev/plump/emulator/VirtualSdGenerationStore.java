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
    private static final String PENDING_FORMAT = "PLUMP_VIRTUAL_SD_PENDING_TRANSACTION";
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
    private static final String[] PENDING_KEYS = {
            "format", "version", "branch_id", "candidate_generation_id", "expected_head_sequence",
            "expected_head_generation_id", "expected_head_manifest_sha256", "expected_head_sha256",
            "target_head_before_exists", "target_head_before_sha256", "candidate_parent_generation_id",
            "candidate_sequence", "image_sha256", "manifest_sha256"
    };

    enum FaultPoint {
        BEFORE_BOOTSTRAP_IMAGE_COPY,
        AFTER_BOOTSTRAP_IMAGE_FORCE,
        BEFORE_BOOTSTRAP_GENERATION_RENAME,
        AFTER_BOOTSTRAP_GENERATION_RENAME,
        BEFORE_WORKING_IMAGE_COPY,
        AFTER_WORKING_IMAGE_FORCE,
        BEFORE_COMMIT_IMAGE_FORCE,
        AFTER_COMMIT_IMAGE_FORCE,
        AFTER_MANIFEST_FORCE,
        BEFORE_GENERATION_RENAME,
        AFTER_GENERATION_RENAME,
        AFTER_HEAD_FILE_FORCE,
        BEFORE_HEAD_RENAME,
        AFTER_HEAD_RENAME,
        BEFORE_HEAD_DIRECTORY_SYNC,
        DURING_HEAD_DIRECTORY_SYNC,
        AFTER_HEAD_DIRECTORY_SYNC
    }

    /** NO_PENDING means reconciliation found no operation to classify; it is not a commit outcome. */
    enum Outcome { NOT_COMMITTED, COMMITTED, RECOVERY_REQUIRED, NO_PENDING }

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

    interface TransactionControl {
        void checkCancellation() throws IOException;
        boolean authorizePublication(String candidateGenerationId);
        void publicationResolved(String candidateGenerationId, Outcome outcome);
    }

    static final TransactionControl NO_CANCELLATION = new TransactionControl() {
        @Override public void checkCancellation() { }
        @Override public boolean authorizePublication(String candidateGenerationId) { return true; }
        @Override public void publicationResolved(String candidateGenerationId, Outcome outcome) { }
    };

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
        final String manifestSha256;
        final String headSha256;
        final Path imageFile;
        final String parentBranchId;
        final String parentGenerationId;
        final String checkpointId;

        Generation(long sequence, String id, long imageBytes, String imageSha256,
                   String manifestSha256, String headSha256, Path imageFile,
                   String parentBranchId, String parentGenerationId, String checkpointId) {
            this.sequence = sequence;
            this.id = id;
            this.imageBytes = imageBytes;
            this.imageSha256 = imageSha256;
            this.manifestSha256 = manifestSha256;
            this.headSha256 = headSha256;
            this.imageFile = imageFile;
            this.parentBranchId = parentBranchId;
            this.parentGenerationId = parentGenerationId;
            this.checkpointId = checkpointId;
        }
    }

    static final class TransactionIdentity {
        final String candidateGenerationId;
        final long expectedHeadSequence;
        final String expectedHeadGenerationId;
        final String expectedHeadManifestSha256;
        final String expectedHeadSha256;
        final boolean targetHeadBeforeExists;
        final String targetHeadBeforeSha256;
        final String candidateParentGenerationId;
        final long candidateSequence;
        final String imageSha256;
        final String manifestSha256;

        TransactionIdentity(String candidateGenerationId, long expectedHeadSequence,
                            String expectedHeadGenerationId, String expectedHeadManifestSha256,
                            String expectedHeadSha256, boolean targetHeadBeforeExists,
                            String targetHeadBeforeSha256, String candidateParentGenerationId,
                            long candidateSequence,
                            String imageSha256, String manifestSha256) {
            this.candidateGenerationId = candidateGenerationId;
            this.expectedHeadSequence = expectedHeadSequence;
            this.expectedHeadGenerationId = expectedHeadGenerationId;
            this.expectedHeadManifestSha256 = expectedHeadManifestSha256;
            this.expectedHeadSha256 = expectedHeadSha256;
            this.targetHeadBeforeExists = targetHeadBeforeExists;
            this.targetHeadBeforeSha256 = targetHeadBeforeSha256;
            this.candidateParentGenerationId = candidateParentGenerationId;
            this.candidateSequence = candidateSequence;
            this.imageSha256 = imageSha256;
            this.manifestSha256 = manifestSha256;
        }
    }

    static final class TransactionResult {
        final Outcome outcome;
        final TransactionIdentity identity;
        final Generation generation;
        final IOException failure;
        final boolean headRenameAttempted;
        final boolean directorySyncConfirmed;
        final boolean cleanupResolved;

        TransactionResult(Outcome outcome, TransactionIdentity identity, Generation generation,
                          IOException failure, boolean headRenameAttempted,
                          boolean directorySyncConfirmed, boolean cleanupResolved) {
            this.outcome = outcome;
            this.identity = identity;
            this.generation = generation;
            this.failure = failure;
            this.headRenameAttempted = headRenameAttempted;
            this.directorySyncConfirmed = directorySyncConfirmed;
            this.cleanupResolved = cleanupResolved;
        }
    }

    private static final class PublicationProgress {
        boolean headRenameAttempted;
        boolean directorySyncConfirmed;
    }

    static final class WorkingSession implements AutoCloseable {
        final String id;
        final long baseSequence;
        final String baseGenerationId;
        final String baseManifestSha256;
        final String baseHeadSha256;
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
            this.baseManifestSha256 = base.manifestSha256;
            this.baseHeadSha256 = base.headSha256;
            this.directory = directory;
            this.saveDirectory = directory;
            this.imageFile = directory.resolve(IMAGE_RELATIVE);
            this.lease = lease;
        }

        @Override public void close() throws IOException {
            if (!terminal) owner.abort(this);
        }

        boolean isTerminal() { return terminal; }
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
    private LockLease recoveryLease;
    private WorkingSession recoverySession;
    private TransactionIdentity recoveryIdentity;

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
        TransactionResult result = initializeTransaction(preformattedImage, parentBranchId,
                parentGenerationId, UUID.randomUUID().toString(), NO_CANCELLATION);
        return requireCommitted(result);
    }

    synchronized TransactionResult initializeTransaction(Path preformattedImage,
            String parentBranchId, String parentGenerationId, String candidateGenerationId,
            TransactionControl control) {
        control = control == null ? NO_CANCELLATION : control;
        LockLease lease = null;
        boolean retainLease = false;
        PublicationProgress progress = new PublicationProgress();
        boolean emptyStoreProven = false;
        Generation oldHead = null;
        TransactionIdentity operation = null;
        Generation published = null;
        IOException failure = null;
        Outcome outcome = Outcome.RECOVERY_REQUIRED;
        boolean unresolvedPending = false;
        try {
            requireUuid(candidateGenerationId);
            ensureDirectories();
            requireNoRetainedRecoveryLease();
            lease = acquireLock();
            if (Files.exists(pendingPath(), LinkOption.NOFOLLOW_LINKS)) {
                unresolvedPending = true;
                throw new IOException("An earlier virtual-SD transaction requires strict reconciliation");
            }
            control.checkCancellation();
            oldHead = readCurrentStrictLocked();
            quarantineStaleHeadTemps();
            if (oldHead != null || hasGenerationDirectories())
                throw new IOException("Virtual-SD store is already initialized or has orphan generations");
            emptyStoreProven = true;
            control.checkCancellation();
            validateParent(parentBranchId, parentGenerationId);

            Path source = preformattedImage.toAbsolutePath().normalize();
            requireRegularFile(source);
            long imageBytes = Files.size(source);
            validateImageBounds(imageBytes);
            capacity.require(root, imageBytes, "the immutable virtual-SD baseline");
            imageValidator.validate(source);
            control.checkCancellation();

            Path stage = workingDirectory.resolve("bootstrap-" + candidateGenerationId);
            Path image = stage.resolve(IMAGE_RELATIVE);
            Files.createDirectories(image.getParent());
            faultInjector.hit(FaultPoint.BEFORE_BOOTSTRAP_IMAGE_COPY);
            copyAndForce(source, image, control);
            control.checkCancellation();
            imageValidator.validate(image);
            faultInjector.hit(FaultPoint.AFTER_BOOTSTRAP_IMAGE_FORCE);
            control.checkCancellation();

            String imageSha = sha256(image, control);
            byte[] manifest = encodeManifest(1, candidateGenerationId, imageBytes, imageSha,
                    parentBranchId, parentGenerationId, "none");
            String manifestSha = sha256(manifest);
            HeadSnapshot targetBefore = captureHeadSnapshot(1);
            operation = new TransactionIdentity(candidateGenerationId, 0, "none", "none", "none",
                    targetBefore.exists, targetBefore.sha256, parentGenerationId, 1,
                    imageSha, manifestSha);
            writeVerified(stage.resolve(MANIFEST_NAME), manifest);
            directorySync.sync(image.getParent());
            directorySync.sync(stage);
            control.checkCancellation();

            Path generation = generationsDirectory.resolve("gen-" + candidateGenerationId);
            writePendingTransaction(operation);
            control.checkCancellation();
            faultInjector.hit(FaultPoint.BEFORE_BOOTSTRAP_GENERATION_RENAME);
            control.checkCancellation();
            moveAtomic(stage, generation, false);
            directorySync.sync(workingDirectory);
            directorySync.sync(generationsDirectory);
            faultInjector.hit(FaultPoint.AFTER_BOOTSTRAP_GENERATION_RENAME);
            control.checkCancellation();
            publishHead(1, candidateGenerationId, manifestSha, operation, control, progress);
            published = verifyCandidate(operation);
            if (published == null) throw new IOException("Bootstrap candidate failed strict HEAD verification");
            outcome = Outcome.COMMITTED;
        } catch (IOException | RuntimeException error) {
            failure = asIOException("Virtual-SD baseline publication failed", error);
            if (unresolvedPending) {
                outcome = Outcome.RECOVERY_REQUIRED;
            } else if (progress.headRenameAttempted) {
                if (progress.directorySyncConfirmed && operation != null && (published = verifyCandidate(operation)) != null)
                    outcome = Outcome.COMMITTED;
                else if (!progress.directorySyncConfirmed && operation != null && candidateHeadMatches(operation))
                    outcome = Outcome.RECOVERY_REQUIRED;
                else if (emptyStoreProven && matchesExpectedHead(operation)) {
                    outcome = quarantineBootstrapCandidate(candidateGenerationId, failure)
                            ? Outcome.NOT_COMMITTED : Outcome.RECOVERY_REQUIRED;
                }
            } else if (emptyStoreProven || (oldHead == null && noHeadExists())) {
                outcome = quarantineBootstrapCandidate(candidateGenerationId, failure)
                        ? Outcome.NOT_COMMITTED : Outcome.RECOVERY_REQUIRED;
            } else if (matchesExpectedHead(oldHead)) {
                outcome = Outcome.NOT_COMMITTED;
            }
        }

        boolean cleanupResolved = outcome != Outcome.RECOVERY_REQUIRED;
        if (operation != null && cleanupResolved) {
            try { finishPendingTransaction(operation, outcome, null); }
            catch (IOException cleanupFailure) {
                cleanupResolved = false;
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (operation != null) {
            try { control.publicationResolved(candidateGenerationId, outcome); }
            catch (RuntimeException callbackFailure) {
                cleanupResolved = false;
                if (failure == null) failure = new IOException("Publication result callback failed", callbackFailure);
                else failure.addSuppressed(callbackFailure);
            }
        }
        if ((!cleanupResolved || outcome == Outcome.RECOVERY_REQUIRED) && lease != null) {
            recoveryLease = lease;
            recoveryIdentity = operation;
            retainLease = true;
        }
        if (lease != null && !retainLease) {
            try { lease.close(); }
            catch (IOException closeFailure) {
                cleanupResolved = false;
                recoveryLease = lease;
                recoveryIdentity = operation;
                retainLease = true;
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return new TransactionResult(outcome, operation, published, failure,
                progress.headRenameAttempted, progress.directorySyncConfirmed,
                cleanupResolved && !retainLease);
    }

    synchronized WorkingSession openSession() throws IOException {
        return openSession(NO_CANCELLATION);
    }

    synchronized WorkingSession openSession(TransactionControl control) throws IOException {
        control = control == null ? NO_CANCELLATION : control;
        control.checkCancellation();
        ensureDirectories();
        requireNoRetainedRecoveryLease();
        LockLease lease = acquireLock();
        Path partialDirectory = null;
        try {
            requireNoPendingTransaction();
            Generation base = readCurrentStrictLocked();
            if (base == null) throw new IOException("Virtual-SD store has no committed baseline");
            quarantineStaleHeadTemps();
            quarantineAbandonedWorking();
            capacity.require(root, base.imageBytes, "an isolated virtual-SD working image");
            control.checkCancellation();

            String sessionId = UUID.randomUUID().toString();
            Path directory = workingDirectory.resolve("session-" + sessionId);
            partialDirectory = directory;
            Path image = directory.resolve(IMAGE_RELATIVE);
            Files.createDirectories(image.getParent());
            faultInjector.hit(FaultPoint.BEFORE_WORKING_IMAGE_COPY);
            copyAndForce(base.imageFile, image, control);
            if (!base.imageSha256.equals(sha256(image, control)))
                throw new IOException("Working virtual-SD copy failed independent readback");
            control.checkCancellation();
            directorySync.sync(image.getParent());
            directorySync.sync(directory);
            directorySync.sync(workingDirectory);
            faultInjector.hit(FaultPoint.AFTER_WORKING_IMAGE_FORCE);
            return new WorkingSession(this, sessionId, base, directory, lease);
        } catch (IOException | RuntimeException failure) {
            if (partialDirectory != null && Files.exists(partialDirectory, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveAtomic(partialDirectory, quarantineDirectory.resolve("cancelled-" +
                            partialDirectory.getFileName()), false);
                    directorySync.sync(workingDirectory);
                    directorySync.sync(quarantineDirectory);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try { lease.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    synchronized Generation commit(WorkingSession session, CoreCloseReceipt receipt) throws IOException {
        return commit(session, receipt, "none");
    }

    synchronized Generation commit(WorkingSession session, CoreCloseReceipt receipt,
                                   String checkpointId) throws IOException {
        TransactionResult result = commitTransaction(session, receipt, checkpointId,
                UUID.randomUUID().toString(), NO_CANCELLATION);
        return requireCommitted(result);
    }

    synchronized TransactionResult commitTransaction(WorkingSession session,
            CoreCloseReceipt receipt, String checkpointId, String candidateGenerationId,
            TransactionControl control) {
        control = control == null ? NO_CANCELLATION : control;
        boolean retainLease = false;
        PublicationProgress progress = new PublicationProgress();
        Generation oldHead = null;
        TransactionIdentity operation = null;
        Generation published = null;
        IOException failure = null;
        Outcome outcome = Outcome.RECOVERY_REQUIRED;
        boolean unresolvedPending = false;
        try {
            requireSession(session);
            requireUuid(candidateGenerationId);
            if (Files.exists(pendingPath(), LinkOption.NOFOLLOW_LINKS)) {
                unresolvedPending = true;
                throw new IOException("An earlier virtual-SD transaction requires strict reconciliation");
            }
            control.checkCancellation();
            oldHead = readCurrentStrictLocked();
            if (oldHead == null || oldHead.sequence != session.baseSequence ||
                    !oldHead.id.equals(session.baseGenerationId) ||
                    !oldHead.manifestSha256.equals(session.baseManifestSha256) ||
                    !oldHead.headSha256.equals(session.baseHeadSha256))
                throw new IOException("Virtual-SD base authority changed during the active session");
            if (receipt == null || !receipt.permitsCommit(oldHead.imageBytes))
                throw new IOException("Native DLDI close receipt is not safe to commit");
            if (!("none".equals(checkpointId) || isUuid(checkpointId)))
                throw new IOException("Invalid checkpoint reference for virtual-SD generation");

            control.checkCancellation();
            validateWorkingTree(session.directory, false);
            requireRegularFile(session.imageFile);
            long imageBytes = Files.size(session.imageFile);
            validateImageBounds(imageBytes);
            if (imageBytes != receipt.imageBytes)
                throw new IOException("Native close receipt and working image size disagree");
            control.checkCancellation();
            faultInjector.hit(FaultPoint.BEFORE_COMMIT_IMAGE_FORCE);
            forceFile(session.imageFile);
            control.checkCancellation();
            faultInjector.hit(FaultPoint.AFTER_COMMIT_IMAGE_FORCE);
            imageValidator.validate(session.imageFile);
            control.checkCancellation();
            String imageSha = sha256(session.imageFile, control);

            if (oldHead.sequence == Long.MAX_VALUE)
                throw new IOException("Virtual-SD generation sequence is exhausted");
            long sequence = oldHead.sequence + 1;
            byte[] manifest = encodeManifest(sequence, candidateGenerationId, imageBytes, imageSha,
                    branchId, oldHead.id, checkpointId);
            String manifestSha = sha256(manifest);
            HeadSnapshot targetBefore = captureHeadSnapshot(sequence);
            operation = new TransactionIdentity(candidateGenerationId, oldHead.sequence,
                    oldHead.id, oldHead.manifestSha256, oldHead.headSha256,
                    targetBefore.exists, targetBefore.sha256, oldHead.id, sequence,
                    imageSha, manifestSha);
            writeVerified(session.directory.resolve(MANIFEST_NAME), manifest);
            directorySync.sync(session.imageFile.getParent());
            directorySync.sync(session.directory);
            control.checkCancellation();
            faultInjector.hit(FaultPoint.AFTER_MANIFEST_FORCE);

            Path generation = generationsDirectory.resolve("gen-" + candidateGenerationId);
            control.checkCancellation();
            writePendingTransaction(operation);
            control.checkCancellation();
            faultInjector.hit(FaultPoint.BEFORE_GENERATION_RENAME);
            control.checkCancellation();
            moveAtomic(session.directory, generation, false);
            directorySync.sync(workingDirectory);
            directorySync.sync(generationsDirectory);
            faultInjector.hit(FaultPoint.AFTER_GENERATION_RENAME);
            control.checkCancellation();

            publishHead(sequence, candidateGenerationId, manifestSha, operation, control, progress);
            published = verifyCandidate(operation);
            if (published == null)
                throw new IOException("New virtual-SD generation failed exact HEAD readback");
            outcome = Outcome.COMMITTED;
        } catch (IOException | RuntimeException error) {
            failure = asIOException("Virtual-SD generation publication failed", error);
            if (unresolvedPending) {
                outcome = Outcome.RECOVERY_REQUIRED;
            } else if (progress.headRenameAttempted) {
                if (progress.directorySyncConfirmed && operation != null && (published = verifyCandidate(operation)) != null)
                    outcome = Outcome.COMMITTED;
                else if (!progress.directorySyncConfirmed && operation != null && candidateHeadMatches(operation))
                    outcome = Outcome.RECOVERY_REQUIRED;
                else if (matchesExpectedHead(operation))
                    outcome = Outcome.NOT_COMMITTED;
            } else if (matchesExpectedHead(operation, oldHead, session)) {
                outcome = Outcome.NOT_COMMITTED;
            }
        }

        boolean cleanupResolved = outcome != Outcome.RECOVERY_REQUIRED;
        if (operation != null && cleanupResolved) {
            try { finishPendingTransaction(operation, outcome, session); }
            catch (IOException cleanupFailure) {
                cleanupResolved = false;
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (operation != null) {
            try { control.publicationResolved(candidateGenerationId, outcome); }
            catch (RuntimeException callbackFailure) {
                cleanupResolved = false;
                if (failure == null) failure = new IOException("Publication result callback failed", callbackFailure);
                else failure.addSuppressed(callbackFailure);
            }
        }
        if (outcome != Outcome.RECOVERY_REQUIRED) session.terminal = true;
        if (outcome == Outcome.RECOVERY_REQUIRED || !cleanupResolved) {
            retainLease = true;
            recoveryLease = session.lease;
            recoverySession = session;
            recoveryIdentity = operation;
        } else {
            try { session.lease.close(); }
            catch (IOException closeFailure) {
                cleanupResolved = false;
                recoveryLease = session.lease;
                recoverySession = session;
                recoveryIdentity = operation;
                retainLease = true;
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return new TransactionResult(outcome, operation, published, failure,
                progress.headRenameAttempted, progress.directorySyncConfirmed,
                cleanupResolved && !retainLease);
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
        requireNoRetainedRecoveryLease();
        try (LockLease ignored = acquireLock()) {
            requireNoPendingTransaction();
            return readCurrentLocked();
        }
    }

    synchronized Generation currentForWrite() throws IOException {
        ensureDirectories();
        requireNoRetainedRecoveryLease();
        try (LockLease ignored = acquireLock()) {
            requireNoPendingTransaction();
            return readCurrentStrictLocked();
        }
    }

    synchronized TransactionResult reconcileStrict(TransactionIdentity identity) {
        return reconcilePendingStrict(identity, NO_CANCELLATION);
    }

    synchronized TransactionResult reconcilePendingStrict() {
        return reconcilePendingStrict(null, NO_CANCELLATION);
    }

    synchronized TransactionResult reconcilePendingStrict(TransactionControl control) {
        return reconcilePendingStrict(null, control);
    }

    private TransactionResult reconcilePendingStrict(TransactionIdentity expected,
            TransactionControl control) {
        control = control == null ? NO_CANCELLATION : control;
        LockLease lease = recoveryLease;
        boolean borrowedLease = lease != null;
        Generation generation = null;
        TransactionIdentity identity = expected;
        IOException failure = null;
        Outcome outcome = Outcome.RECOVERY_REQUIRED;
        boolean directorySyncConfirmed = false;
        boolean cleanupResolved = false;
        try {
            ensureDirectories();
            if (lease == null) lease = acquireLock();
            Path pending = pendingPath();
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                if (expected != null || recoveryIdentity != null) {
                    if (expected != null && recoveryIdentity != null &&
                            !sameIdentity(expected, recoveryIdentity))
                        throw new IOException("Requested recovery identity differs from the retained operation");
                    identity = expected == null ? recoveryIdentity : expected;
                    control.checkCancellation();
                    if (candidateHeadMatches(identity)) {
                        directorySync.sync(root);
                        directorySyncConfirmed = true;
                        generation = verifyCandidate(identity);
                        if (generation == null)
                            throw new IOException("Candidate failed strict verification after cold directory sync");
                        outcome = Outcome.COMMITTED;
                    } else if (matchesExpectedHead(identity)) {
                        outcome = Outcome.NOT_COMMITTED;
                    } else {
                        throw new IOException("Neither the exact parent nor candidate authority is proven");
                    }
                    try {
                        control.checkCancellation();
                        finishPendingTransaction(identity, outcome, null);
                        cleanupResolved = true;
                    } catch (IOException cleanupFailure) {
                        failure = asIOException("Strict recovery proved authority but cleanup is unresolved",
                                cleanupFailure);
                    }
                } else if (borrowedLease) {
                    throw new IOException("Retained writer lease has no exact recovery identity");
                } else {
                    outcome = Outcome.NO_PENDING;
                    cleanupResolved = true;
                }
            } else {
                TransactionIdentity recorded = readPendingTransaction();
                if (expected != null && !sameIdentity(expected, recorded))
                    throw new IOException("Pending operation does not match the exact recovery identity");
                identity = recorded;
                control.checkCancellation();
                if (candidateHeadMatches(identity)) {
                    directorySync.sync(root);
                    directorySyncConfirmed = true;
                    generation = verifyCandidate(identity);
                    if (generation == null)
                        throw new IOException("Candidate failed strict verification after cold directory sync");
                    outcome = Outcome.COMMITTED;
                } else if (matchesExpectedHead(identity)) {
                    outcome = Outcome.NOT_COMMITTED;
                } else {
                    throw new IOException("Neither the exact parent nor candidate authority is proven");
                }

                try {
                    control.checkCancellation();
                    finishPendingTransaction(identity, outcome, null);
                    cleanupResolved = true;
                } catch (IOException cleanupFailure) {
                    failure = asIOException("Strict recovery proved authority but could not clear its marker",
                            cleanupFailure);
                }
            }
        } catch (IOException | RuntimeException error) {
            failure = asIOException("Strict virtual-SD recovery failed", error);
            outcome = Outcome.RECOVERY_REQUIRED;
        }

        if (outcome == Outcome.RECOVERY_REQUIRED || !cleanupResolved) {
            if (lease != null) recoveryLease = lease;
            if (recoverySession != null && outcome != Outcome.RECOVERY_REQUIRED)
                recoverySession.terminal = true;
        } else if (lease != null) {
            if (recoverySession != null) recoverySession.terminal = true;
            try {
                lease.close();
                if (borrowedLease || lease == recoveryLease) {
                    recoveryLease = null;
                    recoverySession = null;
                    recoveryIdentity = null;
                }
            } catch (IOException closeFailure) {
                recoveryLease = lease;
                cleanupResolved = false;
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        return new TransactionResult(outcome, identity, generation, failure, false,
                directorySyncConfirmed, cleanupResolved && recoveryLease == null);
    }

    private void publishHead(long sequence, String generationId, String manifestSha,
                             TransactionIdentity operation, TransactionControl control,
                             PublicationProgress progress) throws IOException {
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
        control.checkCancellation();
        Path target = root.resolve("HEAD." + slot);
        if (Files.isSymbolicLink(target))
            throw new IOException("Refusing symlinked virtual-SD head slot");
        faultInjector.hit(FaultPoint.BEFORE_HEAD_RENAME);
        if (!control.authorizePublication(operation.candidateGenerationId))
            throw new CancelledTransactionException();
        progress.headRenameAttempted = true;
        moveAtomic(temporary, target, true);
        faultInjector.hit(FaultPoint.AFTER_HEAD_RENAME);
        faultInjector.hit(FaultPoint.BEFORE_HEAD_DIRECTORY_SYNC);
        faultInjector.hit(FaultPoint.DURING_HEAD_DIRECTORY_SYNC);
        directorySync.sync(root);
        progress.directorySyncConfirmed = true;
        faultInjector.hit(FaultPoint.AFTER_HEAD_DIRECTORY_SYNC);
    }

    private Path pendingPath() { return root.resolve("PENDING_TRANSACTION.vsd"); }

    private void writePendingTransaction(TransactionIdentity operation) throws IOException {
        Path target = pendingPath();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A pending virtual-SD transaction already exists");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("format", PENDING_FORMAT);
        fields.put("version", Integer.toString(VERSION));
        fields.put("branch_id", branchId);
        fields.put("candidate_generation_id", operation.candidateGenerationId);
        fields.put("expected_head_sequence", Long.toString(operation.expectedHeadSequence));
        fields.put("expected_head_generation_id", operation.expectedHeadGenerationId);
        fields.put("expected_head_manifest_sha256", operation.expectedHeadManifestSha256);
        fields.put("expected_head_sha256", operation.expectedHeadSha256);
        fields.put("target_head_before_exists", Boolean.toString(operation.targetHeadBeforeExists));
        fields.put("target_head_before_sha256", operation.targetHeadBeforeSha256);
        fields.put("candidate_parent_generation_id", operation.candidateParentGenerationId);
        fields.put("candidate_sequence", Long.toString(operation.candidateSequence));
        fields.put("image_sha256", operation.imageSha256);
        fields.put("manifest_sha256", operation.manifestSha256);
        byte[] encoded = encodeRecord(PENDING_KEYS, fields, "pending_sha256");
        Path temporary = root.resolve(".PENDING." + UUID.randomUUID() + ".tmp");
        try {
            writeVerified(temporary, encoded);
            moveAtomic(temporary, target, false);
            directorySync.sync(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private TransactionIdentity readPendingTransaction() throws IOException {
        Path file = pendingPath();
        Map<String, String> fields = decodeRecord(file, PENDING_KEYS, "pending_sha256");
        if (!PENDING_FORMAT.equals(fields.get("format")) ||
                !Integer.toString(VERSION).equals(fields.get("version")) ||
                !branchId.equals(fields.get("branch_id")))
            throw new IOException("Pending transaction belongs to a different format or branch");
        String candidateId = requireUuid(fields.get("candidate_generation_id"));
        long expectedSequence = parseNonnegativeLong(fields.get("expected_head_sequence"), "parent sequence");
        long candidateSequence = parsePositiveLong(fields.get("candidate_sequence"), "candidate sequence");
        if (expectedSequence == Long.MAX_VALUE || candidateSequence != expectedSequence + 1)
            throw new IOException("Pending transaction sequence is inconsistent");
        String expectedId = fields.get("expected_head_generation_id");
        if (expectedSequence == 0) {
            if (!"none".equals(expectedId)) throw new IOException("Pending bootstrap parent is invalid");
        } else requireUuid(expectedId);
        String expectedManifest = fields.get("expected_head_manifest_sha256");
        String expectedHead = fields.get("expected_head_sha256");
        if (expectedSequence == 0) {
            if (!"none".equals(expectedManifest) || !"none".equals(expectedHead))
                throw new IOException("Pending bootstrap head digests are invalid");
        } else {
            requireSha256(expectedManifest);
            requireSha256(expectedHead);
        }
        String targetBefore = fields.get("target_head_before_sha256");
        boolean targetExists;
        try { targetExists = Boolean.parseBoolean(fields.get("target_head_before_exists")); }
        catch (RuntimeException invalid) { throw new IOException("Pending target slot state is invalid", invalid); }
        if (!"true".equals(fields.get("target_head_before_exists")) &&
                !"false".equals(fields.get("target_head_before_exists")))
            throw new IOException("Pending target slot state is invalid");
        if (targetExists) requireSha256(targetBefore);
        else if (!"none".equals(targetBefore)) throw new IOException("Pending target slot digest is invalid");
        String candidateParent = fields.get("candidate_parent_generation_id");
        if (!"none".equals(candidateParent)) requireUuid(candidateParent);
        String imageSha = requireSha256(fields.get("image_sha256"));
        String manifestSha = requireSha256(fields.get("manifest_sha256"));
        return new TransactionIdentity(candidateId, expectedSequence, expectedId,
                expectedManifest, expectedHead, targetExists, targetBefore, candidateParent,
                candidateSequence, imageSha, manifestSha);
    }

    private void finishPendingTransaction(TransactionIdentity identity, Outcome outcome,
                                          WorkingSession session) throws IOException {
        Path pending = pendingPath();
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS) &&
                !sameIdentity(identity, readPendingTransaction()))
            throw new IOException("Pending marker changed during transaction reconciliation");
        if (outcome == Outcome.COMMITTED) {
            if (!candidateHeadMatches(identity) || verifyCandidate(identity) == null)
                throw new IOException("Committed candidate no longer matches its exact operation identity");
        } else if (outcome == Outcome.NOT_COMMITTED) {
            if (!matchesExpectedHead(identity))
                throw new IOException("Old virtual-SD authority is no longer proven");
            quarantineUnpublishedCandidate(identity, session);
        } else {
            throw new IOException("An indeterminate transaction cannot clear its recovery marker");
        }
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(pending);
            directorySync.sync(root);
        }
    }

    private void quarantineUnpublishedCandidate(TransactionIdentity identity,
                                                 WorkingSession session) throws IOException {
        Path generation = generationsDirectory.resolve("gen-" + identity.candidateGenerationId);
        quarantineIfPresent(generation, "unpublished-generation-" + identity.candidateGenerationId);
        Path bootstrap = workingDirectory.resolve("bootstrap-" + identity.candidateGenerationId);
        quarantineIfPresent(bootstrap, "unpublished-bootstrap-" + identity.candidateGenerationId);
        if (session != null && Files.exists(session.directory, LinkOption.NOFOLLOW_LINKS))
            quarantineIfPresent(session.directory, "unpublished-working-" + identity.candidateGenerationId);
    }

    private void quarantineIfPresent(Path source, String name) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source)) throw new IOException("Refusing symlinked unpublished candidate");
        Path target = quarantineDirectory.resolve(name);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unpublished candidate quarantine target already exists");
        moveAtomic(source, target, false);
        directorySync.sync(source.getParent());
        directorySync.sync(quarantineDirectory);
    }

    private static boolean sameIdentity(TransactionIdentity left, TransactionIdentity right) {
        return left != null && right != null &&
                left.candidateGenerationId.equals(right.candidateGenerationId) &&
                left.expectedHeadSequence == right.expectedHeadSequence &&
                left.expectedHeadGenerationId.equals(right.expectedHeadGenerationId) &&
                left.expectedHeadManifestSha256.equals(right.expectedHeadManifestSha256) &&
                left.expectedHeadSha256.equals(right.expectedHeadSha256) &&
                left.targetHeadBeforeExists == right.targetHeadBeforeExists &&
                left.targetHeadBeforeSha256.equals(right.targetHeadBeforeSha256) &&
                left.candidateParentGenerationId.equals(right.candidateParentGenerationId) &&
                left.candidateSequence == right.candidateSequence &&
                left.imageSha256.equals(right.imageSha256) &&
                left.manifestSha256.equals(right.manifestSha256);
    }

    private void requireNoPendingTransaction() throws IOException {
        if (Files.exists(pendingPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A pending transaction must be strictly reconciled before reading or writing");
    }

    private void requireNoRetainedRecoveryLease() throws IOException {
        if (recoveryLease != null)
            throw new IOException("This store retains an unresolved writer lease; cold recovery is required");
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

    private Generation readCurrentStrictLocked() throws IOException {
        List<Generation> valid = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            Path path = root.resolve("HEAD." + slot);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            Generation generation = readHead(path);
            if ((generation.sequence & 1L) != slot)
                throw new IOException("Virtual-SD head is stored in the wrong slot");
            valid.add(generation);
        }
        if (valid.isEmpty()) {
            if (hasGenerationDirectories())
                throw new IOException("Orphan virtual-SD generations exist without a committed head");
            return null;
        }
        if (valid.size() == 2) {
            Generation left = valid.get(0);
            Generation right = valid.get(1);
            if (left.sequence == right.sequence && !left.id.equals(right.id))
                throw new IOException("Conflicting virtual-SD head slots have the same sequence");
            if (Math.max(left.sequence, right.sequence) - Math.min(left.sequence, right.sequence) != 1)
                throw new IOException("Virtual-SD head slots do not describe one parent and its child");
            Generation parent = left.sequence < right.sequence ? left : right;
            Generation child = left.sequence < right.sequence ? right : left;
            if (!parent.id.equals(child.parentGenerationId) ||
                    !branchId.equals(child.parentBranchId))
                throw new IOException("Virtual-SD head parent chain is inconsistent");
        }
        valid.sort((left, right) -> Long.compare(right.sequence, left.sequence));
        return valid.get(0);
    }

    private static final class HeadSnapshot {
        final boolean exists;
        final String sha256;

        HeadSnapshot(boolean exists, String sha256) {
            this.exists = exists;
            this.sha256 = sha256;
        }
    }

    private HeadSnapshot captureHeadSnapshot(long sequence) throws IOException {
        Path target = root.resolve("HEAD." + (sequence & 1L));
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return new HeadSnapshot(false, "none");
        requireRegularFile(target);
        return new HeadSnapshot(true, sha256(target));
    }

    private Generation verifyCandidate(TransactionIdentity operation) {
        try {
            Path candidateHead = root.resolve("HEAD." + (operation.candidateSequence & 1L));
            Generation candidate = readHead(candidateHead);
            if (!operation.candidateGenerationId.equals(candidate.id) ||
                    operation.candidateSequence != candidate.sequence ||
                    !operation.manifestSha256.equals(candidate.manifestSha256) ||
                    !operation.imageSha256.equals(candidate.imageSha256) ||
                    !operation.candidateParentGenerationId.equals(candidate.parentGenerationId))
                return null;
            if (!matchesExpectedParent(operation)) return null;
            return candidate;
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private boolean candidateHeadMatches(TransactionIdentity operation) {
        if (operation == null) return false;
        try {
            Path candidateHead = root.resolve("HEAD." + (operation.candidateSequence & 1L));
            Generation candidate = readHead(candidateHead);
            return operation.candidateGenerationId.equals(candidate.id) &&
                    operation.candidateSequence == candidate.sequence &&
                    operation.manifestSha256.equals(candidate.manifestSha256) &&
                    operation.imageSha256.equals(candidate.imageSha256) &&
                    operation.candidateParentGenerationId.equals(candidate.parentGenerationId);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean matchesExpectedHead(TransactionIdentity operation) {
        if (operation == null || !matchesExpectedParent(operation)) return false;
        return targetHeadMatchesBefore(operation);
    }

    private boolean matchesExpectedHead(Generation expected) {
        try {
            if (expected == null) return noHeadExists();
            Generation observed = readHead(root.resolve("HEAD." + (expected.sequence & 1L)));
            return sameHead(expected, observed);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean matchesExpectedHead(TransactionIdentity operation, Generation oldHead,
                                        WorkingSession session) {
        if (operation != null) return matchesExpectedHead(operation);
        Generation expected = oldHead;
        if (expected == null && session != null) {
            try {
                expected = readHead(root.resolve("HEAD." + (session.baseSequence & 1L)));
            } catch (IOException | RuntimeException invalid) {
                return false;
            }
        }
        return matchesExpectedHead(expected);
    }

    private boolean matchesExpectedParent(TransactionIdentity operation) {
        try {
            if (operation.expectedHeadSequence == 0) {
                Path previousSlot = root.resolve("HEAD." + (1 - (operation.candidateSequence & 1L)));
                return "none".equals(operation.expectedHeadGenerationId) &&
                        !Files.exists(previousSlot, LinkOption.NOFOLLOW_LINKS);
            }
            Path parentHead = root.resolve("HEAD." + (operation.expectedHeadSequence & 1L));
            Generation parent = readHead(parentHead);
            return operation.expectedHeadSequence == parent.sequence &&
                    operation.expectedHeadGenerationId.equals(parent.id) &&
                    operation.expectedHeadManifestSha256.equals(parent.manifestSha256) &&
                    operation.expectedHeadSha256.equals(parent.headSha256);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean targetHeadMatchesBefore(TransactionIdentity operation) {
        try {
            Path target = root.resolve("HEAD." + (operation.candidateSequence & 1L));
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists != operation.targetHeadBeforeExists) return false;
            return !exists || operation.targetHeadBeforeSha256.equals(sha256(target));
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean noHeadExists() {
        return !Files.exists(root.resolve("HEAD.0"), LinkOption.NOFOLLOW_LINKS) &&
                !Files.exists(root.resolve("HEAD.1"), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean quarantineBootstrapCandidate(String candidateId, IOException originalFailure) {
        try {
            Path staged = workingDirectory.resolve("bootstrap-" + candidateId);
            Path generation = generationsDirectory.resolve("gen-" + candidateId);
            if (!noHeadExists()) return false;
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                moveAtomic(staged, quarantineDirectory.resolve("bootstrap-stage-" + candidateId), false);
                directorySync.sync(workingDirectory);
                directorySync.sync(quarantineDirectory);
            }
            if (Files.exists(generation, LinkOption.NOFOLLOW_LINKS)) {
                moveAtomic(generation, quarantineDirectory.resolve("bootstrap-generation-" + candidateId), false);
                directorySync.sync(generationsDirectory);
                directorySync.sync(quarantineDirectory);
            }
            return noHeadExists();
        } catch (IOException | RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            return false;
        }
    }

    private static boolean sameHead(Generation expected, Generation actual) {
        return expected.sequence == actual.sequence && expected.id.equals(actual.id) &&
                expected.manifestSha256.equals(actual.manifestSha256) &&
                expected.headSha256.equals(actual.headSha256);
    }

    private static IOException asIOException(String message, Throwable error) {
        if (error instanceof IOException) return (IOException) error;
        return new IOException(message + ": " + String.valueOf(error.getMessage()), error);
    }

    private static Generation requireCommitted(TransactionResult result) throws IOException {
        if (result.outcome == Outcome.COMMITTED && result.generation != null && result.cleanupResolved)
            return result.generation;
        if (result.outcome == Outcome.COMMITTED && result.generation != null && !result.cleanupResolved)
            throw new IOException("Virtual-SD generation committed, but owner cleanup remains unresolved",
                    result.failure);
        if (result.failure != null) throw result.failure;
        throw new IOException("Virtual-SD transaction outcome is " + result.outcome);
    }

    private static final class CancelledTransactionException extends IOException {
        CancelledTransactionException() { super("Virtual-SD transaction cancelled before HEAD publication"); }
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
        return readGeneration(sequence, generationId, manifestSha, sha256(headFile));
    }

    private Generation readGeneration(long expectedSequence, String generationId,
                                      String expectedManifestSha, String headSha) throws IOException {
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
                fields.get("image_sha256"), expectedManifestSha, headSha, image,
                fields.get("parent_branch_id"),
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
        copyAndForce(source, destination, NO_CANCELLATION);
    }

    private static void copyAndForce(Path source, Path destination,
                                     TransactionControl control) throws IOException {
        requireRegularFile(source);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (true) {
                control.checkCancellation();
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    control.checkCancellation();
                    output.write(buffer);
                }
                buffer.clear();
            }
            control.checkCancellation();
            output.force(true);
        }
        if (!destination.toFile().setWritable(true, true) && !Files.isWritable(destination))
            throw new IOException("Virtual-SD working image is not writable");
        control.checkCancellation();
        if (Files.size(source) != Files.size(destination) ||
                !sha256(source, control).equals(sha256(destination, control)))
            throw new IOException("Virtual-SD image copy failed readback verification");
        control.checkCancellation();
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

    private static long parseNonnegativeLong(String value, String label) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException();
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

    private static String sha256(Path path, TransactionControl control) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    control.checkCancellation();
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            control.checkCancellation();
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
