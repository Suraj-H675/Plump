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
        boolean startLoops(long runToken);
        void unloadGame();
        void shutdownCore();
        default CheckpointStore checkpointStore() { return null; }
        default CheckpointStore.Identity checkpointIdentity() { return null; }
        default CheckpointStore.Capture captureCheckpoint() {
            return CheckpointStore.Capture.unsupported(0, "Checkpoint serializer unavailable");
        }
        default void checkpointFailed(String message) { }
    }

    interface GameLoader {
        /** Loads a ROM and fully validates/restores its native save before returning null. */
        String loadAndRestore();
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
        final Console console;
        final String gameId;
        Phase phase;
        long runToken;

        Session(Console console, String gameId, Phase phase) {
            this.console = console;
            this.gameId = gameId;
            this.phase = phase;
        }
    }

    private static final class HostEntry {
        final HostToken token;
        final Host host;
        boolean initialized;
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
            if (entry.failedShutdown || entry.failedLoadCleanup) return false;
            synchronized (this) { if (entry.initialized) return true; }
            boolean ready;
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

    SessionReceiptStore.ReadResult readReceipt(Console console, String gameId) {
        SessionReceiptStore store;
        synchronized (this) { store = receipts; }
        return store == null
                ? new SessionReceiptStore.ReadResult(SessionReceiptStore.State.UNKNOWN, null, false)
                : store.read(toReceiptConsole(console), gameId);
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
            }
            try { publish(session, SessionReceiptStore.State.RUNNING); }
            catch (IOException failure) { return false; }
            long runToken = IDS.incrementAndGet();
            synchronized (this) {
                session.runToken = runToken;
                session.phase = Phase.RUNNING;
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
            boolean stopped;
            try { stopped = entry.host.stopAndSave(); }
            catch (RuntimeException failure) { stopped = false; }
            if (!stopped) {
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
                boolean stopped;
                try { stopped = entry.host.stopAndSave(); }
                catch (RuntimeException failure) { stopped = false; }
                if (!stopped) { markRecovery(session, ownsActiveSlot); return false; }
                if (ownsActiveSlot) maybeCreateAutoCheckpoint(entry, session);
                try { entry.host.unloadGame(); }
                catch (RuntimeException failure) { markRecovery(session, ownsActiveSlot); return false; }
            }
            if (entry.initialized) {
                try { entry.host.shutdownCore(); }
                catch (RuntimeException failure) {
                    synchronized (this) {
                        entry.failedShutdown = true;
                        exclusiveConsole = token.console;
                        if (session != null) session.phase = Phase.RECOVERY_REQUIRED;
                    }
                    return false;
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

    private boolean stopAndPublishPaused(HostEntry entry, Session session) {
        synchronized (this) {
            session.phase = Phase.QUIESCING;
            exclusiveConsole = session.console;
        }
        boolean stopped;
        try { stopped = entry.host.stopAndSave(); }
        catch (RuntimeException failure) { stopped = false; }
        if (!stopped) { markRecovery(session, true); return false; }
        try { publish(session, SessionReceiptStore.State.PAUSED); }
        catch (IOException failure) { markRecovery(session, true); return false; }
        synchronized (this) {
            session.phase = Phase.PAUSED;
        }
        maybeCreateAutoCheckpoint(entry, session);
        synchronized (this) { if (exclusiveConsole == session.console) exclusiveConsole = null; }
        return true;
    }

    private void maybeCreateAutoCheckpoint(HostEntry entry, Session session) {
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
