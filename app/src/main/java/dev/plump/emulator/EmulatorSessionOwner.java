package dev.plump.emulator;

import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Process-wide serialization boundary for the existing GBA and DS activity hosts. */
final class EmulatorSessionOwner {
    enum Console { GBA, DS }

    interface Host {
        boolean initializeCore();
        boolean stopAndSave();
        default StopResult stopAndSaveResult() {
            boolean stopped = stopAndSave();
            return StopResult.legacy(stopped);
        }
        boolean startLoops(long runToken);
        void unloadGame();
        void shutdownCore();
        default CheckpointStore checkpointStore() { return null; }
        default CheckpointStore.Identity checkpointIdentity() { return null; }
        default ActiveTimelineStore activeTimelineStore() { return null; }
        default GameSaveStore canonicalGameSaveStore() { return null; }
        default CheckpointStore.Capture captureCheckpoint() {
            return CheckpointStore.Capture.unsupported(0, "Checkpoint serializer unavailable");
        }
        default HistoricalBranchStore historicalBranchStore() { return null; }
        default NativeSavePromotionStore.NativeSaveMode nativeSavePromotionMode() {
            return NativeSavePromotionStore.NativeSaveMode.UNKNOWN;
        }
        default void nativeSavePromotionCommitted() { }
        default RestoreDisposition restoreHistoricalBranch(HistoricalBranchStore.Branch branch) {
            return RestoreDisposition.FAILED;
        }
        default void checkpointFailed(String message) { }
    }

    static final class StopResult {
        final VirtualSdGenerationStore.Outcome outcome;
        final boolean quiesced;
        final boolean terminal;

        StopResult(VirtualSdGenerationStore.Outcome outcome, boolean quiesced, boolean terminal) {
            this.outcome = outcome;
            this.quiesced = quiesced;
            this.terminal = terminal;
        }

        static StopResult legacy(boolean stopped) {
            // The legacy boolean only says the host stopped cleanly. It carries
            // no generation identity or durable publication proof.
            return new StopResult(null, stopped, false);
        }

        boolean permitsRetirement() {
            return quiesced && (outcome == null ||
                    outcome != VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED);
        }
    }

    enum RestoreDisposition { RESTORED_PAUSED, CANONICAL_FALLBACK_PAUSED, FAILED }

    static final class RestoreResult {
        final RestoreDisposition disposition;
        final String branchId;
        final String error;
        private RestoreResult(RestoreDisposition disposition, String branchId, String error) {
            this.disposition = disposition;
            this.branchId = branchId;
            this.error = error;
        }
    }

    interface GameLoader {
        /** Loads a ROM and fully validates/restores its native save before returning null. */
        String loadAndRestore();
        /** Branch identity selected by a validated cold recovery gate, if any. */
        default String selectedBranchId() { return null; }
        /** Releases a partially loaded ROM after a failed load/restore. */
        void discardFailedLoad();
    }

    static final class HostToken {
        final Console console;
        final long value;

        private HostToken(Console console, long value) {
            this.console = console;
            this.value = value;
        }
    }

    /** Unforgeable, short-lived proof that the owner holds its transition lock for a paused branch. */
    static final class PromotionLease {
        private final EmulatorSessionOwner owner;
        private final long hostValue;
        private final long sessionId;
        private final Console console;
        private final String gameId;
        private final String branchId;
        private boolean active = true;

        private PromotionLease(EmulatorSessionOwner owner, HostToken host, Session session) {
            this.owner = owner;
            hostValue = host.value;
            sessionId = session.id;
            console = session.console;
            gameId = session.gameId;
            branchId = session.branchId;
        }

        boolean isValid(CheckpointStore.Identity identity, String expectedBranchId) {
            return owner.validPromotionLease(this, identity, expectedBranchId);
        }

        long sessionId() { return sessionId; }
        String branchId() { return branchId; }
    }

    static final class PromotionContext {
        final CheckpointStore.Identity identity;
        final NativeSavePromotionStore.NativeSaveMode mode;
        final GameSaveStore canonical;
        final HistoricalBranchStore branches;
        final ActiveTimelineStore timelines;

