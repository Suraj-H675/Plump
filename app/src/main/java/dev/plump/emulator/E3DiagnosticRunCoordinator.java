package dev.plump.emulator;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide admission and cancellation state for the isolated E3 host run. */
final class E3DiagnosticRunCoordinator {
    enum Phase {
        RESERVED, PREFLIGHT, SEED, BASELINE, WORKING_COPY, CORE_INITIALIZATION,
        GAME_LOAD, FRAMES, COMMIT, COMMIT_AUTHORIZED, CLEANUP, BLOCKED, FINISHED
    }

    interface Worker {
        RunResult run(RunControl control) throws Exception;
    }

    interface Observer {
        void changed(Snapshot snapshot);
    }

    static final class Snapshot {
        final UUID runId;
        final Phase phase;
        final boolean cancellationRequested;
        final String candidateGenerationId;
        final String message;
        final RunResult result;

        Snapshot(UUID runId, Phase phase, boolean cancellationRequested,
                 String candidateGenerationId, String message, RunResult result) {
            this.runId = runId;
            this.phase = phase;
            this.cancellationRequested = cancellationRequested;
            this.candidateGenerationId = candidateGenerationId;
            this.message = message;
            this.result = result;
        }
    }

    static final class RunResult {
        final VirtualSdGenerationStore.Outcome outcome;
        final VirtualSdGenerationStore.TransactionIdentity transaction;
        final VirtualSdGenerationStore.Generation generation;
        final String message;
        final boolean releaseAdmission;

        RunResult(VirtualSdGenerationStore.Outcome outcome,
                  VirtualSdGenerationStore.TransactionIdentity transaction,
                  VirtualSdGenerationStore.Generation generation,
                  String message, boolean releaseAdmission) {
            this.outcome = outcome;
            this.transaction = transaction;
            this.generation = generation;
            this.message = message;
            this.releaseAdmission = releaseAdmission;
        }
    }

    static final class StartResult {
        final RunHandle handle;
        final boolean started;

        StartResult(RunHandle handle, boolean started) {
            this.handle = handle;
            this.started = started;
        }
    }

    final class RunHandle {
        private final Run run;

        private RunHandle(Run run) { this.run = run; }

        UUID id() { return run.id; }
        Snapshot snapshot() { return run.snapshot(); }
        void requestCancel() { run.requestCancel(); }
        void attachObserver(Observer observer) { run.attachObserver(observer); }
        void detachObserver(Observer observer) { run.detachObserver(observer); }
        boolean isActive() { return active.get() == run; }
    }

    static final class RunControl implements VirtualSdGenerationStore.TransactionControl {
        private final Run run;
        private String authorizedCandidate;
        private boolean cancellationRequested;

        private RunControl(Run run) { this.run = run; }

        void checkpoint(Phase phase) throws IOException {
            synchronized (this) {
                if (cancellationRequested && authorizedCandidate == null)
                    throw new CancelledRunException();
                run.phase = phase;
            }
            run.notifyObserver();
        }

        UUID runId() { return run.id; }

        void report(String message) {
            run.message = message;
            run.notifyObserver();
        }

        void enterCleanup() {
            synchronized (this) { run.phase = Phase.CLEANUP; }
            run.notifyObserver();
        }

        void checkCancelled() throws IOException {
            synchronized (this) {
                if (cancellationRequested && authorizedCandidate == null)
                    throw new CancelledRunException();
            }
        }

        @Override public void checkCancellation() throws IOException { checkCancelled(); }

        @Override public boolean authorizePublication(String candidateGenerationId) {
            synchronized (this) {
                if (authorizedCandidate != null)
                    throw new IllegalStateException("A publication is already authorized for this run");
                if (cancellationRequested) return false;
                authorizedCandidate = candidateGenerationId;
                run.phase = Phase.COMMIT_AUTHORIZED;
            }
            run.candidateGenerationId = candidateGenerationId;
            run.notifyObserver();
            return true;
        }

