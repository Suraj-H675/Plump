package dev.plump.emulator;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable per-game selector for canonical or branch-native save history. */
final class ActiveTimelineStore {
    private static final String MAGIC = "PLUMP_ACTIVE_TIMELINE";
    private static final int VERSION = 1;
    private static final int MAX_RECORD_BYTES = 4096;
    private static final String NONE = "-";
    private static final String[] KEYS = {
            "format", "status", "transaction_id", "console", "rom_sha256", "core_id",
            "core_revision", "core_frontend_revision", "core_binary_sha256", "abi", "libretro_api",
            "timeline", "branch_id", "source_checkpoint_id", "native_generation", "native_sha256",
            "coherent_checkpoint_id", "coherent_native_generation", "coherent_native_sha256"
    };

    enum State { PREPARED, COMMITTED, AMBIGUOUS }
    enum Timeline { CANONICAL, BRANCH }
    enum Classification {
        CANONICAL, BRANCH_NATIVE_ONLY, POTENTIAL_EXACT_STATE, ROM_UNAVAILABLE,
        MISSING_POINTER, CORRUPT_POINTER, AMBIGUOUS, INVALID_TARGET
    }

    enum FaultPoint {
        BEFORE_PREPARE, AFTER_PREPARE, BEFORE_COMMIT, AFTER_COMMIT,
        BEFORE_TEMP_WRITE, AFTER_TEMP_WRITE, BEFORE_FILE_FSYNC, AFTER_FILE_FSYNC,
        BEFORE_RENAME, AFTER_RENAME, BEFORE_DIRECTORY_FSYNC, AFTER_DIRECTORY_FSYNC,
        AFTER_READBACK
    }

    interface FaultInjector { void at(FaultPoint point) throws IOException; }
    interface DirectorySync { void sync(Path directory) throws IOException; }

    static final FaultInjector NO_FAULTS = point -> { };

    static final class Resolution {
        final Classification classification;
        final Timeline timeline;
        final HistoricalBranchStore.Branch branch;
        final byte[] nativeSave;
        final long nativeGeneration;
        final String nativeSha256;
        final String detail;

        private Resolution(Classification classification, Timeline timeline,
                           HistoricalBranchStore.Branch branch, byte[] nativeSave,
                           long nativeGeneration, String nativeSha256, String detail) {
            this.classification = classification;
            this.timeline = timeline;
            this.branch = branch;
            this.nativeSave = nativeSave == null ? null : nativeSave.clone();
            this.nativeGeneration = nativeGeneration;
            this.nativeSha256 = nativeSha256;
            this.detail = detail;
        }

        boolean mayLoad() {
            return classification == Classification.CANONICAL ||
                    classification == Classification.BRANCH_NATIVE_ONLY ||
                    classification == Classification.POTENTIAL_EXACT_STATE;
        }

        boolean potentialExactStateAvailable() { return classification == Classification.POTENTIAL_EXACT_STATE; }
    }

    static final class SelectionResult {
        final boolean committed;
        final Timeline timeline;
        final String branchId;
        final long nativeGeneration;
        final String nativeSha256;
        final String detail;

        private SelectionResult(boolean committed, Timeline timeline, String branchId,
                                long nativeGeneration, String nativeSha256, String detail) {
            this.committed = committed;
            this.timeline = timeline;
            this.branchId = branchId;
            this.nativeGeneration = nativeGeneration;
            this.nativeSha256 = nativeSha256;
            this.detail = detail;
        }
    }

    private static final class Entry {
        final long generation;
        final State state;
        final String transactionId;
        final CheckpointStore.Identity identity;
        final Timeline timeline;
        final String branchId;
        final String sourceCheckpointId;
        final long nativeGeneration;
        final String nativeSha256;
        final String coherentCheckpointId;
        final long coherentNativeGeneration;
        final String coherentNativeSha256;

        Entry(long generation, State state, String transactionId, CheckpointStore.Identity identity,
              Timeline timeline, String branchId, String sourceCheckpointId,
              long nativeGeneration, String nativeSha256, String coherentCheckpointId,
              long coherentNativeGeneration, String coherentNativeSha256) {
            this.generation = generation;
            this.state = state;
            this.transactionId = transactionId;
            this.identity = identity;
            this.timeline = timeline;
            this.branchId = branchId;
            this.sourceCheckpointId = sourceCheckpointId;
            this.nativeGeneration = nativeGeneration;
            this.nativeSha256 = nativeSha256;
            this.coherentCheckpointId = coherentCheckpointId;
            this.coherentNativeGeneration = coherentNativeGeneration;
            this.coherentNativeSha256 = coherentNativeSha256;
        }

