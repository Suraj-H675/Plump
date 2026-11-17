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
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Host-only, explicit transaction for promoting one supported cartridge-save branch. */
final class NativeSavePromotionStore {
    enum NativeSaveMode { UNKNOWN, GBA_CARTRIDGE_SRAM_32K, DS_CARTRIDGE_EEPROM_8K }
    enum Classification { NONE, RECOVERY_REQUIRED, CORRUPT, COMMITTED, ROLLED_BACK }
    enum FaultPoint {
        BEFORE_ANCHOR, AFTER_ANCHOR, BEFORE_INTENT, AFTER_INTENT,
        BEFORE_PLSV_WRITE, DURING_PLSV_WRITE, AFTER_PLSV_RECORD_MOVE, AFTER_PLSV_WRITE,
        BEFORE_SELECTOR, AFTER_SELECTOR, BEFORE_FINAL_COMMIT, AFTER_FINAL_COMMIT,
        BEFORE_ROLLBACK_WRITE, DURING_ROLLBACK_PLSV_WRITE, AFTER_ROLLBACK_RECORD_MOVE,
        AFTER_ROLLBACK_WRITE,
        BEFORE_ROLLBACK_SELECTOR, AFTER_ROLLBACK_SELECTOR,
        BEFORE_ROLLED_BACK_RECEIPT, AFTER_ROLLED_BACK_RECEIPT
    }

    interface FaultInjector { void at(FaultPoint point) throws IOException; }
    private interface IoAction<T> { T run() throws IOException; }
    static final FaultInjector NO_FAULTS = ignored -> { };
    private static final String NONE = "-";
    private static final int MAX_RECORD_BYTES = 32 * 1024;
    private static final int MAX_NATIVE_BYTES = 1024 * 1024;
    private static final String[] SCOPE_KEYS = {
            "transaction_id", "approval_id", "session_id", "console", "rom_sha256", "core_id",
            "core_revision", "core_frontend_revision", "core_binary_sha256", "abi", "libretro_api",
            "mode", "branch_id", "branch_generation", "branch_sha256", "source_checkpoint_id",
            "canonical_generation", "canonical_sha256", "selector_generation",
            "source_selector_transaction_id", "selector_transaction_id",
            "file0_present", "file0_size", "file0_sha256", "file1_present", "file1_size", "file1_sha256",
            "history_present", "history_size", "history_sha256"
    };

    static final class Proposal {
        final String transactionId;
        final String branchId;
        final String branchSha256;
        final long branchGeneration;
        final String confirmationText;
        private final Scope scope;

        private Proposal(Scope scope) {
            this.scope = scope;
            transactionId = scope.transactionId;
            branchId = scope.branchId;
            branchSha256 = scope.branchSha256;
            branchGeneration = scope.branchGeneration;
            confirmationText = "PROMOTE " + scope.transactionId;
        }
    }

    static final class Approval {
        final String approvalId;
        final String transactionId;
        final String branchId;
        final long branchGeneration;
        final String branchSha256;
        final long canonicalGeneration;
        final String canonicalSha256;
        private final Scope scope;
        private final String recordSha256;

        private Approval(Scope scope, String recordSha256) {
            this.scope = scope;
            this.recordSha256 = recordSha256;
            approvalId = scope.approvalId;
            transactionId = scope.transactionId;
            branchId = scope.branchId;
            branchGeneration = scope.branchGeneration;
            branchSha256 = scope.branchSha256;
            canonicalGeneration = scope.canonicalGeneration;
            canonicalSha256 = scope.canonicalSha256;
        }
    }

    static final class Result {
        final Classification classification;
        final String transactionId;
        final long canonicalGeneration;
        final String canonicalSha256;
        final String detail;

        private Result(Classification classification, String transactionId, long generation,
                       String sha256, String detail) {
            this.classification = classification;
            this.transactionId = transactionId;
            canonicalGeneration = generation;
            canonicalSha256 = sha256;
            this.detail = detail;
        }
    }

    static final class RecoveryInfo {
        final Classification classification;
        final String transactionId;
        final String detail;

        private RecoveryInfo(Classification classification, String transactionId, String detail) {
            this.classification = classification;
            this.transactionId = transactionId;
            this.detail = detail;
        }
    }

    private static final class Scope {
        final String transactionId;
        final String approvalId;
        final long sessionId;
        final CheckpointStore.Identity identity;
        final NativeSaveMode mode;
        final String branchId;
        final long branchGeneration;
        final String branchSha256;
        final String sourceCheckpointId;
        final long canonicalGeneration;
        final String canonicalSha256;
        final long selectorGeneration;
        final String sourceSelectorTransactionId;
        final String selectorTransactionId;
        final String[] fileNames;
        final boolean[] present;
        final long[] sizes;
        final String[] hashes;

        Scope(String transactionId, String approvalId, long sessionId, CheckpointStore.Identity identity,
              NativeSaveMode mode, String branchId, long branchGeneration, String branchSha256,
              String sourceCheckpointId, long canonicalGeneration, String canonicalSha256,
              long selectorGeneration, String sourceSelectorTransactionId, String selectorTransactionId,
              String[] fileNames, boolean[] present, long[] sizes, String[] hashes) {
            this.transactionId = transactionId;
            this.approvalId = approvalId;
            this.sessionId = sessionId;
            this.identity = identity;
            this.mode = mode;
            this.branchId = branchId;
            this.branchGeneration = branchGeneration;
            this.branchSha256 = branchSha256;
            this.sourceCheckpointId = sourceCheckpointId;
            this.canonicalGeneration = canonicalGeneration;
            this.canonicalSha256 = canonicalSha256;
            this.selectorGeneration = selectorGeneration;
            this.sourceSelectorTransactionId = sourceSelectorTransactionId;
            this.selectorTransactionId = selectorTransactionId;
            this.fileNames = fileNames.clone();
            this.present = present.clone();
            this.sizes = sizes.clone();
            this.hashes = hashes.clone();
        }

        Scope withApproval(String approval) {
            return new Scope(transactionId, approval, sessionId, identity, mode, branchId,
                    branchGeneration, branchSha256, sourceCheckpointId, canonicalGeneration,
                    canonicalSha256, selectorGeneration, sourceSelectorTransactionId, selectorTransactionId,
                    fileNames, present, sizes, hashes);
        }

        LinkedHashMap<String, String> fields() {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put("transaction_id", transactionId);
            values.put("approval_id", approvalId);
            values.put("session_id", Long.toString(sessionId));
            values.put("console", identity.console);
            values.put("rom_sha256", identity.romSha256);
            values.put("core_id", identity.coreId);
            values.put("core_revision", identity.coreRevision);
            values.put("core_frontend_revision", identity.coreFrontendRevision);
            values.put("core_binary_sha256", identity.coreBinarySha256);
            values.put("abi", identity.abi);
            values.put("libretro_api", Integer.toString(identity.libretroApi));
            values.put("mode", mode.name());
            values.put("branch_id", branchId);
            values.put("branch_generation", Long.toString(branchGeneration));
            values.put("branch_sha256", branchSha256);
            values.put("source_checkpoint_id", sourceCheckpointId);
            values.put("canonical_generation", Long.toString(canonicalGeneration));
            values.put("canonical_sha256", canonicalSha256);
            values.put("selector_generation", Long.toString(selectorGeneration));
            values.put("source_selector_transaction_id", sourceSelectorTransactionId);
            values.put("selector_transaction_id", selectorTransactionId);
            for (int i = 0; i < 2; i++) {
                values.put("file" + i + "_present", Boolean.toString(present[i]));
                values.put("file" + i + "_size", Long.toString(sizes[i]));
                values.put("file" + i + "_sha256", hashes[i]);
            }
            values.put("history_present", Boolean.toString(present[2]));
            values.put("history_size", Long.toString(sizes[2]));
            values.put("history_sha256", hashes[2]);
            return values;
        }
    }

    private static final class TxRead {
        final Classification classification;
        final Scope scope;
        final Path directory;
        final Map<String, String> approval;
        final Map<String, String> anchor;
        final Map<String, String> intent;
        final Map<String, String> rollback;
        final Map<String, String> terminal;
        final String approvalSha;
        final String anchorSha;
        final String intentSha;
        final String detail;

        TxRead(Classification classification, Scope scope, Path directory,
               Map<String, String> approval, Map<String, String> anchor,
               Map<String, String> intent, Map<String, String> rollback,
               Map<String, String> terminal, String approvalSha, String anchorSha,
               String intentSha, String detail) {
            this.classification = classification;
            this.scope = scope;
            this.directory = directory;
            this.approval = approval;
            this.anchor = anchor;
            this.intent = intent;
            this.rollback = rollback;
            this.terminal = terminal;
            this.approvalSha = approvalSha;
            this.anchorSha = anchorSha;
            this.intentSha = intentSha;
            this.detail = detail;
        }
    }