        @Override public void publicationResolved(String candidateGenerationId,
                                                  VirtualSdGenerationStore.Outcome outcome) {
            synchronized (this) {
                if (!candidateGenerationId.equals(authorizedCandidate)) return;
                if (outcome != VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED)
                    authorizedCandidate = null;
            }
            run.notifyObserver();
        }

        private void requestCancel() {
            synchronized (this) {
                cancellationRequested = true;
            }
            run.notifyObserver();
        }

        synchronized boolean cancellationRequested() { return cancellationRequested; }
        synchronized boolean publicationAuthorized() { return authorizedCandidate != null; }
    }

    private static final class CancelledRunException extends IOException {
        CancelledRunException() { super("E3 run cancelled before publication authorization"); }
    }

    private static final E3DiagnosticRunCoordinator PROCESS = new E3DiagnosticRunCoordinator();

    static E3DiagnosticRunCoordinator process() { return PROCESS; }

    private final AtomicReference<Run> active = new AtomicReference<>();
    private volatile RunResult lastResult;

    StartResult start(Worker worker) {
        if (worker == null) throw new IllegalArgumentException("E3 run worker is required");
        Run run;
        while (true) {
            Run current = active.get();
            if (current != null) return new StartResult(current.handle, false);
            run = new Run(UUID.randomUUID(), this);
            if (active.compareAndSet(null, run)) break;
        }
        Run admitted = run;
        Thread thread = new Thread(() -> execute(admitted, worker), "E3-host-run-" + admitted.id);
        admitted.workerThread = thread;
        try {
            thread.start();
            return new StartResult(admitted.handle, true);
        } catch (RuntimeException failure) {
            admitted.phase = Phase.FINISHED;
            active.compareAndSet(admitted, null);
            admitted.notifyObserver();
            return new StartResult(admitted.handle, false);
        }
    }

    RunHandle activeRun() {
        Run run = active.get();
        return run == null ? null : run.handle;
    }

    RunResult lastResult() { return lastResult; }

    private void execute(Run run, Worker worker) {
        RunResult result;
        try {
            run.phase = Phase.PREFLIGHT;
            run.notifyObserver();
            result = worker.run(run.control);
            if (result == null) throw new IllegalStateException("E3 worker returned no outcome");
        } catch (Throwable failure) {
            result = new RunResult(null, null, null,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()), false);
        }
        run.result = result;
        if (result.releaseAdmission) {
            run.phase = Phase.FINISHED;
            lastResult = result;
            active.compareAndSet(run, null);
        } else {
            run.phase = Phase.BLOCKED;
        }
        run.notifyObserver();
    }

    private static final class Run {
        final UUID id;
        final E3DiagnosticRunCoordinator coordinator;
        final RunControl control;
        final RunHandle handle;
        volatile Phase phase = Phase.RESERVED;
        volatile String candidateGenerationId;
        volatile String message;
        volatile RunResult result;
        volatile Thread workerThread;
        private final AtomicReference<Observer> observer = new AtomicReference<>();

        Run(UUID id, E3DiagnosticRunCoordinator coordinator) {
            this.id = id;
            this.coordinator = coordinator;
            this.control = new RunControl(this);
            this.handle = coordinator.new RunHandle(this);
        }

        void requestCancel() { control.requestCancel(); }

        Snapshot snapshot() {
            return new Snapshot(id, phase, control.cancellationRequested(),
                    candidateGenerationId, message, result);
        }

        void attachObserver(Observer next) {
            if (next == null) throw new IllegalArgumentException("Observer is required");
            observer.set(next);
            deliver(next);
        }

        void detachObserver(Observer expected) {
            if (expected != null) observer.compareAndSet(expected, null);
        }

        void notifyObserver() {
            Observer current = observer.get();
            if (current != null) deliver(current);
        }

        private void deliver(Observer current) {
            try { current.changed(snapshot()); }
            catch (RuntimeException ignored) { }
        }
    }
}