        Entry with(long nextGeneration, State nextState, long nextNativeGeneration,
                   String nextNativeSha256, String nextTransactionId) {
            return new Entry(nextGeneration, nextState, nextTransactionId, identity, timeline,
                    branchId, sourceCheckpointId, nextNativeGeneration, nextNativeSha256,
                    coherentCheckpointId, coherentNativeGeneration, coherentNativeSha256);
        }
    }

    private static final class Slot {
        final int index;
        final Entry entry;
        Slot(int index, Entry entry) { this.index = index; this.entry = entry; }
    }

    private static final class Read {
        final Entry latest;
        final boolean absent;
        final boolean corrupt;
        final String detail;
        Read(Entry latest, boolean absent, boolean corrupt, String detail) {
            this.latest = latest;
            this.absent = absent;
            this.corrupt = corrupt;
            this.detail = detail;
        }
    }

    static final class PendingSelection {
        final String transactionId;
        final CheckpointStore.Identity identity;
        final Timeline timeline;
        final String branchId;
        final String sourceCheckpointId;
        final long nativeGeneration;
        final String nativeSha256;
        final String coherentCheckpointId;
        final long coherentNativeGeneration;
        final String coherentNativeSha256;
        private final GameSaveStore targetStore;

        private PendingSelection(Entry entry, GameSaveStore targetStore) {
            transactionId = entry.transactionId;
            identity = entry.identity;
            timeline = entry.timeline;
            branchId = entry.branchId;
            sourceCheckpointId = entry.sourceCheckpointId;
            nativeGeneration = entry.nativeGeneration;
            nativeSha256 = entry.nativeSha256;
            coherentCheckpointId = entry.coherentCheckpointId;
            coherentNativeGeneration = entry.coherentNativeGeneration;
            coherentNativeSha256 = entry.coherentNativeSha256;
            this.targetStore = targetStore;
        }

        private Entry entry() {
            return new Entry(1, State.PREPARED, transactionId, identity, timeline, branchId,
                    sourceCheckpointId, nativeGeneration, nativeSha256, coherentCheckpointId,
                    coherentNativeGeneration, coherentNativeSha256);
        }
    }

    private final Path directory;
    private final DirectorySync directorySync;
    private final FaultInjector faults;
    private CheckpointStore.Identity recoveryIdentity;

    ActiveTimelineStore(Path directory, DirectorySync directorySync) {
        this(directory, directorySync, NO_FAULTS);
    }

    ActiveTimelineStore(Path directory, DirectorySync directorySync, FaultInjector faults) {
        if (directory == null || directorySync == null || faults == null)
            throw new IllegalArgumentException("Timeline storage dependencies are required");
        this.directory = directory.toAbsolutePath().normalize();
        this.directorySync = directorySync;
        this.faults = faults;
    }

    /** Resolve before any ROM is passed to a native load/restore function. */
    synchronized Resolution resolveCold(boolean romAvailable, CheckpointStore.Identity identity,
                                        GameSaveStore canonical, HistoricalBranchStore branches) {
        if (!romAvailable || identity == null)
            return unresolved(Classification.ROM_UNAVAILABLE, "ROM must be reselected; saved timeline is preserved");
        if (canonical == null || branches == null)
            return unresolved(Classification.INVALID_TARGET, "Timeline storage is unavailable");

        Read read = read(identity);
        if (read.corrupt)
            return unresolved(Classification.CORRUPT_POINTER,
                    "Active timeline metadata is unreadable; choose a preserved history explicitly");
        if (read.absent) {
            HistoricalBranchStore.Evidence evidence = branches.scanPublishedEvidence(identity);
            if (evidence == HistoricalBranchStore.Evidence.MATCHING_ROM)
                return unresolved(Classification.MISSING_POINTER,
                        "Published branch history exists without an active selector; choose a history explicitly");
            if (evidence == HistoricalBranchStore.Evidence.UNKNOWN)
                return unresolved(Classification.MISSING_POINTER,
                        "Branch metadata could not be scanned safely; choose a preserved history explicitly");
            // Legacy v1 canonical saves predate selectors. With no branch evidence for this ROM,
            // validate the PLSV generation first, then durably initialize its canonical selector.
            try {
                verifiedTarget(canonical, identity);
                SelectionResult initial = selectCanonical(identity, canonical);
                if (initial.committed) return resolveCold(true, identity, canonical, branches);
            } catch (IOException ignored) { }
            return unresolved(Classification.MISSING_POINTER,
                    "Legacy native history could not be selected safely; choose a preserved history explicitly");
        }

        Entry entry = read.latest;
        if (entry.state != State.COMMITTED)
            return unresolved(Classification.AMBIGUOUS,
                    "A timeline transaction was interrupted; canonical and branch histories are preserved");
        try {
            if (entry.timeline == Timeline.CANONICAL) {
                requireCanonicalFields(entry);
                GameSaveStore.Recovery nativeSave = requireNative(canonical, identity, entry.nativeGeneration,
                        entry.nativeSha256);
                return new Resolution(Classification.CANONICAL, Timeline.CANONICAL, null,
                        nativeSave.bytes, nativeSave.generation, entry.nativeSha256, null);
            }

            if (entry.branchId == null || !entry.sourceCheckpointId.matches(uuidPattern()))
                throw new IOException("Branch selector identity is incomplete");
            HistoricalBranchStore.Branch branch = branches.open(entry.branchId, identity);
            if (!entry.sourceCheckpointId.equals(branch.checkpointId))
                throw new IOException("Branch source checkpoint does not match active selector");
            GameSaveStore.Recovery nativeSave = requireNative(branch.saves, identity,
                    entry.nativeGeneration, entry.nativeSha256);
            boolean exact = entry.coherentCheckpointId.equals(branch.checkpointId) &&
                    entry.coherentNativeGeneration > 0 &&
                    entry.nativeGeneration == entry.coherentNativeGeneration &&
                    entry.nativeSha256.equals(entry.coherentNativeSha256) &&
                    nativeSave.bytes.length == branch.nativeSave().length &&
                    MessageDigest.isEqual(nativeSave.bytes, branch.nativeSave()) &&
                    branch.machineState().length > 0;
            if (exact)
                return new Resolution(Classification.POTENTIAL_EXACT_STATE, Timeline.BRANCH, branch,
                        nativeSave.bytes, nativeSave.generation, entry.nativeSha256, null);
            return new Resolution(Classification.BRANCH_NATIVE_ONLY, Timeline.BRANCH, branch,
                    nativeSave.bytes, nativeSave.generation, entry.nativeSha256,
                    "Latest branch native save is valid; no matching machine checkpoint is current");
        } catch (IOException | RuntimeException invalid) {
            return unresolved(Classification.INVALID_TARGET,
                    "Selected native history failed identity or checksum validation; both histories are preserved");
        }
    }