        PromotionContext(CheckpointStore.Identity identity,
                         NativeSavePromotionStore.NativeSaveMode mode,
                         GameSaveStore canonical, HistoricalBranchStore branches,
                         ActiveTimelineStore timelines) {
            this.identity = identity;
            this.mode = mode;
            this.canonical = canonical;
            this.branches = branches;
            this.timelines = timelines;
        }
    }

    interface PromotionAction<T> {
        T run(PromotionLease lease, PromotionContext context) throws IOException;
    }

    static final class OpenResult {
        final boolean opened;
        final String error;
        final SessionReceiptStore.ReadResult previousReceipt;

        private OpenResult(boolean opened, String error, SessionReceiptStore.ReadResult previousReceipt) {
            this.opened = opened;
            this.error = error;
            this.previousReceipt = previousReceipt;
        }
    }

    private enum Phase { PAUSED, RUNNING, QUIESCING, RECOVERY_REQUIRED }

    private static final class Session {
        final long id;
        final Console console;
        final String gameId;
        Phase phase;
        long runToken;
        String branchId;
        StopResult stopResult;
        boolean gameUnloaded;

        Session(Console console, String gameId, Phase phase) {
            this.id = IDS.incrementAndGet();
            this.console = console;
            this.gameId = gameId;
            this.phase = phase;
        }
    }

    private static final class HostEntry {
        final HostToken token;
        final Host host;
        boolean initialized;
        boolean initializationAttempted;
        boolean coreShutdown;
        boolean retired;
        boolean failedLoadCleanup;
        boolean failedShutdown;

        HostEntry(HostToken token, Host host) {
            this.token = token;
            this.host = host;
        }
    }

    private static final EmulatorSessionOwner PROCESS = new EmulatorSessionOwner();
    private static final AtomicLong IDS = new AtomicLong();

    static EmulatorSessionOwner process() { return PROCESS; }

    private final ReentrantLock transitions = new ReentrantLock(true);
    private final EnumMap<Console, HostEntry> hosts = new EnumMap<>(Console.class);
    private final EnumMap<Console, Session> sessions = new EnumMap<>(Console.class);
    private SessionReceiptStore receipts;
    private Console exclusiveConsole;

    EmulatorSessionOwner() { }

    HostToken attach(Console console, Host host, SessionReceiptStore receiptStore) {
        if (console == null || host == null || receiptStore == null) throw new IllegalArgumentException();
        transitions.lock();
        try {
            synchronized (this) {
                HostEntry old = hosts.get(console);
                if (old != null) throw new IllegalStateException("Prior " + console + " host is unresolved");
                if (receipts == null) receipts = receiptStore;
                HostToken token = new HostToken(console, IDS.incrementAndGet());
                hosts.put(console, new HostEntry(token, host));
                return token;
            }
        } finally {
            transitions.unlock();
        }
    }