    private final Path root;
    private final ActiveTimelineStore selector;
    private final ActiveTimelineStore.DirectorySync directorySync;
    private final FaultInjector faults;
    private final Map<String, Approval> issuedApprovals = new LinkedHashMap<>();

    NativeSavePromotionStore(Path promotionDir, ActiveTimelineStore selector,
                             ActiveTimelineStore.DirectorySync sync) {
        this(promotionDir, selector, sync, NO_FAULTS);
    }

    NativeSavePromotionStore(Path promotionDir, ActiveTimelineStore selector,
                             ActiveTimelineStore.DirectorySync sync, FaultInjector faults) {
        if (promotionDir == null || selector == null || sync == null || faults == null)
            throw new IllegalArgumentException("Promotion storage dependencies are required");
        root = promotionDir.toAbsolutePath().normalize();
        this.selector = selector;
        directorySync = sync;
        this.faults = faults;
    }

    synchronized Proposal propose(EmulatorSessionOwner.PromotionLease lease,
                                  CheckpointStore.Identity identity, NativeSaveMode mode,
                                  GameSaveStore canonical, HistoricalBranchStore branches) throws IOException {
        requireLease(lease, identity, lease == null ? null : leaseBranch(lease));
        assertUnblocked(identity);
        validateMode(identity, mode);
        ActiveTimelineStore.PromotionSelection selected = selector.promotionSelection(identity);
        if (selected.timeline != ActiveTimelineStore.Timeline.BRANCH || selected.branchId == null)
            throw new IOException("Only the selected paused historical branch can be promoted");
        String transactionId = UUID.randomUUID().toString();
        String selectorTransactionId = UUID.nameUUIDFromBytes(
                ("promote-" + transactionId).getBytes(StandardCharsets.UTF_8)).toString();
        Scope scope = captureScope(lease, identity, mode, selected.branchId, canonical, branches, selected,
                transactionId, selectorTransactionId);
        return new Proposal(scope);
    }

    synchronized Approval approve(EmulatorSessionOwner.PromotionLease lease, Proposal proposal,
                                  String explicitConfirmation, CheckpointStore.Identity identity,
                                  NativeSaveMode mode, GameSaveStore canonical,
                                  HistoricalBranchStore branches) throws IOException {
        return withFileLock(identity, () -> approveLocked(lease, proposal, explicitConfirmation,
                identity, mode, canonical, branches));
    }