    /** Internal explicit choice. It publishes PREPARED, revalidates the native target, then COMMITTED. */
    synchronized SelectionResult selectCanonical(CheckpointStore.Identity identity,
                                                 GameSaveStore canonical) throws IOException {
        Entry target = canonicalTarget(identity, canonical);
        return select(target, canonical);
    }

    /** Explicit recovery choice; prior selector bytes are moved intact into durable evidence first. */
    synchronized SelectionResult recoverCanonical(CheckpointStore.Identity identity,
                                                  GameSaveStore canonical) throws IOException {
        Entry target = canonicalTarget(identity, canonical);
        return recoverSelection(target, canonical);
    }

    /** Explicit branch recovery choice; the branch's immutable pair and latest PLSV are revalidated. */
    synchronized SelectionResult recoverBranch(CheckpointStore.Identity identity,
                                                HistoricalBranchStore branches,
                                                HistoricalBranchStore.Branch branch) throws IOException {
        Entry target = branchTarget(identity, branches, branch);
        HistoricalBranchStore.Branch verified = branches.open(branch.id, identity);
        return recoverSelection(target, verified.saves);
    }

    synchronized PendingSelection prepareCanonical(CheckpointStore.Identity identity,
                                                    GameSaveStore canonical) throws IOException {
        if (identity == null || canonical == null) throw new IOException("Canonical selection identity is incomplete");
        GameSaveStore.Recovery nativeSave = verifiedTarget(canonical, identity);
        return prepare(new Entry(1, State.PREPARED, UUID.randomUUID().toString(), identity,
                Timeline.CANONICAL, NONE, NONE, nativeSave.generation,
                nativeSave.generation == 0 ? NONE : sha256(nativeSave.bytes), NONE, 0, NONE), canonical);
    }

    /** Internal explicit choice. The branch and its latest actual PLSV generation are re-read before commit. */
    synchronized SelectionResult selectBranch(CheckpointStore.Identity identity,
                                               HistoricalBranchStore branches,
                                               HistoricalBranchStore.Branch branch) throws IOException {
        PendingSelection pending = prepareBranch(identity, branches, branch);
        return commit(pending);
    }

    synchronized PendingSelection prepareBranch(CheckpointStore.Identity identity,
                                                  HistoricalBranchStore branches,
                                                  HistoricalBranchStore.Branch branch) throws IOException {
        Entry target = branchTarget(identity, branches, branch);
        HistoricalBranchStore.Branch verified = branches.open(branch.id, identity);
        return prepare(target, verified.saves);
    }

    synchronized SelectionResult commit(PendingSelection pending) throws IOException {
        if (pending == null || pending.targetStore == null)
            throw new IOException("Prepared timeline selection is incomplete");
        Entry prepared = pending.entry();
        return commitPrepared(prepared, pending.targetStore);
    }