    boolean initialize(HostToken token) {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            if (entry == null) return false;
            if (entry.failedShutdown || entry.failedLoadCleanup || entry.coreShutdown) return false;
            synchronized (this) { if (entry.initialized) return true; }
            boolean ready;
            synchronized (this) { entry.initializationAttempted = true; }
            try { ready = entry.host.initializeCore(); }
            catch (RuntimeException failure) { ready = false; }
            if (ready) synchronized (this) { entry.initialized = true; }
            return ready;
        } finally {
            transitions.unlock();
        }
    }

    boolean isCurrentHost(HostToken token) { return currentHost(token) != null; }

    boolean isCurrentRun(HostToken token, long runToken) {
        synchronized (this) {
            HostEntry host = token == null ? null : hosts.get(token.console);
            Session session = token == null ? null : sessions.get(token.console);
            return host != null && host.token.value == token.value && !host.retired &&
                    session != null && session.phase == Phase.RUNNING && session.runToken == runToken &&
                    exclusiveConsole == token.console;
        }
    }

    boolean hasLoadedGame(HostToken token) {
        synchronized (this) {
            HostEntry host = token == null ? null : hosts.get(token.console);
            return host != null && host.token.value == token.value && sessions.containsKey(token.console);
        }
    }

    StopResult lastStopResult(HostToken token) {
        synchronized (this) {
            if (token == null) return null;
            HostEntry host = hosts.get(token.console);
            Session session = sessions.get(token.console);
            return host != null && host.token.value == token.value && session != null
                    ? session.stopResult : null;
        }
    }

    SessionReceiptStore.ReadResult readReceipt(Console console, String gameId) {
        SessionReceiptStore store;
        synchronized (this) { store = receipts; }
        return store == null
                ? new SessionReceiptStore.ReadResult(SessionReceiptStore.State.UNKNOWN, null, false)
                : store.read(toReceiptConsole(console), gameId);
    }

    NativeSavePromotionStore.Proposal proposeNativeSavePromotion(HostToken token) throws IOException {
        return withPausedBranch(token, null, (lease, context) -> context.timelines.promotions()
                .propose(lease, context.identity, context.mode, context.canonical, context.branches));
    }

    NativeSavePromotionStore.Approval approveNativeSavePromotion(HostToken token,
            NativeSavePromotionStore.Proposal proposal, String explicitConfirmation) throws IOException {
        if (proposal == null) throw new IOException("Promotion proposal is unavailable");
        return withPausedBranch(token, proposal.branchId, (lease, context) -> context.timelines.promotions()
                .approve(lease, proposal, explicitConfirmation, context.identity, context.mode,
                        context.canonical, context.branches));
    }

    NativeSavePromotionStore.Result promoteNativeSaveBranch(HostToken token,
            NativeSavePromotionStore.Approval approval) throws IOException {
        if (approval == null) throw new IOException("Promotion approval is unavailable");
        NativeSavePromotionStore.Result completed = retryCommittedPromotion(token, approval);
        if (completed != null) return completed;
        return withPausedBranch(token, approval.branchId, (lease, context) -> {
            NativeSavePromotionStore.Result result = context.timelines.promotions().promote(
                    lease, approval, context.identity, context.mode, context.canonical, context.branches);
            if (result.classification == NativeSavePromotionStore.Classification.COMMITTED) {
                HostEntry entry = currentHost(token);
                Session session = sessionFor(token);
                if (entry == null || session == null || !approval.branchId.equals(session.branchId))
                    throw new IOException("Promotion committed after the owned branch session changed");
                try { entry.host.nativeSavePromotionCommitted(); }
                catch (RuntimeException failure) {
                    markRecovery(session, true);
                    throw new IOException("Promotion committed, but the paused host could not adopt canonical saves", failure);
                }
                synchronized (this) {
                    // The loaded core still contains the branch session's in-memory state.
                    // Retire this host without another save; a fresh cold-open must use canonical.
                    session.phase = Phase.RECOVERY_REQUIRED;
                    entry.failedShutdown = true;
                    exclusiveConsole = token.console;
                }
            }
            return result;
        });
    }

    private NativeSavePromotionStore.Result retryCommittedPromotion(HostToken token,
            NativeSavePromotionStore.Approval approval) throws IOException {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            Session session = sessionFor(token);
            if (entry == null || session == null || !entry.failedShutdown ||
                    session.phase != Phase.RECOVERY_REQUIRED || !approval.branchId.equals(session.branchId))
                return null;
            ActiveTimelineStore timelines;
            GameSaveStore canonical;
            HistoricalBranchStore branches;
            CheckpointStore.Identity identity;
            try {
                timelines = entry.host.activeTimelineStore();
                canonical = entry.host.canonicalGameSaveStore();
                branches = entry.host.historicalBranchStore();
                identity = entry.host.checkpointIdentity();
            }
            catch (RuntimeException unavailable) { throw new IOException("Promotion selector is unavailable", unavailable); }
            if (timelines == null || canonical == null || branches == null || identity == null) return null;
            return timelines.promotions().retryCommitted(approval, identity, canonical, branches);
        } finally { transitions.unlock(); }
    }

    private <T> T withPausedBranch(HostToken token, String requiredBranch,
                                   PromotionAction<T> action) throws IOException {
        if (action == null) throw new IOException("Promotion action is unavailable");
        transitions.lock();
        PromotionLease lease = null;
        Session session = null;
        try {
            HostEntry entry = currentHost(token);
            session = sessionFor(token);
            if (entry == null || session == null || !entry.initialized || entry.failedLoadCleanup ||
                    entry.failedShutdown)
                throw new IOException("A live owned game session is required for promotion");
            synchronized (this) {
                if (session.phase != Phase.PAUSED || exclusiveConsole != null || session.branchId == null)
                    throw new IOException("Promotion requires the exclusive paused historical branch");
                if (requiredBranch != null && !requiredBranch.equals(session.branchId))
                    throw new IOException("Promotion branch no longer matches the owned session");
                exclusiveConsole = token.console;
            }
            CheckpointStore.Identity identity;
            ActiveTimelineStore timelines;
            HistoricalBranchStore branches;
            GameSaveStore canonical;
            NativeSavePromotionStore.NativeSaveMode mode;
            try {
                identity = entry.host.checkpointIdentity();
                timelines = entry.host.activeTimelineStore();
                branches = entry.host.historicalBranchStore();
                canonical = entry.host.canonicalGameSaveStore();
                mode = entry.host.nativeSavePromotionMode();
            } catch (RuntimeException unavailable) {
                throw new IOException("Promotion host identity or storage is unavailable", unavailable);
            }
            if (identity == null || !identity.romSha256.equals(session.gameId) || timelines == null ||
                    branches == null || canonical == null || mode == null)
                throw new IOException("Promotion host identity or native storage is incomplete");
            String expectedConsole = session.console == Console.GBA ? "gba" : "ds";
            if (!expectedConsole.equals(identity.console))
                throw new IOException("Promotion console does not match the owned game session");
            HistoricalBranchStore.Branch branch = branches.open(session.branchId, identity);
            ActiveTimelineStore.PromotionSelection selection = timelines.promotionSelection(identity);
            if (selection.timeline != ActiveTimelineStore.Timeline.BRANCH ||
                    !session.branchId.equals(selection.branchId) || !branch.id.equals(selection.branchId))
                throw new IOException("Durable selector no longer owns the paused branch");
            lease = new PromotionLease(this, token, session);
            return action.run(lease, new PromotionContext(identity, mode, canonical, branches, timelines));
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Native-save promotion failed closed", failure);
        } finally {
            if (lease != null) lease.active = false;
            synchronized (this) {
                if (token != null && exclusiveConsole == token.console) exclusiveConsole = null;
            }
            transitions.unlock();
        }
    }

    private boolean validPromotionLease(PromotionLease lease, CheckpointStore.Identity identity,
                                       String expectedBranchId) {
        if (lease == null || identity == null || !transitions.isHeldByCurrentThread() ||
                lease.owner != this || !lease.active || lease.gameId == null ||
                !lease.gameId.equals(identity.romSha256) || !lease.branchId.equals(expectedBranchId)) return false;
        synchronized (this) {
            HostEntry host = hosts.get(lease.console);
            Session session = sessions.get(lease.console);
            return exclusiveConsole == lease.console && host != null && host.token.value == lease.hostValue &&
                    session != null && session.id == lease.sessionId && session.phase == Phase.PAUSED &&
                    lease.gameId.equals(session.gameId) && lease.branchId.equals(session.branchId);
        }
    }

    OpenResult open(HostToken token, String gameId, GameLoader loader) {
        if (gameId == null || !gameId.matches("[0-9a-f]{64}") || loader == null)
            return new OpenResult(false, "Invalid ROM identity", null);
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            if (entry == null || !entry.initialized) return new OpenResult(false, "Core is unavailable", null);
            if (entry.failedLoadCleanup || entry.failedShutdown)
                return new OpenResult(false, "A native core transition remains unresolved", null);
            SessionReceiptStore.ReadResult prior = readReceipt(token.console, gameId);
            synchronized (this) {
                if (exclusiveConsole != null && exclusiveConsole != token.console)
                    return new OpenResult(false, "Another console has an unresolved session", prior);
            }
            Session old;
            synchronized (this) { old = sessions.get(token.console); }
            if (old != null) {
                if (!stopAndPublishPaused(entry, old))
                    return new OpenResult(false, "Previous session did not stop, save and publish safely", prior);
                try { entry.host.unloadGame(); }
                catch (RuntimeException failure) {
                    markRecovery(old, true);
                    return new OpenResult(false, "Previous core unload failed", prior);
                }
                try { publish(old, SessionReceiptStore.State.CLOSED); }
                catch (IOException publicationFailure) {
                    markRecovery(old, true);
                    return new OpenResult(false, "Previous session close receipt was not durable", prior);
                }
                synchronized (this) {
                    sessions.remove(token.console);
                    if (exclusiveConsole == token.console) exclusiveConsole = null;
                }
            }
            String failure;
            try { failure = loader.loadAndRestore(); }
            catch (RuntimeException exception) {
                failure = exception.getMessage() == null
                        ? "ROM load or native-save restore failed unexpectedly"
                        : exception.getMessage();
            }
            if (failure != null) {
                try { loader.discardFailedLoad(); }
                catch (RuntimeException cleanupFailure) {
                    synchronized (this) {
                        entry.failedLoadCleanup = true;
                        exclusiveConsole = token.console;
                    }
                    return new OpenResult(false,
                            failure + "; failed native cleanup requires a fresh process", prior);
                }
                return new OpenResult(false, failure, prior);
            }
            Session session = new Session(token.console, gameId, Phase.PAUSED);
            session.branchId = loader.selectedBranchId();
            synchronized (this) { sessions.put(token.console, session); }
            try {
                publish(session, SessionReceiptStore.State.PAUSED);
            } catch (IOException publicationFailure) {
                markRecovery(session, true);
                return new OpenResult(false, "ROM loaded, but its session receipt could not be made durable", prior);
            }
            return new OpenResult(true, null, prior);
        } finally {
            transitions.unlock();
        }
    }

    boolean resume(HostToken token) {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            Session session = sessionFor(token);
            if (entry == null || session == null) return false;
            if (entry.failedLoadCleanup || entry.failedShutdown) return false;
            synchronized (this) {
                if (session.phase == Phase.RUNNING) return true;
                if (session.phase != Phase.PAUSED || exclusiveConsole != null) return false;
                if (session.stopResult != null && session.stopResult.terminal) return false;
            }
            try { publish(session, SessionReceiptStore.State.RUNNING); }
            catch (IOException failure) { return false; }
            long runToken = IDS.incrementAndGet();
            synchronized (this) {
                session.runToken = runToken;
                session.phase = Phase.RUNNING;
                session.stopResult = null;
                exclusiveConsole = token.console;
            }
            boolean started;
            try { started = entry.host.startLoops(runToken); }
            catch (RuntimeException failure) { started = false; }
            if (!started) markRecovery(session, true);
            return started;
        } finally {
            transitions.unlock();
        }
    }

    boolean pause(HostToken token) {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            Session session = sessionFor(token);
            if (entry == null) return false;
            if (entry.failedLoadCleanup || entry.failedShutdown) return false;
            if (session == null) return true;
            synchronized (this) {
                if (session.phase == Phase.PAUSED) return true;
                session.phase = Phase.QUIESCING;
                exclusiveConsole = token.console;
            }
            StopResult stopped = stopSession(entry, session);
            if (stopped == null || !stopped.permitsRetirement() ||
                    (stopped.outcome != null &&
                            stopped.outcome != VirtualSdGenerationStore.Outcome.COMMITTED)) {
                markRecovery(session, true);
                return false;
            }
            try { publish(session, SessionReceiptStore.State.PAUSED); }
            catch (IOException failure) {
                markRecovery(session, true);
                return false;
            }
            synchronized (this) {
                session.phase = Phase.PAUSED;
            }
            maybeCreateAutoCheckpoint(entry, session);
            synchronized (this) { if (exclusiveConsole == token.console) exclusiveConsole = null; }
            return true;
        } finally {
            transitions.unlock();
        }
    }

    boolean closeHost(HostToken token) {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            if (entry == null) return false;
            if (entry.failedLoadCleanup || entry.failedShutdown) return false;
            Session session = sessionFor(token);
            if (session != null) {
                boolean ownsActiveSlot;
                synchronized (this) {
                    ownsActiveSlot = exclusiveConsole == null || exclusiveConsole == token.console;
                    if (!ownsActiveSlot) {
                        if (session.phase != Phase.PAUSED) return false;
                    } else {
                        session.phase = Phase.QUIESCING;
                        exclusiveConsole = token.console;
                    }
                }
                boolean reusedTerminalStop = session.stopResult != null && session.stopResult.terminal;
                StopResult stopped = stopSession(entry, session);
                if (stopped == null || !stopped.permitsRetirement()) {
                    markRecovery(session, ownsActiveSlot);
                    return false;
                }
                if (ownsActiveSlot && !reusedTerminalStop)
                    maybeCreateAutoCheckpoint(entry, session);
                if (!session.gameUnloaded) {
                    try { entry.host.unloadGame(); session.gameUnloaded = true; }
                    catch (RuntimeException failure) { markRecovery(session, ownsActiveSlot); return false; }
                }
            }
            if (entry.initializationAttempted && !entry.coreShutdown) {
                try { entry.host.shutdownCore(); }
                catch (RuntimeException failure) {
                    synchronized (this) {
                        entry.failedShutdown = true;
                        exclusiveConsole = token.console;
                        if (session != null) session.phase = Phase.RECOVERY_REQUIRED;
                    }
                    return false;
                }
                synchronized (this) {
                    entry.coreShutdown = true;
                    entry.initialized = false;
                }
            }
            if (session != null) {
                try { publish(session, SessionReceiptStore.State.CLOSED); }
                catch (IOException failure) { markRecovery(session, true); return false; }
                synchronized (this) {
                    sessions.remove(token.console);
                    if (exclusiveConsole == token.console) exclusiveConsole = null;
                }
            }
            synchronized (this) {
                entry.retired = true;
                hosts.remove(token.console);
            }
            return true;
        } finally {
            transitions.unlock();
        }
    }

    boolean noteSaveFailure(HostToken token, long runToken) {
        synchronized (this) {
            HostEntry host = token == null ? null : hosts.get(token.console);
            Session session = token == null ? null : sessions.get(token.console);
            if (host == null || host.token.value != token.value || session == null ||
                    session.phase != Phase.RUNNING || session.runToken != runToken) return false;
            session.phase = Phase.RECOVERY_REQUIRED;
            exclusiveConsole = token.console;
            return true;
        }
    }

    CheckpointStore.Record createManualCheckpoint(HostToken token) throws IOException {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            Session session = sessionFor(token);
            if (entry == null || session == null || !entry.initialized)
                throw new IOException("No current loaded session for checkpoint capture");
            synchronized (this) {
                if (session.phase != Phase.PAUSED || exclusiveConsole != null)
                    throw new IOException("Checkpoint capture requires the exclusive paused session");
                if (session.branchId != null)
                    throw new IOException("Historical branches cannot publish canonical-tagged checkpoints");
                exclusiveConsole = token.console;
            }
            try { return createCheckpoint(entry, session, CheckpointStore.Kind.MANUAL); }
            finally {
                synchronized (this) { if (exclusiveConsole == token.console) exclusiveConsole = null; }
            }
        } finally {
            transitions.unlock();
        }
    }

    /** Internal restore entry point. It deliberately has no Activity or public UI binding. */
    RestoreResult restoreHistoricalCheckpoint(HostToken token, String checkpointId) {
        transitions.lock();
        try {
            HostEntry entry = currentHost(token);
            Session session = sessionFor(token);
            if (entry == null || session == null || !entry.initialized)
                return new RestoreResult(RestoreDisposition.FAILED, null, "No current loaded session");
            synchronized (this) {
                if (session.phase != Phase.PAUSED || exclusiveConsole != null || session.branchId != null)
                    return new RestoreResult(RestoreDisposition.FAILED, null,
                            "Historical restore requires an exclusive paused canonical session");
                session.phase = Phase.QUIESCING;
                exclusiveConsole = token.console;
            }

            boolean saved;
            try { saved = entry.host.stopAndSave(); }
            catch (RuntimeException failure) { saved = false; }
            if (!saved) {
                markRecovery(session, true);
                return new RestoreResult(RestoreDisposition.FAILED, null,
                        "Canonical save could not be durably quiesced");
            }
            try { publish(session, SessionReceiptStore.State.PAUSED); }
            catch (IOException failure) {
                markRecovery(session, true);
                return new RestoreResult(RestoreDisposition.FAILED, null,
                        "Paused session receipt could not be made durable");
            }
            synchronized (this) { session.phase = Phase.PAUSED; }

            HistoricalBranchStore branches;
            CheckpointStore checkpoints;
            CheckpointStore.Identity identity;
            ActiveTimelineStore timelines;
            try {
                branches = entry.host.historicalBranchStore();
                checkpoints = entry.host.checkpointStore();
                identity = entry.host.checkpointIdentity();
                timelines = entry.host.activeTimelineStore();
            } catch (RuntimeException unavailable) {
                synchronized (this) { if (exclusiveConsole == token.console) exclusiveConsole = null; }
                return new RestoreResult(RestoreDisposition.FAILED, null,
                        "Pinned branch storage or identity is unavailable");
            }
            String expectedConsole = session.console == Console.GBA ? "gba" : "ds";
            if (branches == null || checkpoints == null || timelines == null || identity == null ||
                    !expectedConsole.equals(identity.console) || !session.gameId.equals(identity.romSha256)) {
                synchronized (this) { if (exclusiveConsole == token.console) exclusiveConsole = null; }
                return new RestoreResult(RestoreDisposition.FAILED, null,
                        "Checkpoint identity does not match the owned game session");
            }

            HistoricalBranchStore.Branch branch;
            try { branch = branches.create(checkpoints, checkpointId, identity); }
            catch (IOException | RuntimeException failure) {
                synchronized (this) { if (exclusiveConsole == token.console) exclusiveConsole = null; }
                return new RestoreResult(RestoreDisposition.FAILED, null,
                        failure.getMessage() == null ? "Historical branch creation failed" : failure.getMessage());
            }

            ActiveTimelineStore.PendingSelection pending;
            try { pending = timelines.prepareBranch(identity, branches, branch); }
            catch (IOException | RuntimeException failure) {
                timelines.markAmbiguous(identity);
                markRecovery(session, true);
                return new RestoreResult(RestoreDisposition.FAILED, branch.id,
                        failure.getMessage() == null ? "Branch selection could not be prepared safely" : failure.getMessage());
            }

            RestoreDisposition disposition;
            try { disposition = entry.host.restoreHistoricalBranch(branch); }
            catch (RuntimeException failure) { disposition = RestoreDisposition.FAILED; }
            if (disposition == RestoreDisposition.RESTORED_PAUSED) {
                try { timelines.commit(pending); }
                catch (IOException | RuntimeException failure) {
                    timelines.markAmbiguous(identity);
                    markRecovery(session, true);
                    return new RestoreResult(RestoreDisposition.FAILED, branch.id,
                            "Branch core restore completed, but durable selection is unresolved; both histories remain preserved");
                }
                synchronized (this) {
                    session.branchId = branch.id;
                    session.phase = Phase.PAUSED;
                    if (exclusiveConsole == token.console) exclusiveConsole = null;
                }
                return new RestoreResult(disposition, branch.id, null);
            }

            timelines.markAmbiguous(identity);
            markRecovery(session, true);
            return new RestoreResult(RestoreDisposition.FAILED, branch.id,
                    "Historical restore failed; core was retired and canonical plus branch histories remain preserved for explicit recovery");
        } finally {
            transitions.unlock();
        }
    }

    private boolean stopAndPublishPaused(HostEntry entry, Session session) {
        synchronized (this) {
            session.phase = Phase.QUIESCING;
            exclusiveConsole = session.console;
        }
        StopResult stopped = stopSession(entry, session);
        if (stopped == null || !stopped.permitsRetirement() ||
                (stopped.outcome != null &&
                        stopped.outcome != VirtualSdGenerationStore.Outcome.COMMITTED)) {
            markRecovery(session, true);
            return false;
        }
        try { publish(session, SessionReceiptStore.State.PAUSED); }
        catch (IOException failure) { markRecovery(session, true); return false; }
        synchronized (this) {
            session.phase = Phase.PAUSED;
        }
        maybeCreateAutoCheckpoint(entry, session);
        synchronized (this) { if (exclusiveConsole == session.console) exclusiveConsole = null; }
        return true;
    }

    private StopResult stopSession(HostEntry entry, Session session) {
        synchronized (this) {
            if (session.stopResult != null && session.stopResult.terminal) return session.stopResult;
        }
        StopResult result;
        try { result = entry.host.stopAndSaveResult(); }
        catch (RuntimeException failure) {
            result = StopResult.legacy(false);
        }
        if (result == null)
            result = new StopResult(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false, true);
        synchronized (this) { session.stopResult = result; }
        return result;
    }

    private void maybeCreateAutoCheckpoint(HostEntry entry, Session session) {
        synchronized (this) { if (session.branchId != null) return; }
        try { createCheckpoint(entry, session, CheckpointStore.Kind.AUTO); }
        catch (IOException | RuntimeException failure) {
            try { entry.host.checkpointFailed(failure.getMessage()); }
            catch (RuntimeException ignored) { }
        }
    }

    private CheckpointStore.Record createCheckpoint(HostEntry entry, Session session,
                                                     CheckpointStore.Kind kind) throws IOException {
        CheckpointStore.Identity identity;
        CheckpointStore store;
        CheckpointStore.Capture capture;
        try {
            identity = entry.host.checkpointIdentity();
            store = entry.host.checkpointStore();
        } catch (RuntimeException failure) {
            throw new IOException("Checkpoint host identity or storage is unavailable", failure);
        }
        if (identity == null || store == null)
            throw new IOException("Checkpoint storage identity is unavailable");
        String expectedConsole = session.console == Console.GBA ? "gba" : "ds";
        if (!expectedConsole.equals(identity.console) || !session.gameId.equals(identity.romSha256))
            throw new IOException("Checkpoint identity does not match the owned game session");
        try { capture = entry.host.captureCheckpoint(); }
        catch (RuntimeException failure) { throw new IOException("Checkpoint serializer capture failed", failure); }
        return store.create(identity, capture, kind, session.runToken);
    }

    private boolean publish(Session session, SessionReceiptStore.State state) throws IOException {
        SessionReceiptStore store;
        synchronized (this) { store = receipts; }
        if (store == null) throw new IOException("Session receipt storage unavailable");
        store.publish(toReceiptConsole(session.console), session.gameId, state);
        return true;
    }

    private void markRecovery(Session session, boolean exclusive) {
        synchronized (this) {
            session.phase = Phase.RECOVERY_REQUIRED;
            if (exclusive) exclusiveConsole = session.console;
        }
    }

    private HostEntry currentHost(HostToken token) {
        synchronized (this) {
            if (token == null) return null;
            HostEntry entry = hosts.get(token.console);
            return entry != null && entry.token.value == token.value && !entry.retired ? entry : null;
        }
    }

    private Session sessionFor(HostToken token) {
        synchronized (this) {
            if (token == null) return null;
            HostEntry entry = hosts.get(token.console);
            return entry != null && entry.token.value == token.value && !entry.retired
                    ? sessions.get(token.console) : null;
        }
    }

    private static SessionReceiptStore.Console toReceiptConsole(Console console) {
        return console == Console.GBA ? SessionReceiptStore.Console.GBA : SessionReceiptStore.Console.DS;
    }
}