    private Approval approveLocked(EmulatorSessionOwner.PromotionLease lease, Proposal proposal,
                                   String explicitConfirmation, CheckpointStore.Identity identity,
                                   NativeSaveMode mode, GameSaveStore canonical,
                                   HistoricalBranchStore branches) throws IOException {
        if (proposal == null) throw new IOException("Promotion proposal is unavailable");
        requireLease(lease, identity, proposal.branchId);
        if (!proposal.confirmationText.equals(explicitConfirmation))
            throw new IOException("Explicit promotion confirmation did not match the proposal");
        validateMode(identity, mode);
        if (readTransactions(identity).classification != Classification.NONE)
            throw new IOException("Another promotion or corrupt promotion evidence blocks approval");
        verifyCurrentScope(proposal.scope, lease, identity, mode, canonical, branches);
        Scope approvedScope = proposal.scope.withApproval(UUID.randomUUID().toString());
        Path tx = transactionDirectory(identity, approvedScope.transactionId);
        createDirectoriesSafely(tx);
        byte[] bytes = encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_APPROVAL", approvedScope.fields());
        publishNewFile(tx.resolve("approval.record"), bytes);
        Map<String, String> readback = readRecord(tx.resolve("approval.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_APPROVAL", Arrays.asList(SCOPE_KEYS));
        if (!approvedScope.fields().equals(fieldsOnlyScope(readback)))
            throw new IOException("Durable promotion approval failed readback validation");
        String sha = sha256(bytes);
        Approval approval = new Approval(approvedScope, sha);
        issuedApprovals.put(approval.approvalId, approval);
        return approval;
    }

    synchronized void assertUnblocked(CheckpointStore.Identity identity) throws IOException {
        if (identity == null) throw new IOException("Promotion gate identity is unavailable");
        Path game = gameDirectory(identity);
        checkPathComponents(root);
        if (!Files.exists(game, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectoryNoFollow(game);
        Path lock = game.resolve(".promotion.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymlink(lock);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                FileLock held;
                try { held = channel.tryLock(); }
                catch (OverlappingFileLockException busy) { throw new IOException("Promotion is in progress", busy); }
                if (held == null) throw new IOException("Promotion is in progress");
                held.release();
            }
        }
        TxRead read = readTransactions(identity);
        if (read.classification == Classification.RECOVERY_REQUIRED ||
                read.classification == Classification.CORRUPT)
            throw new IOException("Promotion transaction requires explicit recovery: " + read.detail);
    }

    synchronized RecoveryInfo inspect(CheckpointStore.Identity identity) {
        if (identity == null) return new RecoveryInfo(Classification.CORRUPT, null,
                "Pinned game identity is unavailable");
        TxRead read = readTransactions(identity);
        return new RecoveryInfo(read.classification, read.scope == null ? null : read.scope.transactionId,
                read.detail);
    }

    synchronized Result retryCommitted(Approval approval, CheckpointStore.Identity identity,
                                       GameSaveStore canonical, HistoricalBranchStore branches)
            throws IOException {
        requireApprovalObject(approval);
        if (identity == null || !approval.scope.identity.sameAs(identity) || canonical == null || branches == null)
            throw new IOException("Committed promotion retry identity or storage changed");
        TxRead tx = readTransactions(identity);
        if (tx.classification == Classification.RECOVERY_REQUIRED || tx.classification == Classification.NONE)
            return null;
        if (tx.classification != Classification.COMMITTED || tx.scope == null ||
                !approval.transactionId.equals(tx.scope.transactionId) ||
                !approval.recordSha256.equals(tx.approvalSha) || !sameScope(approval.scope, tx.scope))
            throw new IOException("Exact committed promotion receipt does not match this approval");
        verifyBranch(tx.scope, identity, branches);
        GameSaveStore.PromotionImage latest = canonical.snapshotForPromotion(identity.romSha256);
        if (latest.generation != parseLong(tx.terminal.get("final_generation")) ||
                !latest.nativeSha256.equals(tx.terminal.get("final_sha256")))
            throw new IOException("Committed promotion retry found a different canonical generation");
        ActiveTimelineStore.PromotionSelection selected = selector.promotionSelection(identity);
        if (selected.timeline != ActiveTimelineStore.Timeline.CANONICAL ||
                !tx.scope.selectorTransactionId.equals(selected.selectorTransactionId) ||
                selected.nativeGeneration != latest.generation ||
                !selected.nativeSha256.equals(latest.nativeSha256))
            throw new IOException("Committed promotion retry found a different active selector");
        return resultForTerminal(tx);
    }

    synchronized Result promote(EmulatorSessionOwner.PromotionLease lease, Approval approval,
                                CheckpointStore.Identity identity, NativeSaveMode mode,
                                GameSaveStore canonical, HistoricalBranchStore branches) throws IOException {
        requireApprovalObject(approval);
        requireLease(lease, identity, approval.branchId);
        if (lease.sessionId() != approval.scope.sessionId)
            throw new IOException("Paused owner session changed after approval");
        validateMode(identity, mode);
        if (mode != approval.scope.mode)
            throw new IOException("Native save mode changed after approval");
        return withFileLock(identity, () -> promoteApproved(approval.scope, approval.recordSha256,
                identity, canonical, branches));
    }

    /** Explicitly finish a pending promotion. Cold resolution never calls this method. */
    synchronized Result recoverFinish(CheckpointStore.Identity identity, GameSaveStore canonical,
                                      HistoricalBranchStore branches, String transactionId) throws IOException {
        return withFileLock(identity, () -> {
            TxRead current = readTransactions(identity);
            if (current.classification == Classification.COMMITTED && current.scope != null &&
                    current.scope.transactionId.equals(transactionId)) return resultForTerminal(current);
            TxRead tx = requirePending(identity, transactionId);
            if (tx.rollback != null) throw new IOException("A rollback choice is already durable for this transaction");
            ensureAnchorAndIntent(tx.scope, tx.approvalSha, identity, canonical, tx.directory);
            return finish(tx.scope, tx.approvalSha, identity, canonical, branches, tx.directory);
        });
    }

    /** Explicit rollback to the independently verified original canonical native payload. */
    synchronized Result rollback(CheckpointStore.Identity identity, GameSaveStore canonical,
                                 HistoricalBranchStore branches, String transactionId) throws IOException {
        return withFileLock(identity, () -> rollbackPending(identity, canonical, branches, transactionId));
    }

    Result recoverRollback(CheckpointStore.Identity identity, GameSaveStore canonical,
                           HistoricalBranchStore branches, String transactionId) throws IOException {
        return rollback(identity, canonical, branches, transactionId);
    }

    private Result promoteApproved(Scope scope, String approvalSha, CheckpointStore.Identity identity,
                                  GameSaveStore canonical, HistoricalBranchStore branches) throws IOException {
        TxRead tx = readTransactions(identity);
        if (tx.classification == Classification.COMMITTED && scope.transactionId.equals(tx.scope.transactionId))
            return resultForTerminal(tx);
        if (tx.classification != Classification.RECOVERY_REQUIRED || tx.scope == null ||
                !scope.transactionId.equals(tx.scope.transactionId) ||
                !approvalSha.equals(tx.approvalSha) || !sameScope(scope, tx.scope))
            throw new IOException("Approval is stale, unknown or conflicts with another transaction");
        if (tx.rollback != null) throw new IOException("A rollback choice is already durable for this transaction");

        Path directory = tx.directory;
        verifyBranch(scope, identity, branches);
        ensureAnchorAndIntent(scope, approvalSha, identity, canonical, directory);
        return finish(scope, approvalSha, identity, canonical, branches, directory);
    }

    private Result finish(Scope scope, String approvalSha, CheckpointStore.Identity identity,
                          GameSaveStore canonical, HistoricalBranchStore branches, Path txDirectory)
            throws IOException {
        verifyBranch(scope, identity, branches);
        Map<String, String> anchor = verifyAnchor(scope, approvalSha, txDirectory);
        Map<String, String> intent = ensureIntent(scope, approvalSha, anchor, txDirectory);
        String intentSha = sha256(readNoFollow(txDirectory.resolve("intent.record"), MAX_RECORD_BYTES));
        byte[] promotedBytes = branchNativeBytes(scope, branches);
        GameSaveStore.PromotionImage current = canonical.snapshotForPromotion(identity.romSha256);
        if (matchesOriginal(scope, current)) {
            hit(FaultPoint.BEFORE_PLSV_WRITE);
            canonical.writeForPromotion(identity.romSha256, promotedBytes, point -> {
                if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE) hit(FaultPoint.DURING_PLSV_WRITE);
                if (point == GameSaveStore.WritePoint.AFTER_RECORD_MOVE) hit(FaultPoint.AFTER_PLSV_RECORD_MOVE);
            });
        } else if (!isPromotedGeneration(scope, current)) {
            throw new IOException("Canonical PLSV changed outside the approved promotion generation");
        }
        current = canonical.snapshotForPromotion(identity.romSha256);
        if (!isPromotedGeneration(scope, current) || !Arrays.equals(promotedBytes, current.nativeBytes))
            throw new IOException("Promoted canonical PLSV failed exact generation and byte readback");
        verifyBranch(scope, identity, branches);
        hit(FaultPoint.AFTER_PLSV_WRITE);

        verifyIntent(scope, approvalSha, anchor, intent, intentSha);
        hit(FaultPoint.BEFORE_SELECTOR);
        ActiveTimelineStore.SelectionResult selection = selector.selectCanonicalForPromotion(identity,
                canonical, scope.selectorTransactionId, scope.transactionId, scope.branchId);
        if (!selection.committed || selection.timeline != ActiveTimelineStore.Timeline.CANONICAL ||
                selection.nativeGeneration != scope.canonicalGeneration + 1 ||
                !scope.branchSha256.equals(selection.nativeSha256))
            throw new IOException("Canonical selector did not commit the exact promoted PLSV generation");
        hit(FaultPoint.AFTER_SELECTOR);

        GameSaveStore.PromotionImage verified = canonical.snapshotForPromotion(identity.romSha256);
        if (!isPromotedGeneration(scope, verified) || !Arrays.equals(promotedBytes, verified.nativeBytes))
            throw new IOException("Canonical PLSV changed before final promotion receipt");
        hit(FaultPoint.BEFORE_FINAL_COMMIT);
        Map<String, String> receipt = terminalFields(scope, approvalSha,
                sha256(readNoFollow(txDirectory.resolve("anchor/manifest.record"), MAX_RECORD_BYTES)),
                intentSha, "COMMITTED", verified.generation, verified.nativeSha256,
                scope.selectorTransactionId, NONE);
        publishNewFile(txDirectory.resolve("terminal.record"),
                encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_TERMINAL", receipt));
        Map<String, String> published = readRecord(txDirectory.resolve("terminal.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_TERMINAL", new ArrayList<>(receipt.keySet()));
        if (!receipt.equals(fieldsWithoutChecksum(published)))
            throw new IOException("Final promotion receipt failed durable readback");
        hit(FaultPoint.AFTER_FINAL_COMMIT);
        return new Result(Classification.COMMITTED, scope.transactionId,
                verified.generation, verified.nativeSha256, null);
    }

    private Result rollbackPending(CheckpointStore.Identity identity, GameSaveStore canonical,
                                  HistoricalBranchStore branches, String transactionId) throws IOException {
        if (identity == null || canonical == null || branches == null)
            throw new IOException("Rollback identity or storage is unavailable");
        TxRead tx = readTransactions(identity);
        if (tx.classification == Classification.ROLLED_BACK &&
                (transactionId == null || transactionId.equals(tx.scope.transactionId)))
            return resultForTerminal(tx);
        if ((tx.classification != Classification.RECOVERY_REQUIRED &&
                tx.classification != Classification.COMMITTED) || tx.scope == null ||
                (transactionId != null && !transactionId.equals(tx.scope.transactionId)))
            throw new IOException("No matching pending promotion is available for rollback");
        Scope scope = tx.scope;
        Path directory = tx.directory;
        ensureAnchorAndIntent(scope, tx.approvalSha, identity, canonical, directory);
        Map<String, String> anchor = verifyAnchor(scope, tx.approvalSha, directory);
        byte[] originalBytes = readNoFollow(directory.resolve("anchor/canonical.native"), MAX_NATIVE_BYTES);
        if (!sha256(originalBytes).equals(scope.canonicalSha256) || originalBytes.length != modeLength(scope.mode))
            throw new IOException("Rollback anchor native payload failed size or checksum verification");
        verifyBranch(scope, identity, branches);
        String approvalSha = tx.approvalSha;
        String anchorSha = sha256(readNoFollow(directory.resolve("anchor/manifest.record"), MAX_RECORD_BYTES));
        Map<String, String> intent = ensureIntent(scope, approvalSha, anchor, directory);
        String intentSha = sha256(readNoFollow(directory.resolve("intent.record"), MAX_RECORD_BYTES));

        Map<String, String> rollback = readOptionalRecord(directory.resolve("rollback.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollbackKeys());
        GameSaveStore.PromotionImage current = canonical.snapshotForPromotion(identity.romSha256);
        if (rollback == null) {
            if (!matchesOriginal(scope, current) && !isPromotedGeneration(scope, current))
                throw new IOException("Canonical PLSV is outside the original or promoted generation; rollback refused");
            long targetGeneration = current.generation + 1;
            if (targetGeneration <= 0) throw new IOException("Canonical save generation is exhausted");
            rollback = rollbackFields(scope, approvalSha, anchorSha, intentSha, current.generation,
                    current.nativeSha256, targetGeneration, sha256(originalBytes), UUID.randomUUID().toString());
            publishNewFile(directory.resolve("rollback.record"),
                    encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollback));
            rollback = readRecord(directory.resolve("rollback.record"),
                    "PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollbackKeys());
        }
        verifyRollback(scope, approvalSha, anchorSha, intentSha, rollback);
        long rollbackGeneration = parseLong(rollback.get("target_generation"));
        String rollbackSha = rollback.get("target_sha256");
        if (current.generation == rollbackGeneration && current.nativeSha256.equals(rollbackSha) &&
                Arrays.equals(current.nativeBytes, originalBytes)) {
            // The durable PLSV publication already completed before the prior process stopped.
        } else {
            if (current.generation + 1 != rollbackGeneration ||
                    !current.nativeSha256.equals(rollback.get("base_sha256")))
                throw new IOException("Rollback PLSV base changed; preserving both histories for recovery");
            hit(FaultPoint.BEFORE_ROLLBACK_WRITE);
            canonical.writeForPromotion(identity.romSha256, originalBytes, point -> {
                if (point == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE)
                    hit(FaultPoint.DURING_ROLLBACK_PLSV_WRITE);
                if (point == GameSaveStore.WritePoint.AFTER_RECORD_MOVE)
                    hit(FaultPoint.AFTER_ROLLBACK_RECORD_MOVE);
            });
            hit(FaultPoint.AFTER_ROLLBACK_WRITE);
        }
        GameSaveStore.PromotionImage restored = canonical.snapshotForPromotion(identity.romSha256);
        if (restored.generation != rollbackGeneration || !rollbackSha.equals(restored.nativeSha256) ||
                !Arrays.equals(restored.nativeBytes, originalBytes))
            throw new IOException("Rollback canonical PLSV failed exact readback");

        hit(FaultPoint.BEFORE_ROLLBACK_SELECTOR);
        ActiveTimelineStore.SelectionResult selection = selector.selectCanonicalForPromotion(identity,
                canonical, rollback.get("selector_transaction_id"), scope.transactionId, scope.branchId);
        if (!selection.committed || selection.timeline != ActiveTimelineStore.Timeline.CANONICAL ||
                selection.nativeGeneration != rollbackGeneration || !rollbackSha.equals(selection.nativeSha256))
            throw new IOException("Rollback canonical selector did not commit the restored generation");
        hit(FaultPoint.AFTER_ROLLBACK_SELECTOR);
        hit(FaultPoint.BEFORE_ROLLED_BACK_RECEIPT);
        Map<String, String> terminal = terminalFields(scope, approvalSha, anchorSha, intentSha,
                "ROLLED_BACK", rollbackGeneration, rollbackSha,
                rollback.get("selector_transaction_id"), sha256(encodeRecord(
                        "PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollback)));
        Path terminalPath = tx.terminal != null && "COMMITTED".equals(tx.terminal.get("status"))
                ? directory.resolve("rollback-terminal.record") : directory.resolve("terminal.record");
        publishNewFile(terminalPath, encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_TERMINAL", terminal));
        hit(FaultPoint.AFTER_ROLLED_BACK_RECEIPT);
        return new Result(Classification.ROLLED_BACK, scope.transactionId,
                rollbackGeneration, rollbackSha, null);
    }

    private Scope captureScope(EmulatorSessionOwner.PromotionLease lease,
                               CheckpointStore.Identity identity, NativeSaveMode mode,
                               String branchId, GameSaveStore canonical,
                               HistoricalBranchStore branches,
                               ActiveTimelineStore.PromotionSelection selected,
                               String transactionId, String selectorTransactionId) throws IOException {
        requireLease(lease, identity, branchId);
        HistoricalBranchStore.Branch branch = branches.open(branchId, identity);
        GameSaveStore.PromotionImage branchImage = branch.saves.snapshotForPromotion(identity.romSha256);
        GameSaveStore.PromotionImage canonicalImage = canonical.snapshotForPromotion(identity.romSha256);
        validatePayloadSizes(mode, branchImage.nativeBytes.length, canonicalImage.nativeBytes.length);
        if (!branch.checkpointId.equals(selected.sourceCheckpointId) ||
                branchImage.generation != selected.nativeGeneration ||
                !branchImage.nativeSha256.equals(selected.nativeSha256))
            throw new IOException("Selected branch PLSV changed before promotion proposal");
        String[] names = { identity.romSha256 + ".0.sav", identity.romSha256 + ".1.sav",
                identity.romSha256 + ".history" };
        boolean[] present = new boolean[3];
        long[] sizes = new long[3];
        String[] hashes = new String[3];
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = canonicalImage.files.get(names[i]);
            present[i] = bytes != null;
            sizes[i] = bytes == null ? 0 : bytes.length;
            hashes[i] = bytes == null ? NONE : sha256(bytes);
        }
        if (!present[2]) throw new IOException("Canonical PLSV receipt is absent; promotion refused");
        return new Scope(transactionId, NONE, lease.sessionId(), identity, mode, branchId,
                branchImage.generation, branchImage.nativeSha256, branch.checkpointId,
                canonicalImage.generation, canonicalImage.nativeSha256,
                selected.selectorGeneration, selected.selectorTransactionId, selectorTransactionId,
                names, present, sizes, hashes);
    }

    private void verifyCurrentScope(Scope expected, EmulatorSessionOwner.PromotionLease lease,
                                    CheckpointStore.Identity identity, NativeSaveMode mode,
                                    GameSaveStore canonical, HistoricalBranchStore branches) throws IOException {
        requireLease(lease, identity, expected.branchId);
        if (!identity.sameAs(expected.identity) || mode != expected.mode || lease.sessionId() != expected.sessionId)
            throw new IOException("Pinned game, core, ABI, mode or owner session changed after proposal");
        ActiveTimelineStore.PromotionSelection selected = selector.promotionSelection(identity);
        if (selected.timeline != ActiveTimelineStore.Timeline.BRANCH ||
                !expected.branchId.equals(selected.branchId) ||
                !expected.sourceCheckpointId.equals(selected.sourceCheckpointId) ||
                selected.selectorGeneration != expected.selectorGeneration ||
                !expected.sourceSelectorTransactionId.equals(selected.selectorTransactionId))
            throw new IOException("Active branch selector changed after promotion proposal");
        Scope current = captureScope(lease, identity, mode, expected.branchId, canonical, branches,
                selected, expected.transactionId, expected.selectorTransactionId);
        if (!sameScope(expected, current))
            throw new IOException("Canonical or branch PLSV generation changed after promotion proposal");
    }

    private void ensureAnchorAndIntent(Scope scope, String approvalSha,
                                       CheckpointStore.Identity identity, GameSaveStore canonical,
                                       Path txDirectory) throws IOException {
        Map<String, String> approval = readRecord(txDirectory.resolve("approval.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_APPROVAL", Arrays.asList(SCOPE_KEYS));
        if (!approvalSha.equals(sha256(readNoFollow(txDirectory.resolve("approval.record"), MAX_RECORD_BYTES))) ||
                !sameScope(scope, parseScope(approval)))
            throw new IOException("Durable approval record does not match the requested transaction");
        Path anchorDir = txDirectory.resolve("anchor");
        if (Files.exists(anchorDir, LinkOption.NOFOLLOW_LINKS)) {
            verifyAnchor(scope, approvalSha, txDirectory);
        } else {
            hit(FaultPoint.BEFORE_ANCHOR);
            GameSaveStore.PromotionImage image = canonical.snapshotForPromotion(identity.romSha256);
            if (!matchesOriginal(scope, image))
                throw new IOException("Canonical PLSV changed before a complete rollback anchor was published");
            publishAnchor(scope, approvalSha, image, txDirectory);
            verifyAnchor(scope, approvalSha, txDirectory);
            hit(FaultPoint.AFTER_ANCHOR);
        }
        Map<String, String> anchor = verifyAnchor(scope, approvalSha, txDirectory);
        Map<String, String> existingIntent = readOptionalRecord(txDirectory.resolve("intent.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intentKeys());
        if (existingIntent == null) {
            hit(FaultPoint.BEFORE_INTENT);
            LinkedHashMap<String, String> intent = intentFields(scope, approvalSha,
                    sha256(readNoFollow(anchorDir.resolve("manifest.record"), MAX_RECORD_BYTES)));
            publishNewFile(txDirectory.resolve("intent.record"),
                    encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intent));
            Map<String, String> readback = readRecord(txDirectory.resolve("intent.record"),
                    "PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intentKeys());
            if (!intent.equals(fieldsWithoutChecksum(readback)))
                throw new IOException("Durable promotion intent failed readback validation");
            hit(FaultPoint.AFTER_INTENT);
        } else {
            verifyIntent(scope, approvalSha, anchor, existingIntent,
                    sha256(readNoFollow(txDirectory.resolve("intent.record"), MAX_RECORD_BYTES)));
        }
    }

    private void publishAnchor(Scope scope, String approvalSha, GameSaveStore.PromotionImage image,
                               Path txDirectory) throws IOException {
        String stageName = "anchor.pending-" + UUID.randomUUID();
        Path stage = txDirectory.resolve(stageName);
        createDirectoryNoFollow(stage);
        for (int i = 0; i < 3; i++) {
            if (!scope.present[i]) continue;
            byte[] bytes = image.files.get(scope.fileNames[i]);
            if (bytes == null || bytes.length != scope.sizes[i] || !sha256(bytes).equals(scope.hashes[i]))
                throw new IOException("Canonical PLSV changed while constructing rollback anchor");
            writeNewFile(stage.resolve(anchorComponent(i)), bytes);
        }
        writeNewFile(stage.resolve("canonical.native"), image.nativeBytes);
        LinkedHashMap<String, String> manifest = anchorFields(scope, approvalSha,
                image.nativeBytes.length, sha256(image.nativeBytes));
        byte[] manifestBytes = encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_ANCHOR", manifest);
        writeNewFile(stage.resolve("manifest.record"), manifestBytes); // Manifest is the final staged file.
        syncDirectory(stage);
        verifyAnchorDirectory(scope, approvalSha, stage, sha256(manifestBytes));
        Path target = txDirectory.resolve("anchor");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Immutable promotion anchor already exists");
        try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Rollback anchor requires atomic directory publication", unsupported);
        }
        syncDirectory(txDirectory);
        verifyAnchor(scope, approvalSha, txDirectory);
    }

    private Map<String, String> verifyAnchor(Scope scope, String approvalSha, Path txDirectory)
            throws IOException {
        Path anchor = txDirectory.resolve("anchor");
        requireDirectoryNoFollow(anchor);
        byte[] manifestBytes = readNoFollow(anchor.resolve("manifest.record"), MAX_RECORD_BYTES);
        Map<String, String> manifest = readRecord(anchor.resolve("manifest.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_ANCHOR", anchorKeys());
        verifyAnchorDirectory(scope, approvalSha, anchor, sha256(manifestBytes));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(anchor)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.equals("manifest.record") && !name.equals("canonical.native") &&
                        !name.equals("record0") && !name.equals("record1") && !name.equals("history"))
                    throw new IOException("Rollback anchor contains an unknown path");
            }
        }
        return fieldsWithoutChecksum(manifest);
    }

    private void verifyAnchorDirectory(Scope scope, String approvalSha, Path anchor, String manifestSha)
            throws IOException {
        Map<String, String> manifest = readRecord(anchor.resolve("manifest.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_ANCHOR", anchorKeys());
        if (!sameScope(scope, parseScope(manifest)) || !approvalSha.equals(manifest.get("approval_sha256")) ||
                !manifestSha.equals(sha256(readNoFollow(anchor.resolve("manifest.record"), MAX_RECORD_BYTES))))
            throw new IOException("Rollback anchor manifest provenance or checksum mismatch");
        byte[] nativeBytes = readNoFollow(anchor.resolve("canonical.native"), MAX_NATIVE_BYTES);
        if (nativeBytes.length != parseLong(manifest.get("native_size")) ||
                !sha256(nativeBytes).equals(manifest.get("native_sha256")) ||
                !scope.canonicalSha256.equals(sha256(nativeBytes)) ||
                nativeBytes.length != modeLength(scope.mode))
            throw new IOException("Rollback anchor native payload failed readback verification");
        for (int i = 0; i < 3; i++) {
            Path component = anchor.resolve(anchorComponent(i));
            if (!scope.present[i]) {
                if (Files.exists(component, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Rollback anchor has a record marked absent");
                continue;
            }
            byte[] bytes = readNoFollow(component, MAX_NATIVE_BYTES + 4096);
            if (bytes.length != scope.sizes[i] || !sha256(bytes).equals(scope.hashes[i]))
                throw new IOException("Rollback anchor PLSV record size or checksum mismatch");
        }
    }

    private Map<String, String> ensureIntent(Scope scope, String approvalSha,
                                             Map<String, String> anchor, Path txDirectory) throws IOException {
        Path intentPath = txDirectory.resolve("intent.record");
        Map<String, String> intent = readOptionalRecord(intentPath,
                "PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intentKeys());
        String anchorSha = sha256(readNoFollow(txDirectory.resolve("anchor/manifest.record"), MAX_RECORD_BYTES));
        if (intent == null) {
            LinkedHashMap<String, String> expected = intentFields(scope, approvalSha, anchorSha);
            publishNewFile(intentPath, encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_INTENT", expected));
            intent = readRecord(intentPath, "PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intentKeys());
        }
        verifyIntent(scope, approvalSha, anchor, intent,
                sha256(readNoFollow(intentPath, MAX_RECORD_BYTES)));
        return fieldsWithoutChecksum(intent);
    }

    private void verifyIntent(Scope scope, String approvalSha, Map<String, String> anchor,
                              Map<String, String> intent, String intentSha) throws IOException {
        String anchorSha = sha256(encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_ANCHOR", anchor));
        Map<String, String> expected = intentFields(scope, approvalSha, anchorSha);
        if (intent == null || !expected.equals(fieldsWithoutChecksum(intent)) || !isHash(intentSha))
            throw new IOException("Promotion intent does not match its verified anchor and scope");
    }

    void authorizeSelectorCommit(CheckpointStore.Identity identity, GameSaveStore canonical,
                                 String promotionTransactionId, String branchId) throws IOException {
        TxRead tx = readTransactions(identity);
        if (tx.classification != Classification.RECOVERY_REQUIRED || tx.scope == null ||
                !tx.scope.transactionId.equals(promotionTransactionId) || !tx.scope.branchId.equals(branchId))
            throw new IOException("Selector promotion does not have an active durable intent");
        Scope scope = tx.scope;
        verifyAnchor(scope, tx.approvalSha, tx.directory);
        if (tx.intent == null) throw new IOException("Selector promotion intent is absent");
        GameSaveStore.PromotionImage current = canonical.snapshotForPromotion(identity.romSha256);
        if (tx.rollback != null) {
            verifyRollback(scope, tx.approvalSha, tx.anchorSha, tx.intentSha, tx.rollback);
            if (current.generation != parseLong(tx.rollback.get("target_generation")) ||
                    !current.nativeSha256.equals(tx.rollback.get("target_sha256")))
                throw new IOException("Rollback selector target does not match restored PLSV");
        } else if (!isPromotedGeneration(scope, current)) {
            throw new IOException("Promotion selector target does not match verified branch PLSV");
        }
    }

    private TxRead requirePending(CheckpointStore.Identity identity, String transactionId) throws IOException {
        if (identity == null || transactionId == null || !isUuid(transactionId))
            throw new IOException("Explicit recovery transaction identity is invalid");
        TxRead tx = readTransactions(identity);
        if (tx.classification != Classification.RECOVERY_REQUIRED || tx.scope == null ||
                !transactionId.equals(tx.scope.transactionId))
            throw new IOException("No matching pending promotion requires recovery");
        return tx;
    }

    private TxRead readTransactions(CheckpointStore.Identity identity) {
        Path game = gameDirectory(identity);
        try {
            checkPathComponents(root);
            if (!Files.exists(game, LinkOption.NOFOLLOW_LINKS))
                return emptyRead(Classification.NONE, "No promotion transaction exists");
            requireDirectoryNoFollow(game);
            ArrayList<Path> transactionDirs = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(game)) {
                for (Path path : entries) {
                    String name = path.getFileName().toString();
                    if (".promotion.lock".equals(name)) continue;
                    if (!isUuid(name)) return emptyRead(Classification.CORRUPT,
                            "Promotion directory contains an unknown transaction path");
                    requireDirectoryNoFollow(path);
                    transactionDirs.add(path);
                }
            }
            TxRead pending = null;
            TxRead terminal = null;
            for (Path directory : transactionDirs) {
                TxRead one = readTransaction(identity, directory);
                if (one.classification == Classification.CORRUPT)
                    return one;
                if (one.classification == Classification.RECOVERY_REQUIRED) {
                    if (pending != null) return emptyRead(Classification.CORRUPT,
                            "More than one unresolved promotion exists for this game");
                    pending = one;
                } else if (one.classification == Classification.COMMITTED ||
                        one.classification == Classification.ROLLED_BACK) {
                    terminal = one;
                }
            }
            if (pending != null) return pending;
            return terminal == null ? emptyRead(Classification.NONE, "No unresolved promotion exists") : terminal;
        } catch (IOException | RuntimeException invalid) {
            return emptyRead(Classification.CORRUPT,
                    "Promotion metadata is unreadable or unsafe");
        }
    }

    private TxRead readTransaction(CheckpointStore.Identity identity, Path directory) throws IOException {
        Path approvalPath = directory.resolve("approval.record");
        if (!Files.exists(approvalPath, LinkOption.NOFOLLOW_LINKS))
            return emptyRead(Classification.CORRUPT, "Promotion transaction has no complete approval record");
        byte[] approvalBytes = readNoFollow(approvalPath, MAX_RECORD_BYTES);
        Map<String, String> approval = readRecord(approvalPath,
                "PLUMP_NATIVE_SAVE_PROMOTION_APPROVAL", Arrays.asList(SCOPE_KEYS));
        Scope scope = parseScope(approval);
        if (!scope.identity.sameAs(identity) || !directory.getFileName().toString().equals(scope.transactionId))
            return emptyRead(Classification.CORRUPT, "Promotion approval scope does not match its directory");
        String approvalSha = sha256(approvalBytes);
        Path anchorPath = directory.resolve("anchor");
        Map<String, String> anchor = null;
        String anchorSha = null;
        if (Files.exists(anchorPath, LinkOption.NOFOLLOW_LINKS)) {
            anchor = verifyAnchor(scope, approvalSha, directory);
            anchorSha = sha256(readNoFollow(anchorPath.resolve("manifest.record"), MAX_RECORD_BYTES));
        }
        Map<String, String> intent = readOptionalRecord(directory.resolve("intent.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_INTENT", intentKeys());
        String intentSha = intent == null ? null :
                sha256(readNoFollow(directory.resolve("intent.record"), MAX_RECORD_BYTES));
        if (intent != null) {
            if (anchor == null) throw new IOException("Promotion intent exists without its rollback anchor");
            verifyIntent(scope, approvalSha, anchor, intent, intentSha);
        }
        Map<String, String> rollback = readOptionalRecord(directory.resolve("rollback.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollbackKeys());
        if (rollback != null) {
            if (anchor == null || intent == null)
                throw new IOException("Rollback journal exists without its verified promotion scope");
            verifyRollback(scope, approvalSha, anchorSha, intentSha, rollback);
        }
        Map<String, String> terminal = readOptionalRecord(directory.resolve("terminal.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_TERMINAL", terminalKeys());
        Map<String, String> rollbackTerminal = readOptionalRecord(directory.resolve("rollback-terminal.record"),
                "PLUMP_NATIVE_SAVE_PROMOTION_TERMINAL", terminalKeys());
        if (rollbackTerminal != null) {
            if (anchor == null || intent == null || rollback == null)
                throw new IOException("Rollback terminal receipt is missing its transaction evidence");
            verifyTerminal(scope, approvalSha, anchorSha, intentSha, rollback, rollbackTerminal);
            if (!"ROLLED_BACK".equals(rollbackTerminal.get("status")))
                throw new IOException("Rollback terminal receipt has an invalid status");
            return new TxRead(Classification.ROLLED_BACK, scope, directory, approval, anchor, intent,
                    rollback, rollbackTerminal, approvalSha, anchorSha, intentSha,
                    "Explicit rollback receipt verified");
        }
        if (terminal == null)
            return new TxRead(Classification.RECOVERY_REQUIRED, scope, directory, approval, anchor,
                    intent, rollback, null, approvalSha, anchorSha, intentSha,
                    "Promotion is pending an explicit finish or rollback choice");
        if (anchor == null || intent == null)
            throw new IOException("Terminal promotion receipt is missing its anchor or intent");
        if ("COMMITTED".equals(terminal.get("status"))) {
            verifyTerminal(scope, approvalSha, anchorSha, intentSha, null, terminal);
            if (rollback != null)
                return new TxRead(Classification.RECOVERY_REQUIRED, scope, directory, approval, anchor,
                        intent, rollback, terminal, approvalSha, anchorSha, intentSha,
                        "Explicit rollback is in progress");
            return new TxRead(Classification.COMMITTED, scope, directory, approval, anchor,
                    intent, null, terminal, approvalSha, anchorSha, intentSha,
                    "Terminal promotion receipt verified");
        }
        verifyTerminal(scope, approvalSha, anchorSha, intentSha, rollback, terminal);
        Classification terminalClass = Classification.ROLLED_BACK;
        return new TxRead(terminalClass, scope, directory, approval, anchor, intent, rollback,
                terminal, approvalSha, anchorSha, intentSha, "Terminal promotion receipt verified");
    }

    private Result resultForTerminal(TxRead tx) throws IOException {
        if (tx.terminal == null) throw new IOException("Promotion terminal receipt is unavailable");
        return new Result(tx.classification, tx.scope.transactionId,
                parseLong(tx.terminal.get("final_generation")), tx.terminal.get("final_sha256"), null);
    }

    private static TxRead emptyRead(Classification classification, String detail) {
        return new TxRead(classification, null, null, null, null, null, null, null,
                null, null, null, detail);
    }

    private byte[] branchNativeBytes(Scope scope, HistoricalBranchStore branches) throws IOException {
        verifyBranch(scope, scope.identity, branches);
        HistoricalBranchStore.Branch branch = branches.open(scope.branchId, scope.identity);
        GameSaveStore.PromotionImage image = branch.saves.snapshotForPromotion(scope.identity.romSha256);
        return image.nativeBytes.clone();
    }

    private void verifyBranch(Scope scope, CheckpointStore.Identity identity,
                              HistoricalBranchStore branches) throws IOException {
        if (branches == null || identity == null || !scope.identity.sameAs(identity))
            throw new IOException("Pinned branch identity changed during promotion");
        HistoricalBranchStore.Branch branch = branches.open(scope.branchId, identity);
        GameSaveStore.PromotionImage image = branch.saves.snapshotForPromotion(identity.romSha256);
        validatePayloadSizes(scope.mode, image.nativeBytes.length, image.nativeBytes.length);
        if (!scope.sourceCheckpointId.equals(branch.checkpointId) ||
                scope.branchGeneration != image.generation || !scope.branchSha256.equals(image.nativeSha256))
            throw new IOException("Immutable source or latest branch PLSV changed during promotion");
    }

    private static boolean matchesOriginal(Scope scope, GameSaveStore.PromotionImage image) {
        if (image == null || image.generation != scope.canonicalGeneration ||
                !scope.canonicalSha256.equals(image.nativeSha256)) return false;
        for (int i = 0; i < 3; i++) {
            byte[] bytes = image.files.get(scope.fileNames[i]);
            if ((bytes != null) != scope.present[i]) return false;
            if (bytes != null && (bytes.length != scope.sizes[i] || !scope.hashes[i].equals(sha256(bytes))))
                return false;
        }
        return true;
    }

    private static boolean isPromotedGeneration(Scope scope, GameSaveStore.PromotionImage image) {
        return image != null && scope.canonicalGeneration < Long.MAX_VALUE &&
                image.generation == scope.canonicalGeneration + 1 &&
                scope.branchSha256.equals(image.nativeSha256) &&
                image.nativeBytes.length == modeLength(scope.mode);
    }

    private void validateMode(CheckpointStore.Identity identity, NativeSaveMode mode) throws IOException {
        if (identity == null || mode == null || mode == NativeSaveMode.UNKNOWN)
            throw new IOException("Unknown native save mode cannot be promoted");
        if (mode == NativeSaveMode.GBA_CARTRIDGE_SRAM_32K) {
            if (!"gba".equals(identity.console) || !"mgba-libretro".equals(identity.coreId))
                throw new IOException("GBA SRAM promotion requires the pinned GBA cartridge core");
        } else if (mode == NativeSaveMode.DS_CARTRIDGE_EEPROM_8K) {
            if (!"ds".equals(identity.console) || !"melonds-libretro-ds".equals(identity.coreId))
                throw new IOException("DS EEPROM promotion requires the pinned DS cartridge core");
        } else {
            throw new IOException("Native save mode is not on the supported cartridge whitelist");
        }
    }

    private static void validatePayloadSizes(NativeSaveMode mode, int branchBytes, int canonicalBytes)
            throws IOException {
        int allowed = modeLength(mode);
        if (allowed <= 0 || branchBytes != allowed || canonicalBytes != allowed ||
                branchBytes > MAX_NATIVE_BYTES)
            throw new IOException("Native cartridge save geometry is unsupported or mixed");
    }

    private static int modeLength(NativeSaveMode mode) {
        if (mode == NativeSaveMode.GBA_CARTRIDGE_SRAM_32K) return 32 * 1024;
        if (mode == NativeSaveMode.DS_CARTRIDGE_EEPROM_8K) return 8 * 1024;
        return 0;
    }

    private static void requireLease(EmulatorSessionOwner.PromotionLease lease,
                                     CheckpointStore.Identity identity, String branchId) throws IOException {
        if (lease == null || identity == null || branchId == null || !lease.isValid(identity, branchId))
            throw new IOException("Promotion requires the matching exclusive paused branch owner");
    }

    private static String leaseBranch(EmulatorSessionOwner.PromotionLease lease) {
        return lease == null ? null : lease.branchId();
    }

    private void requireApprovalObject(Approval approval) throws IOException {
        if (approval == null || issuedApprovals.get(approval.approvalId) != approval)
            throw new IOException("Promotion approval is fake, stale or belongs to another store instance");
    }

    private static boolean sameScope(Scope a, Scope b) {
        if (a == null || b == null || !a.identity.sameAs(b.identity)) return false;
        return a.fields().equals(b.fields());
    }

    private LinkedHashMap<String, String> anchorFields(Scope scope, String approvalSha,
                                                       int nativeSize, String nativeSha) {
        LinkedHashMap<String, String> fields = scope.fields();
        fields.put("approval_sha256", approvalSha);
        fields.put("native_size", Integer.toString(nativeSize));
        fields.put("native_sha256", nativeSha);
        return fields;
    }

    private static LinkedHashMap<String, String> intentFields(Scope scope, String approvalSha,
                                                             String anchorSha) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("transaction_id", scope.transactionId);
        values.put("approval_sha256", approvalSha);
        values.put("anchor_record_sha256", anchorSha);
        values.put("console", scope.identity.console);
        values.put("rom_sha256", scope.identity.romSha256);
        values.put("core_id", scope.identity.coreId);
        values.put("core_revision", scope.identity.coreRevision);
        values.put("core_frontend_revision", scope.identity.coreFrontendRevision);
        values.put("core_binary_sha256", scope.identity.coreBinarySha256);
        values.put("abi", scope.identity.abi);
        values.put("libretro_api", Integer.toString(scope.identity.libretroApi));
        values.put("branch_id", scope.branchId);
        values.put("branch_generation", Long.toString(scope.branchGeneration));
        values.put("branch_sha256", scope.branchSha256);
        values.put("source_checkpoint_id", scope.sourceCheckpointId);
        values.put("canonical_generation", Long.toString(scope.canonicalGeneration));
        values.put("canonical_sha256", scope.canonicalSha256);
        values.put("selector_generation", Long.toString(scope.selectorGeneration));
        values.put("selector_transaction_id", scope.selectorTransactionId);
        return values;
    }

    private static LinkedHashMap<String, String> rollbackFields(Scope scope, String approvalSha,
            String anchorSha, String intentSha, long baseGeneration, String baseSha,
            long targetGeneration, String targetSha, String selectorTransactionId) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("transaction_id", scope.transactionId);
        values.put("approval_sha256", approvalSha);
        values.put("anchor_record_sha256", anchorSha);
        values.put("intent_record_sha256", intentSha);
        values.put("base_generation", Long.toString(baseGeneration));
        values.put("base_sha256", baseSha);
        values.put("target_generation", Long.toString(targetGeneration));
        values.put("target_sha256", targetSha);
        values.put("selector_transaction_id", selectorTransactionId);
        return values;
    }

    private static LinkedHashMap<String, String> terminalFields(Scope scope, String approvalSha,
            String anchorSha, String intentSha, String status, long generation, String nativeSha,
            String selectorTransactionId, String rollbackSha) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("transaction_id", scope.transactionId);
        values.put("approval_sha256", approvalSha);
        values.put("anchor_record_sha256", anchorSha);
        values.put("intent_record_sha256", intentSha);
        values.put("status", status);
        values.put("console", scope.identity.console);
        values.put("rom_sha256", scope.identity.romSha256);
        values.put("core_id", scope.identity.coreId);
        values.put("core_revision", scope.identity.coreRevision);
        values.put("core_frontend_revision", scope.identity.coreFrontendRevision);
        values.put("core_binary_sha256", scope.identity.coreBinarySha256);
        values.put("abi", scope.identity.abi);
        values.put("libretro_api", Integer.toString(scope.identity.libretroApi));
        values.put("branch_id", scope.branchId);
        values.put("source_checkpoint_id", scope.sourceCheckpointId);
        values.put("final_generation", Long.toString(generation));
        values.put("final_sha256", nativeSha);
        values.put("selector_transaction_id", selectorTransactionId);
        values.put("rollback_record_sha256", rollbackSha);
        return values;
    }

    private void verifyRollback(Scope scope, String approvalSha, String anchorSha, String intentSha,
                                Map<String, String> rollback) throws IOException {
        if (rollback == null || !rollbackFields(scope, approvalSha, anchorSha, intentSha,
                parseLong(rollback.get("base_generation")), rollback.get("base_sha256"),
                parseLong(rollback.get("target_generation")), rollback.get("target_sha256"),
                rollback.get("selector_transaction_id")).equals(fieldsWithoutChecksum(rollback)) ||
                !isUuid(rollback.get("selector_transaction_id")) ||
                parseLong(rollback.get("target_generation")) <= parseLong(rollback.get("base_generation")) ||
                !scope.canonicalSha256.equals(rollback.get("target_sha256")))
            throw new IOException("Durable rollback intent is invalid or contradictory");
    }

    private void verifyTerminal(Scope scope, String approvalSha, String anchorSha, String intentSha,
                                Map<String, String> rollback, Map<String, String> terminal)
            throws IOException {
        String status = terminal.get("status");
        long generation = parseLong(terminal.get("final_generation"));
        String sha = terminal.get("final_sha256");
        String selectorTransactionId = terminal.get("selector_transaction_id");
        boolean rolledBack = "ROLLED_BACK".equals(status);
        if (!rolledBack && !"COMMITTED".equals(status))
            throw new IOException("Promotion terminal status is unsupported");
        if (rolledBack != (rollback != null))
            throw new IOException("Terminal promotion status disagrees with rollback evidence");
        if (rolledBack) {
            verifyRollback(scope, approvalSha, anchorSha, intentSha, rollback);
            if (generation != parseLong(rollback.get("target_generation")) ||
                    !sha.equals(rollback.get("target_sha256")) ||
                    !selectorTransactionId.equals(rollback.get("selector_transaction_id")) ||
                    !sha256(encodeRecord("PLUMP_NATIVE_SAVE_PROMOTION_ROLLBACK", rollback))
                            .equals(terminal.get("rollback_record_sha256")))
                throw new IOException("Rollback terminal receipt does not match rollback evidence");
        } else if (generation != scope.canonicalGeneration + 1 ||
                !scope.branchSha256.equals(sha) ||
                !scope.selectorTransactionId.equals(selectorTransactionId) ||
                !NONE.equals(terminal.get("rollback_record_sha256"))) {
            throw new IOException("Commit terminal receipt does not match the approved target");
        }
        LinkedHashMap<String, String> expected = terminalFields(scope, approvalSha, anchorSha,
                intentSha, status, generation, sha, selectorTransactionId,
                rolledBack ? terminal.get("rollback_record_sha256") : NONE);
        if (!expected.equals(fieldsWithoutChecksum(terminal)))
            throw new IOException("Promotion terminal receipt scope is invalid");
    }

    private static List<String> anchorKeys() {
        ArrayList<String> keys = new ArrayList<>(Arrays.asList(SCOPE_KEYS));
        keys.add("approval_sha256");
        keys.add("native_size");
        keys.add("native_sha256");
        return keys;
    }

    private static List<String> intentKeys() {
        return Arrays.asList("transaction_id", "approval_sha256", "anchor_record_sha256", "console",
                "rom_sha256", "core_id", "core_revision", "core_frontend_revision", "core_binary_sha256",
                "abi", "libretro_api", "branch_id", "branch_generation", "branch_sha256",
                "source_checkpoint_id", "canonical_generation", "canonical_sha256", "selector_generation",
                "selector_transaction_id");
    }

    private static List<String> rollbackKeys() {
        return Arrays.asList("transaction_id", "approval_sha256", "anchor_record_sha256", "intent_record_sha256",
                "base_generation", "base_sha256", "target_generation", "target_sha256",
                "selector_transaction_id");
    }

    private static List<String> terminalKeys() {
        return Arrays.asList("transaction_id", "approval_sha256", "anchor_record_sha256", "intent_record_sha256",
                "status", "console", "rom_sha256", "core_id", "core_revision", "core_frontend_revision",
                "core_binary_sha256", "abi", "libretro_api", "branch_id", "source_checkpoint_id",
                "final_generation", "final_sha256", "selector_transaction_id", "rollback_record_sha256");
    }

    private static byte[] encodeRecord(String header, Map<String, String> fields) throws IOException {
        StringBuilder body = new StringBuilder(header).append("|1\n");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue();
            if (entry.getKey().contains("=") || value == null || value.contains("\n") || value.contains("\r"))
                throw new IOException("Promotion record field is invalid");
            body.append(entry.getKey()).append('=').append(value).append('\n');
        }
        String checksum = sha256(body.toString().getBytes(StandardCharsets.UTF_8));
        body.append("record_sha256=").append(checksum).append('\n');
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Promotion record exceeds its size bound");
        return bytes;
    }

    private static Map<String, String> readRecord(Path path, String header, List<String> keys)
            throws IOException {
        byte[] bytes = readNoFollow(path, MAX_RECORD_BYTES);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)))
            throw new IOException("Promotion record encoding is invalid");
        String[] lines = text.split("\n", -1);
        if (lines.length != keys.size() + 3 || !(header + "|1").equals(lines[0]) ||
                !lines[lines.length - 1].isEmpty())
            throw new IOException("Promotion record structure is invalid");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder(lines[0]).append('\n');
        for (int i = 0; i < keys.size(); i++) {
            String prefix = keys.get(i) + '=';
            if (!lines[i + 1].startsWith(prefix) || lines[i + 1].indexOf('=', prefix.length()) >= 0)
                throw new IOException("Promotion record field order or name is invalid");
            values.put(keys.get(i), lines[i + 1].substring(prefix.length()));
            body.append(lines[i + 1]).append('\n');
        }
        if (!lines[keys.size() + 1].startsWith("record_sha256=") ||
                !sha256(body.toString().getBytes(StandardCharsets.UTF_8)).equals(
                        lines[keys.size() + 1].substring("record_sha256=".length())))
            throw new IOException("Promotion record checksum mismatch");
        values.put("record_sha256", lines[keys.size() + 1].substring("record_sha256=".length()));
        return values;
    }

    private static Map<String, String> readOptionalRecord(Path path, String header, List<String> keys)
            throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        return readRecord(path, header, keys);
    }

    private static LinkedHashMap<String, String> fieldsWithoutChecksum(Map<String, String> record) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>(record);
        values.remove("record_sha256");
        return values;
    }

    private static LinkedHashMap<String, String> fieldsOnlyScope(Map<String, String> record) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String key : SCOPE_KEYS) values.put(key, record.get(key));
        return values;
    }

    private static Scope parseScope(Map<String, String> record) throws IOException {
        Map<String, String> fields = fieldsOnlyScope(record);
        if (fields.containsValue(null)) throw new IOException("Promotion scope is incomplete");
        try {
            CheckpointStore.Identity identity = parseIdentity(fields);
            NativeSaveMode mode = NativeSaveMode.valueOf(fields.get("mode"));
            String[] names = { identity.romSha256 + ".0.sav", identity.romSha256 + ".1.sav",
                    identity.romSha256 + ".history" };
            boolean[] present = new boolean[3];
            long[] sizes = new long[3];
            String[] hashes = new String[3];
            for (int i = 0; i < 3; i++) {
                String prefix = i == 2 ? "history" : "file" + i;
                present[i] = parseBoolean(fields.get(prefix + "_present"));
                sizes[i] = parseLong(fields.get(prefix + "_size"));
                hashes[i] = fields.get(prefix + "_sha256");
                if (present[i] != (sizes[i] > 0) ||
                        (present[i] ? !isHash(hashes[i]) : !NONE.equals(hashes[i])))
                    throw new IOException("Promotion raw-file manifest fields are inconsistent");
            }
            Scope scope = new Scope(fields.get("transaction_id"), fields.get("approval_id"),
                    parseLong(fields.get("session_id")), identity, mode, fields.get("branch_id"),
                    parseLong(fields.get("branch_generation")), fields.get("branch_sha256"),
                    fields.get("source_checkpoint_id"), parseLong(fields.get("canonical_generation")),
                    fields.get("canonical_sha256"), parseLong(fields.get("selector_generation")),
                    fields.get("source_selector_transaction_id"), fields.get("selector_transaction_id"),
                    names, present, sizes, hashes);
            if (!isUuid(scope.transactionId) || (!NONE.equals(scope.approvalId) && !isUuid(scope.approvalId)) ||
                    scope.sessionId <= 0 || !isUuid(scope.branchId) || !isUuid(scope.sourceCheckpointId) ||
                    !isUuid(scope.sourceSelectorTransactionId) || !isUuid(scope.selectorTransactionId) ||
                    scope.branchGeneration <= 0 ||
                    scope.canonicalGeneration <= 0 || scope.selectorGeneration <= 0 ||
                    !isHash(scope.branchSha256) || !isHash(scope.canonicalSha256))
                throw new IOException("Promotion identity or generation fields are invalid");
            return scope;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Promotion scope fields are invalid", invalid);
        }
    }

    private static CheckpointStore.Identity parseIdentity(Map<String, String> fields) throws IOException {
        try {
            return new CheckpointStore.Identity(fields.get("console"), fields.get("rom_sha256"),
                    fields.get("core_id"), fields.get("core_revision"), fields.get("core_frontend_revision"),
                    fields.get("core_binary_sha256"), fields.get("abi"),
                    Integer.parseInt(fields.get("libretro_api")));
        } catch (RuntimeException invalid) { throw new IOException("Promotion pinned identity is invalid", invalid); }
    }

    private <T> T withFileLock(CheckpointStore.Identity identity, IoAction<T> action) throws IOException {
        if (identity == null || action == null) throw new IOException("Promotion lock identity is unavailable");
        Path game = gameDirectory(identity);
        createDirectoriesSafely(game);
        Path lockPath = game.resolve(".promotion.lock");
        rejectSymlink(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    private Path gameDirectory(CheckpointStore.Identity identity) {
        return root.resolve(identity.console + "-" + identity.romSha256);
    }

    private Path transactionDirectory(CheckpointStore.Identity identity, String transactionId) {
        if (!isUuid(transactionId)) throw new IllegalArgumentException("Invalid promotion transaction ID");
        return gameDirectory(identity).resolve(transactionId);
    }

    private void createDirectoriesSafely(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        checkPathComponents(absolute.getParent());
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryNoFollow(current);
            } else {
                Files.createDirectory(current);
                syncDirectory(current.getParent());
            }
        }
    }

    private static void createDirectoryNoFollow(Path directory) throws IOException {
        rejectSymlink(directory);
        Files.createDirectory(directory);
        requireDirectoryNoFollow(directory);
    }

    private void publishNewFile(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !target.toAbsolutePath().normalize().startsWith(parent))
            throw new IOException("Promotion publication escaped its directory");
        requireDirectoryNoFollow(parent);
        rejectSymlink(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Immutable promotion evidence already exists");
        Path temporary = parent.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) {
                    if (channel.write(data) <= 0) throw new IOException("Promotion record short write");
                }
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Promotion evidence requires atomic publication", unsupported);
            }
            moved = true;
            syncDirectory(parent);
            if (!Arrays.equals(bytes, readNoFollow(target, Math.max(bytes.length, 1))))
                throw new IOException("Promotion evidence failed full readback");
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private void writeNewFile(Path target, byte[] bytes) throws IOException {
        publishNewFile(target, bytes);
    }

    private static byte[] readNoFollow(Path path, int limit) throws IOException {
        rejectSymlink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Promotion evidence path is not a regular file");
        long size = Files.size(path);
        if (size < 0 || size > limit || size > Integer.MAX_VALUE)
            throw new IOException("Promotion evidence exceeds its size bound");
        ByteBuffer data = ByteBuffer.allocate((int) size);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (data.hasRemaining()) {
                int count = channel.read(data);
                if (count < 0) throw new IOException("Promotion evidence was truncated during readback");
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1)
                throw new IOException("Promotion evidence changed size during readback");
        }
        if (Files.size(path) != size) throw new IOException("Promotion evidence changed during readback");
        return data.array();
    }

    private void syncDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("Promotion directory is unavailable");
        requireDirectoryNoFollow(directory);
        directorySync.sync(directory);
    }

    private static void checkPathComponents(Path path) throws IOException {
        if (path == null) return;
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Promotion storage path contains a symlink");
        }
    }

    private static void requireDirectoryNoFollow(Path directory) throws IOException {
        rejectSymlink(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Promotion storage path is not a private directory");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Promotion storage path contains a symlink");
    }

    private void hit(FaultPoint point) throws IOException { faults.at(point); }

    private static String anchorComponent(int index) {
        if (index == 0) return "record0";
        if (index == 1) return "record1";
        return "history";
    }

    private static long parseLong(String value) throws IOException {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) { throw new IOException("Promotion generation or size is invalid", invalid); }
    }

    private static boolean parseBoolean(String value) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Promotion boolean field is invalid");
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) output.append(String.format(Locale.ROOT, "%02x", value & 255));
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