    /** Called before any core save write. It proves the committed selector still owns the target journal. */
    synchronized void assertWritable(CheckpointStore.Identity identity, String branchId,
                                     GameSaveStore target) throws IOException {
        Entry entry = requireCommitted(identity);
        requireTimeline(entry, branchId);
        requireNative(target, identity, entry.nativeGeneration, entry.nativeSha256);
    }

    /** Called after every durable PLSV write; publishes and verifies the new selector generation. */
    synchronized SelectionResult recordDurableSave(CheckpointStore.Identity identity, String branchId,
                                                   GameSaveStore target, byte[] written) throws IOException {
        Entry current = requireCommitted(identity);
        requireTimeline(current, branchId);
        GameSaveStore.Recovery latest = verifiedTarget(target, identity);
        if (latest.bytes == null || written == null || !MessageDigest.isEqual(latest.bytes, written) ||
                latest.generation != current.nativeGeneration + 1)
            throw new IOException("Durable native generation does not match the active timeline transaction");
        String transaction = UUID.randomUUID().toString();
        Entry prepared = current.with(1, State.PREPARED, latest.generation,
                sha256(latest.bytes), transaction);
        return select(prepared, target);
    }

    /** Ambiguity is durable when possible; a hard kill already leaves PREPARED as a fail-closed marker. */
    synchronized void markAmbiguous(CheckpointStore.Identity identity) {
        if (identity == null) return;
        try {
            Read read = readRaw(identity);
            if (read.latest == null) return;
            Entry ambiguous = next(read.latest, State.AMBIGUOUS);
            writeNext(ambiguous);
        } catch (IOException ignored) { }
    }

    private SelectionResult select(Entry requested, GameSaveStore target) throws IOException {
        PendingSelection pending = prepare(requested, target);
        return commitPrepared(pending.entry(), target);
    }

    private Entry canonicalTarget(CheckpointStore.Identity identity, GameSaveStore canonical) throws IOException {
        if (identity == null || canonical == null)
            throw new IOException("Canonical selection identity is incomplete");
        GameSaveStore.Recovery nativeSave = verifiedTarget(canonical, identity);
        return new Entry(1, State.PREPARED, UUID.randomUUID().toString(), identity,
                Timeline.CANONICAL, NONE, NONE, nativeSave.generation,
                nativeSave.generation == 0 ? NONE : sha256(nativeSave.bytes), NONE, 0, NONE);
    }

    private Entry branchTarget(CheckpointStore.Identity identity, HistoricalBranchStore branches,
                               HistoricalBranchStore.Branch branch) throws IOException {
        if (identity == null || branches == null || branch == null || !identity.sameAs(branch.identity))
            throw new IOException("Branch selection identity does not match the pinned game/core");
        HistoricalBranchStore.Branch verified = branches.open(branch.id, identity);
        GameSaveStore.Recovery nativeSave = verifiedTarget(verified.saves, identity);
        if (nativeSave.bytes == null || nativeSave.bytes.length != verified.nativeSave().length)
            throw new IOException("Branch native save geometry is invalid");
        boolean coherentSource = nativeSave.generation == 1 &&
                MessageDigest.isEqual(verified.nativeSave(), nativeSave.bytes);
        return new Entry(1, State.PREPARED, UUID.randomUUID().toString(), identity,
                Timeline.BRANCH, verified.id, verified.checkpointId, nativeSave.generation,
                sha256(nativeSave.bytes), coherentSource ? verified.checkpointId : NONE,
                coherentSource ? 1 : 0, coherentSource ? sha256(verified.nativeSave()) : NONE);
    }

    private SelectionResult recoverSelection(Entry requested, GameSaveStore target) throws IOException {
        Path marker = beginRecoveryEvidence(requested.identity);
        recoveryIdentity = requested.identity;
        try {
            SelectionResult result = select(requested, target);
            Files.deleteIfExists(marker);
            directorySync.sync(directory);
            return result;
        } finally {
            recoveryIdentity = null;
        }
    }

    private Path beginRecoveryEvidence(CheckpointStore.Identity identity) throws IOException {
        ensureDirectory();
        Path marker = recoveryMarker(identity);
        Path evidenceRoot = directory.resolve("recovery-evidence");
        if (Files.exists(evidenceRoot, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(evidenceRoot) || !Files.isDirectory(evidenceRoot, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Timeline recovery evidence directory is unsafe");
        Files.createDirectories(evidenceRoot);
        String transaction;
        Path evidence;
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(marker);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Timeline recovery marker is invalid");
            transaction = decodeRecoveryMarker(Files.readAllBytes(marker));
            evidence = evidenceRoot.resolve(transaction);
            if (!Files.isDirectory(evidence, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(evidence))
                throw new IOException("Timeline recovery evidence is unavailable");
        } else {
            transaction = UUID.randomUUID().toString();
            evidence = evidenceRoot.resolve(transaction);
            Files.createDirectory(evidence);
            directorySync.sync(evidenceRoot);
            byte[] markerBytes = encodeRecoveryMarker(transaction);
            Path temporary = directory.resolve(marker.getFileName() + ".tmp-" + UUID.randomUUID());
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(markerBytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            try { Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.deleteIfExists(temporary);
                throw new IOException("Timeline recovery marker requires atomic publication", unsupported);
            }
            directorySync.sync(directory);
        }

        for (int index = 0; index < 2; index++) {
            Path slot = slotPath(identity, index);
            if (!Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) continue;
            Path preserved = evidence.resolve("slot" + index + "-" + UUID.randomUUID() + ".timeline.evidence");
            try { Files.move(slot, preserved, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Preserving selector evidence requires atomic rename", unsupported);
            }
            directorySync.sync(evidence);
            directorySync.sync(directory);
        }
        return marker;
    }

    private Path recoveryMarker(CheckpointStore.Identity identity) {
        return directory.resolve(identity.console + "-" + identity.romSha256 + ".recovery");
    }

    private static byte[] encodeRecoveryMarker(String transaction) {
        String body = "PLUMP_TIMELINE_RECOVERY|1|" + transaction;
        return (body + "|" + sha256(body.getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeRecoveryMarker(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.endsWith("\n\n")) throw new IOException("Recovery marker terminator is invalid");
        String[] fields = text.substring(0, text.length() - 1).split("\\|", -1);
        if (fields.length != 4 || !"PLUMP_TIMELINE_RECOVERY".equals(fields[0]) ||
                !"1".equals(fields[1]) || !fields[2].matches(uuidPattern()))
            throw new IOException("Recovery marker structure is invalid");
        String body = String.join("|", fields[0], fields[1], fields[2]);
        if (!sha256(body.getBytes(StandardCharsets.UTF_8)).equals(fields[3]))
            throw new IOException("Recovery marker checksum is invalid");
        return fields[2];
    }

    private PendingSelection prepare(Entry requested, GameSaveStore targetStore) throws IOException {
        if (targetStore == null) throw new IOException("Timeline target PLSV store is unavailable");
        Entry prepared = next(requested, State.PREPARED);
        hit(FaultPoint.BEFORE_PREPARE);
        writeNext(prepared);
        verifyWritten(prepared);
        hit(FaultPoint.AFTER_PREPARE);
        return new PendingSelection(prepared, targetStore);
    }

    private SelectionResult commitPrepared(Entry prepared, GameSaveStore target) throws IOException {
        try {
            Entry latest = readRaw(prepared.identity).latest;
            if (latest == null || !prepared.transactionId.equals(latest.transactionId) ||
                    latest.state != State.PREPARED)
                throw new IOException("Prepared active timeline transaction could not be reread");
            requireNative(target, prepared.identity, prepared.nativeGeneration, prepared.nativeSha256);
            hit(FaultPoint.BEFORE_COMMIT);
            Entry committed = next(prepared, State.COMMITTED);
            writeNext(committed);
            verifyWritten(committed);
            hit(FaultPoint.AFTER_COMMIT);
            Read result = read(prepared.identity);
            if (result.corrupt || result.latest == null || result.latest.state != State.COMMITTED ||
                    !sameSelection(result.latest, committed))
                throw new IOException("Committed active timeline pointer failed readback validation");
            requireNative(target, committed.identity, committed.nativeGeneration, committed.nativeSha256);
            return new SelectionResult(true, committed.timeline, committed.branchId,
                    committed.nativeGeneration, committed.nativeSha256, null);
        } catch (IOException | RuntimeException failure) {
            markAmbiguous(prepared.identity);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Active timeline selection failed closed", failure);
        }
    }


    private Entry requireCommitted(CheckpointStore.Identity identity) throws IOException {
        Read result = read(identity);
        if (result.corrupt || result.latest == null || result.latest.state != State.COMMITTED)
            throw new IOException("Active timeline is ambiguous; native writes are blocked");
        return result.latest;
    }

    private void requireTimeline(Entry entry, String branchId) throws IOException {
        if (branchId == null) {
            if (entry.timeline != Timeline.CANONICAL) throw new IOException("Canonical writer does not own active timeline");
        } else if (entry.timeline != Timeline.BRANCH || !branchId.equals(entry.branchId)) {
            throw new IOException("Historical branch writer does not own active timeline");
        }
    }

    private static void requireCanonicalFields(Entry entry) throws IOException {
        if (!NONE.equals(entry.branchId) || !NONE.equals(entry.sourceCheckpointId) ||
                !NONE.equals(entry.coherentCheckpointId) || entry.coherentNativeGeneration != 0 ||
                !NONE.equals(entry.coherentNativeSha256))
            throw new IOException("Canonical selector contains branch metadata");
    }

    private static GameSaveStore.Recovery verifiedTarget(GameSaveStore store,
                                                         CheckpointStore.Identity identity) throws IOException {
        if (store.hasUnsafePaths(identity.romSha256))
            throw new IOException("Native PLSV target contains a symlink or non-regular path");
        GameSaveStore.Recovery result = store.load(identity.romSha256);
        if (result.warning != null || result.status == GameSaveStore.Status.CORRUPT ||
                result.status == GameSaveStore.Status.KNOWN_MISSING)
            throw new IOException("Native PLSV target is corrupt, missing-known, or ambiguous");
        if (result.status == GameSaveStore.Status.AVAILABLE &&
                (result.bytes == null || result.bytes.length == 0 || result.generation <= 0))
            throw new IOException("Native PLSV target has invalid generation or payload");
        if (result.status == GameSaveStore.Status.UNKNOWN && (result.bytes != null || result.generation != 0))
            throw new IOException("Empty native PLSV target has inconsistent metadata");
        return result;
    }

    private static GameSaveStore.Recovery requireNative(GameSaveStore store,
                                                        CheckpointStore.Identity identity,
                                                        long expectedGeneration, String expectedSha) throws IOException {
        GameSaveStore.Recovery result = verifiedTarget(store, identity);
        String actualSha = result.bytes == null ? NONE : sha256(result.bytes);
        if (result.generation != expectedGeneration || !actualSha.equals(expectedSha))
            throw new IOException("Native PLSV generation or checksum differs from active timeline pointer");
        return result;
    }

    private Entry next(Entry source, State state) throws IOException {
        Read raw = readRaw(source.identity);
        if (raw.corrupt)
            throw new IOException("Corrupt-present active timeline metadata is preserved; refusing to overwrite it");
        if (!allowedTransition(raw.latest, source, state))
            throw new IOException("Active timeline is ambiguous; an explicit evidence-preserving recovery choice is required");
        long max = 0;
        if (raw.latest != null) max = raw.latest.generation;
        if (max == Long.MAX_VALUE) throw new IOException("Active timeline selector generation exhausted");
        return source.with(max + 1, state, source.nativeGeneration, source.nativeSha256,
                source.transactionId == null ? UUID.randomUUID().toString() : source.transactionId);
    }

    private void writeNext(Entry entry) throws IOException {
        ensureDirectory();
        Read current = readRaw(entry.identity);
        if (current.corrupt)
            throw new IOException("Corrupt-present active timeline metadata is preserved; refusing to overwrite it");
        if (!allowedTransition(current.latest, entry, entry.state))
            throw new IOException("Active timeline generation transition is invalid");
        Slot[] slots = readSlots(entry.identity);
        int targetIndex;
        List<Slot> valid = new ArrayList<>();
        for (Slot slot : slots) if (slot != null) valid.add(slot);
        if (valid.isEmpty()) targetIndex = 0;
        else if (valid.size() == 1) targetIndex = 1 - valid.get(0).index;
        else {
            Slot oldest = valid.get(0).entry.generation <= valid.get(1).entry.generation ? valid.get(0) : valid.get(1);
            targetIndex = oldest.index;
        }

        Path target = slotPath(entry.identity, targetIndex);
        rejectSymlink(target);
        Path temporary = directory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        byte[] bytes = encode(entry);
        boolean moved = false;
        try {
            hit(FaultPoint.BEFORE_TEMP_WRITE);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                int split = Math.max(1, data.remaining() / 2);
                int oldLimit = data.limit();
                data.limit(split);
                while (data.hasRemaining()) channel.write(data);
                hit(FaultPoint.AFTER_TEMP_WRITE);
                data.limit(oldLimit);
                while (data.hasRemaining()) channel.write(data);
                hit(FaultPoint.BEFORE_FILE_FSYNC);
                channel.force(true);
                hit(FaultPoint.AFTER_FILE_FSYNC);
            }
            hit(FaultPoint.BEFORE_RENAME);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Timeline selector requires atomic metadata rename", unsupported);
            }
            moved = true;
            hit(FaultPoint.AFTER_RENAME);
            hit(FaultPoint.BEFORE_DIRECTORY_FSYNC);
            directorySync.sync(directory);
            hit(FaultPoint.AFTER_DIRECTORY_FSYNC);
            Entry readback = decode(Files.readAllBytes(target), entry.identity);
            if (!sameRecord(entry, readback)) throw new IOException("Timeline selector readback differs from publication");
            hit(FaultPoint.AFTER_READBACK);
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private void verifyWritten(Entry expected) throws IOException {
        Slot[] slots = readSlots(expected.identity);
        boolean found = false;
        for (Slot slot : slots) if (slot != null && sameRecord(expected, slot.entry)) found = true;
        if (!found) throw new IOException("Timeline generation was not found after durable readback");
    }

    private Read read(CheckpointStore.Identity identity) {
        Read raw = readRaw(identity);
        if (raw.corrupt) return raw;
        if (raw.latest != null && raw.latest.state != State.COMMITTED) return raw;
        return raw;
    }

    private Read readRaw(CheckpointStore.Identity identity) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
                return new Read(null, true, false, null);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                return new Read(null, false, true, "Timeline metadata directory is unsafe");
            Path marker = recoveryMarker(identity);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
                    return new Read(null, false, true, "Timeline recovery marker is unsafe");
                if (recoveryIdentity == null || !recoveryIdentity.sameAs(identity))
                    return new Read(null, false, true, "Explicit timeline recovery is incomplete");
            }
            Slot[] slots = readSlots(identity);
            int present = 0;
            int invalid = 0;
            List<Slot> valid = new ArrayList<>();
            for (int i = 0; i < slots.length; i++) {
                Path path = slotPath(identity, i);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                present++;
                if (slots[i] == null) invalid++;
                else valid.add(slots[i]);
            }
            if (present == 0) return new Read(null, true, false, null);
            if (invalid != 0) return new Read(null, false, true,
                    "At least one present active timeline generation is unreadable");
            if (valid.isEmpty()) return new Read(null, false, true, "No valid selector generation");
            valid.sort(Comparator.comparingLong(slot -> slot.entry.generation));
            if (valid.size() == 2 && valid.get(0).entry.generation == valid.get(1).entry.generation)
                return new Read(null, false, true, "Duplicate active timeline generation");
            return new Read(valid.get(valid.size() - 1).entry, false, false, null);
        } catch (IOException | RuntimeException invalid) {
            return new Read(null, false, true, "Active timeline metadata could not be read");
        }
    }

    private Slot[] readSlots(CheckpointStore.Identity identity) throws IOException {
        Slot[] slots = new Slot[2];
        for (int index = 0; index < 2; index++) {
            Path path = slotPath(identity, index);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            rejectSymlink(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_RECORD_BYTES)
                continue;
            try {
                slots[index] = new Slot(index, decode(Files.readAllBytes(path), identity));
            } catch (IOException | RuntimeException invalid) { /* Present invalid metadata is rejected by readRaw. */ }
        }
        return slots;
    }

    private void ensureDirectory() throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Active timeline metadata directory is not a private directory");
            return;
        }
        Path parent = directory.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent))
            throw new IOException("Active timeline metadata parent is a symlink");
        Files.createDirectories(directory);
        if (parent != null) directorySync.sync(parent);
    }

    private Path slotPath(CheckpointStore.Identity identity, int index) {
        return directory.resolve(identity.console + "-" + identity.romSha256 + "." + index + ".timeline");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Active timeline path is a symlink");
    }

    private static byte[] encode(Entry entry) {
        String[] values = {
                Integer.toString(VERSION), entry.state.name(), entry.transactionId,
                entry.identity.console, entry.identity.romSha256, entry.identity.coreId,
                entry.identity.coreRevision, entry.identity.coreFrontendRevision,
                entry.identity.coreBinarySha256, entry.identity.abi,
                Integer.toString(entry.identity.libretroApi), entry.timeline.name(), entry.branchId,
                entry.sourceCheckpointId, Long.toString(entry.nativeGeneration), entry.nativeSha256,
                entry.coherentCheckpointId, Long.toString(entry.coherentNativeGeneration),
                entry.coherentNativeSha256
        };
        StringBuilder body = new StringBuilder(MAGIC).append('\n');
        for (int i = 0; i < KEYS.length; i++) body.append(KEYS[i]).append('=').append(values[i]).append('\n');
        body.append("selector_generation=").append(entry.generation).append('\n');
        String checksum = sha256(body.toString().getBytes(StandardCharsets.UTF_8));
        body.append("selector_sha256=").append(checksum).append('\n');
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Entry decode(byte[] bytes, CheckpointStore.Identity expected) throws IOException {
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Timeline selector exceeds bound");
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\n", -1);
        if (lines.length != KEYS.length + 4 || !MAGIC.equals(lines[0]) || !lines[lines.length - 1].isEmpty())
            throw new IOException("Timeline selector structure is invalid");
        StringBuilder body = new StringBuilder(lines[0]).append('\n');
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String prefix = KEYS[i] + '=';
            if (!lines[i + 1].startsWith(prefix) || lines[i + 1].indexOf('=', prefix.length()) >= 0)
                throw new IOException("Timeline selector field is invalid");
            values[i] = lines[i + 1].substring(prefix.length());
            body.append(lines[i + 1]).append('\n');
        }
        if (!lines[KEYS.length + 1].startsWith("selector_generation="))
            throw new IOException("Timeline selector generation is absent");
        long generation = parsePositive(lines[KEYS.length + 1].substring("selector_generation=".length()));
        body.append(lines[KEYS.length + 1]).append('\n');
        if (!lines[KEYS.length + 2].startsWith("selector_sha256=") ||
                !sha256(body.toString().getBytes(StandardCharsets.UTF_8)).equals(
                        lines[KEYS.length + 2].substring("selector_sha256=".length())))
            throw new IOException("Timeline selector checksum mismatch");
        if (!Integer.toString(VERSION).equals(values[0])) throw new IOException("Timeline selector version is unsupported");
        CheckpointStore.Identity actual;
        try {
            actual = new CheckpointStore.Identity(values[3], values[4], values[5], values[6], values[7],
                    values[8], values[9], Integer.parseInt(values[10]));
        } catch (RuntimeException invalid) { throw new IOException("Timeline selector identity is invalid", invalid); }
        if (!expected.sameAs(actual)) throw new IOException("Timeline selector belongs to another ROM/core/ABI");
        State state;
        Timeline timeline;
        long nativeGeneration = parseNonNegative(values[14]);
        long coherentGeneration = parseNonNegative(values[17]);
        try {
            state = State.valueOf(values[1]);
            timeline = Timeline.valueOf(values[11]);
        } catch (IllegalArgumentException invalid) { throw new IOException("Timeline selector state is invalid", invalid); }
        if (!values[2].matches(uuidPattern()) || nativeGeneration == 0 != NONE.equals(values[15]) ||
                (nativeGeneration > 0 && !values[15].matches("[0-9a-f]{64}")) ||
                coherentGeneration == 0 != NONE.equals(values[18]) ||
                (coherentGeneration > 0 && !values[18].matches("[0-9a-f]{64}")))
            throw new IOException("Timeline selector generation/hash fields are invalid");
        if (timeline == Timeline.CANONICAL) {
            if (!NONE.equals(values[12]) || !NONE.equals(values[13]) || !NONE.equals(values[16]) ||
                    coherentGeneration != 0 || !NONE.equals(values[18]))
                throw new IOException("Canonical timeline contains branch-only metadata");
        } else if (!values[12].matches(uuidPattern()) || !values[13].matches(uuidPattern()) ||
                (coherentGeneration > 0 && !values[16].matches(uuidPattern())) ||
                (coherentGeneration == 0 && !NONE.equals(values[16]))) {
            throw new IOException("Branch timeline metadata is invalid");
        }
        return new Entry(generation, state, values[2], actual, timeline, values[12], values[13],
                nativeGeneration, values[15], values[16], coherentGeneration, values[18]);
    }

    private static boolean sameSelection(Entry a, Entry b) {
        return a.identity.sameAs(b.identity) && a.timeline == b.timeline &&
                a.branchId.equals(b.branchId) && a.sourceCheckpointId.equals(b.sourceCheckpointId) &&
                a.nativeGeneration == b.nativeGeneration && a.nativeSha256.equals(b.nativeSha256) &&
                a.coherentCheckpointId.equals(b.coherentCheckpointId) &&
                a.coherentNativeGeneration == b.coherentNativeGeneration &&
                a.coherentNativeSha256.equals(b.coherentNativeSha256) &&
                a.transactionId.equals(b.transactionId);
    }

    private static boolean allowedTransition(Entry current, Entry next, State targetState) {
        if (current == null) return targetState == State.PREPARED;
        if (targetState == State.PREPARED)
            return current.state == State.COMMITTED && !current.transactionId.equals(next.transactionId);
        if (targetState == State.COMMITTED)
            return current.state == State.PREPARED && current.transactionId.equals(next.transactionId) &&
                    sameSelection(current, next);
        return targetState == State.AMBIGUOUS && current.state != State.AMBIGUOUS &&
                current.transactionId.equals(next.transactionId) && sameSelection(current, next);
    }

    private static boolean sameRecord(Entry a, Entry b) {
        return a.generation == b.generation && a.state == b.state && sameSelection(a, b);
    }

    private static Resolution unresolved(Classification classification, String detail) {
        return new Resolution(classification, null, null, null, 0, NONE, detail);
    }

    private void hit(FaultPoint point) throws IOException { faults.at(point); }

    private static long parsePositive(String value) throws IOException {
        long parsed = parseNonNegative(value);
        if (parsed == 0) throw new IOException("Timeline generation must be positive");
        return parsed;
    }

    private static long parseNonNegative(String value) throws IOException {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) { throw new IOException("Timeline generation is invalid", invalid); }
    }

    private static String uuidPattern() {
        return "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }
}
