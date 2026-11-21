package dev.plump.emulator;

import android.app.Activity;
import android.os.Bundle;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.system.Os;
import android.system.OsConstants;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/** E3-only diagnostic UI. All storage and native session work stays on its reserved worker. */
public final class E3TestActivity extends Activity {
    private static final long IMAGE_BYTES = VirtualSdFat16Validator.IMAGE_BYTES;
    private static final long PREFLIGHT_BYTES = 768L * 1024 * 1024;
    private static final String CORE_REV = "16f127dbb587a73371827ce79689c5d237c4b59b";
    private static final String FRONTEND_REV = "f394adbacb5722ee97c1b37c8064da9a25818310";
    private static final String FAULT_EXTRA = "dev.plump.emulator.E3_TEST_FAULT";
    private static final String MODE_NONE = "NONE";
    private static final String MODE_DENY_LAUNCH = "DENY_LAUNCH_CAPACITY";
    private static final String MODE_DENY_WORKING = "DENY_WORKING_COPY_CAPACITY";

    private final E3DiagnosticRunCoordinator coordinator = E3DiagnosticRunCoordinator.process();
    private final EmulatorSessionOwner owner = EmulatorSessionOwner.process();
    private TextView status;
    private Button run;
    private Button recover;
    private E3DiagnosticRunCoordinator.RunHandle runHandle;
    private ActivityObserver observer;
    private String selectedFault = MODE_NONE;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BuildConfig.E3_TEST || !BuildConfig.E3_EXPECTED_PACKAGE.equals(getPackageName())) {
            finish();
            return;
        }
        selectedFault = readDeviceFaultMode();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 38, 28, 28);
        status = new TextView(this);
        status.setText("PLUMP E3C. Isolated host lifecycle test.\n" +
                "Only the embedded CC0 DLDI counter fixture is permitted.\n" +
                "Each run attempts one genuine write. Unverified generations stay quarantined.\n" +
                "This build does NOT use or import ordinary Plump data.\n\n" +
                "Tap Run only during the separately authorized device test.\n" +
                "Fault injection: " + selectedFault);
        run = new Button(this);
        run.setText("Run isolated diagnostic once");
        run.setOnClickListener(view -> startDiagnostic());
        recover = new Button(this);
        recover.setText("Reconcile exact pending transaction");
        recover.setOnClickListener(view -> startReconciliation());
        layout.addView(status);
        layout.addView(run);
        layout.addView(recover);
        setContentView(layout);
    }

    @Override protected void onResume() {
        super.onResume();
        observer = new ActivityObserver();
        runHandle = coordinator.activeRun();
        if (runHandle != null) {
            runHandle.attachObserver(observer);
            run.setEnabled(false);
            recover.setEnabled(false);
        } else {
            E3DiagnosticRunCoordinator.RunResult previous = coordinator.lastResult();
            if (previous != null) status.setText(previous.message);
            run.setEnabled(true);
            recover.setEnabled(true);
        }
    }

    @Override protected void onPause() {
        E3DiagnosticRunCoordinator.RunHandle active = runHandle;
        if (active != null && active.isActive()) active.requestCancel();
        detachObserver();
        super.onPause();
    }

    @Override protected void onDestroy() {
        detachObserver();
        super.onDestroy();
    }

    private void startDiagnostic() {
        E3DiagnosticRunCoordinator.StartResult started = coordinator.start(this::runDiagnosticOnce);
        attachStartedRun(started);
    }

    private void startReconciliation() {
        E3DiagnosticRunCoordinator.StartResult started = coordinator.start(this::reconcilePendingOnce);
        attachStartedRun(started);
    }

    private void attachStartedRun(E3DiagnosticRunCoordinator.StartResult started) {
        runHandle = started.handle;
        if (observer != null) runHandle.attachObserver(observer);
        if (!started.started) {
            run.setEnabled(coordinator.activeRun() == null);
            recover.setEnabled(coordinator.activeRun() == null);
            if (coordinator.activeRun() != null)
                status.setText("An earlier E3 run still owns or is resolving the native session. " +
                        "Wait for its worker or restart this test process before another run.");
        } else {
            run.setEnabled(false);
            recover.setEnabled(false);
        }
    }

    private void detachObserver() {
        ActivityObserver previous = observer;
        observer = null;
        if (previous == null) return;
        previous.attached = false;
        if (runHandle != null) runHandle.detachObserver(previous);
    }

    private final class ActivityObserver implements E3DiagnosticRunCoordinator.Observer {
        volatile boolean attached = true;

        @Override public void changed(E3DiagnosticRunCoordinator.Snapshot snapshot) {
            runOnUiThread(() -> {
                if (!attached || isFinishing() || status == null || run == null) return;
                if (snapshot.result != null) status.setText(snapshot.result.message);
                else if (snapshot.message != null) status.setText(snapshot.message);
                else status.setText("E3 run " + snapshot.phase.name().toLowerCase(java.util.Locale.ROOT));
                run.setEnabled(snapshot.phase == E3DiagnosticRunCoordinator.Phase.FINISHED &&
                        coordinator.activeRun() == null);
                recover.setEnabled(snapshot.phase == E3DiagnosticRunCoordinator.Phase.FINISHED &&
                        coordinator.activeRun() == null);
            });
        }
    }

    private E3DiagnosticRunCoordinator.RunResult runDiagnosticOnce(
            E3DiagnosticRunCoordinator.RunControl control) {
        VirtualSdGenerationStore store = null;
        VirtualSdGenerationStore.WorkingSession working = null;
        VirtualSdGenerationStore.Generation oldHead = null;
        VirtualSdGenerationStore.TransactionResult baselinePublication = null;
        VirtualSdGenerationStore.TransactionResult publication = null;
        DiagnosticHost host = null;
        EmulatorSessionOwner.HostToken hostToken = null;
        String failureMessage = null;
        boolean releaseAdmission = true;
        boolean ownerAttachmentFailed = false;
        boolean authorityUncertain = false;
        long previousCounter = -1;
        // Fault modes are allowed only in the separately installed disposable package.
        // The one-shot fault hook is armed after a verified baseline and real native
        // frame execution, never during baseline construction or another app's run.
        final String faultMode = selectedFault;
        final AtomicBoolean workingCapacityFaultArmed = new AtomicBoolean(false);
        final AtomicBoolean commitFaultArmed = new AtomicBoolean(false);
        final AtomicBoolean commitFaultFired = new AtomicBoolean(false);
        final VirtualSdGenerationStore.FaultPoint requestedCommitFault = commitFaultPoint(faultMode);

        try {
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.PREFLIGHT);
            requireTestIdentity();
            byte[] rom = verifiedFixture();
            control.checkCancelled();
            verifyNativeLibrary(control);
            Path privateRoot = getFilesDir().toPath().toRealPath();
            if (!privateRoot.startsWith(getDataDir().toPath().toRealPath()))
                throw new IOException("Experimental storage is not within this app's private data");
            StorageCapacity capacity = StorageCapacity.withAllocationPreflight(path -> {
                if (MODE_DENY_LAUNCH.equals(faultMode) ||
                        (MODE_DENY_WORKING.equals(faultMode) && workingCapacityFaultArmed.get()))
                    return 0;
                return new StatFs(path.toString()).getAvailableBytes();
            }, this::allocatableBytes);
            capacity.require(privateRoot, PREFLIGHT_BYTES, "128 MiB snapshots plus rollback margin");
            control.checkCancelled();

            control.checkpoint(E3DiagnosticRunCoordinator.Phase.SEED);
            Path base = privateRoot.resolve("e3-only");
            Files.createDirectories(base);
            Path seed = base.resolve("baseline-fat16.bin");
            createVerifiedSeed(seed, control);
            control.checkCancelled();

            CheckpointStore.Identity identity = new CheckpointStore.Identity(
                    "ds", E3DiagnosticFatRecord.ROM_SHA256, "melondsds-libretro",
                    CORE_REV, FRONTEND_REV, BuildConfig.E3_CORE_SHA256, "arm64-v8a", 1);
            store = new VirtualSdGenerationStore(
                    base.resolve("rom-" + E3DiagnosticFatRecord.ROM_SHA256).resolve("canonical"),
                    identity, "canonical", IMAGE_BYTES, this::syncDirectory,
                    new VirtualSdFat16Validator(), point -> {
                        if (commitFaultArmed.get() && requestedCommitFault == point &&
                                commitFaultFired.compareAndSet(false, true))
                            throw new IOException("E3 device fault injected at " + point);
                    }, capacity);

            control.checkpoint(E3DiagnosticRunCoordinator.Phase.BASELINE);
            try { oldHead = store.currentForWrite(); }
            catch (IOException uncertainAuthority) {
                authorityUncertain = true;
                releaseAdmission = false;
                throw uncertainAuthority;
            }
            if (oldHead == null) {
                baselinePublication = store.initializeTransaction(seed, "none", "none",
                        UUID.randomUUID().toString(), control);
                if (baselinePublication.outcome != VirtualSdGenerationStore.Outcome.COMMITTED ||
                        !baselinePublication.cleanupResolved) {
                    if (baselinePublication.outcome == VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED ||
                            !baselinePublication.cleanupResolved)
                        releaseAdmission = false;
                    throw new IOException("E3 baseline outcome is " + baselinePublication.outcome,
                            baselinePublication.failure);
                }
                oldHead = baselinePublication.generation;
            }
            control.checkCancelled();
            if (oldHead.sequence >= 4)
                throw new IOException("E3 diagnostic reached its three-write limit; no automatic cleanup");
            previousCounter = E3DiagnosticFatRecord.read(oldHead.imageFile, oldHead.sequence > 1).counter;

            control.checkpoint(E3DiagnosticRunCoordinator.Phase.WORKING_COPY);
            if (MODE_DENY_WORKING.equals(faultMode)) workingCapacityFaultArmed.set(true);
            working = store.openSession(control);
            control.checkCancelled();

            Path system = base.resolve("system");
            Files.createDirectories(system);
            control.checkCancelled();
            DiagnosticHost diagnosticHost = new DiagnosticHost(control, store, working, system, previousCounter);
            host = diagnosticHost;
            try {
                hostToken = owner.attach(EmulatorSessionOwner.Console.DS, host,
                        new SessionReceiptStore(base.resolve("e3-session-receipts")));
            } catch (IllegalStateException unresolvedOwner) {
                ownerAttachmentFailed = true;
                throw unresolvedOwner;
            }
            host.ownerToken = hostToken;
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.CORE_INITIALIZATION);
            if (!owner.initialize(hostToken))
                throw new IOException("Exclusive E3 core ownership could not be initialized");
            control.checkCancelled();

            control.checkpoint(E3DiagnosticRunCoordinator.Phase.GAME_LOAD);
            EmulatorSessionOwner.OpenResult opened = owner.open(hostToken,
                    E3DiagnosticFatRecord.ROM_SHA256, new EmulatorSessionOwner.GameLoader() {
                        @Override public String loadAndRestore() {
                            if (control.cancellationRequested()) return "E3 run cancelled before game load";
                            diagnosticHost.loadAttempted = true;
                            String issue = NativeDs.load(rom);
                            diagnosticHost.gameLoaded = issue == null;
                            if (control.cancellationRequested()) return "E3 run cancelled during game load";
                            return issue;
                        }

                        @Override public void discardFailedLoad() {
                            if (!diagnosticHost.loadAttempted) return;
                            NativeDs.unload();
                            diagnosticHost.gameLoaded = false;
                        }
                    });
            if (!opened.opened) throw new IOException("E3 emulator refused fixture: " + opened.error);
            control.checkCancelled();

            control.checkpoint(E3DiagnosticRunCoordinator.Phase.FRAMES);
            control.checkCancelled();
            if (!owner.resume(hostToken))
                throw new IOException("Exclusive E3 frame session could not be started");
            Thread frames = host.frameThread;
            if (frames == null) throw new IOException("E3 frame worker was not created");
            frames.join(15_000);
            if (frames.isAlive()) throw new IOException("E3 frame execution did not quiesce");
            if (control.cancellationRequested()) {
                failureMessage = "E3 lifecycle cancellation arrived before publication authorization";
            } else if (!host.framesCompleted) {
                throw new IOException("E3 frame execution stopped before the approved boundary");
            } else {
                control.checkpoint(E3DiagnosticRunCoordinator.Phase.COMMIT);
                if (requestedCommitFault != null) commitFaultArmed.set(true);
                boolean paused = owner.pause(hostToken);
                EmulatorSessionOwner.StopResult stop = owner.lastStopResult(hostToken);
                publication = host.transaction;
                if (stop == null || stop.outcome != VirtualSdGenerationStore.Outcome.COMMITTED) {
                    if (stop != null && stop.outcome == VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED)
                        releaseAdmission = false;
                    throw new IOException("E3 store result is " +
                            (stop == null ? "unavailable" : stop.outcome));
                }
                if (!paused)
                    failureMessage = "The generation committed, but the owner could not publish PAUSED";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failureMessage = "E3 worker was interrupted";
        } catch (IOException | RuntimeException failure) {
            if (failureMessage == null) failureMessage = failure.getClass().getSimpleName() +
                    ": " + String.valueOf(failure.getMessage());
        } finally {
            control.enterCleanup();
            control.report("E3 worker is retiring its native and storage owners");
            if (hostToken != null) {
                boolean retired = false;
                try { retired = owner.closeHost(hostToken); }
                catch (RuntimeException closeFailure) {
                    if (failureMessage == null) failureMessage = "Owner close failed: " + closeFailure.getMessage();
                }
                if (!retired) {
                    releaseAdmission = false;
                    if (failureMessage == null)
                        failureMessage = "Native owner or lifecycle receipt could not be retired safely";
                } else {
                    hostToken = null;
                }
            }
            if (releaseAdmission && working != null && !working.isTerminal()) {
                try { working.close(); }
                catch (IOException abortFailure) {
                    releaseAdmission = false;
                    if (failureMessage == null) failureMessage = "Working image quarantine failed: " +
                            abortFailure.getMessage();
                }
            }
            if (ownerAttachmentFailed) releaseAdmission = false;
        }

        publication = publication == null && host != null ? host.transaction : publication;
        VirtualSdGenerationStore.TransactionResult reported = publication;
        if (reported == null && baselinePublication != null) reported = baselinePublication;
        VirtualSdGenerationStore.Outcome outcome = authorityUncertain
                ? VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED : reported == null
                ? VirtualSdGenerationStore.Outcome.NO_PENDING : reported.outcome;
        if (reported != null && reported.failure != null) {
            String publicationDetail = "Store operation detail: " +
                    String.valueOf(reported.failure.getMessage());
            failureMessage = failureMessage == null ? publicationDetail :
                    failureMessage + "\n" + publicationDetail;
        }
        VirtualSdGenerationStore.TransactionIdentity transaction = reported == null ? null : reported.identity;
        VirtualSdGenerationStore.Generation generation = reported == null ? null : reported.generation;
        if (outcome == VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED ||
                (reported != null && !reported.cleanupResolved)) releaseAdmission = false;
        String message = formatResult(outcome, transaction, generation, oldHead,
                baselinePublication, failureMessage, releaseAdmission);
        return new E3DiagnosticRunCoordinator.RunResult(outcome, transaction, generation,
                message, releaseAdmission);
    }

    private String readDeviceFaultMode() {
        if (!BuildConfig.E3_DEVICE_FAULT_TEST ||
                !"dev.plump.emulator.e3fault".equals(getPackageName())) return MODE_NONE;
        String mode = getIntent() == null ? null : getIntent().getStringExtra(FAULT_EXTRA);
        if (MODE_DENY_LAUNCH.equals(mode) || MODE_DENY_WORKING.equals(mode) ||
                "BEFORE_HEAD_RENAME".equals(mode) || "AFTER_HEAD_RENAME".equals(mode) ||
                "DURING_HEAD_DIRECTORY_SYNC".equals(mode) ||
                "AFTER_HEAD_DIRECTORY_SYNC".equals(mode)) return mode;
        return MODE_NONE;
    }

    private static VirtualSdGenerationStore.FaultPoint commitFaultPoint(String mode) {
        switch (mode) {
            case "BEFORE_HEAD_RENAME": return VirtualSdGenerationStore.FaultPoint.BEFORE_HEAD_RENAME;
            case "AFTER_HEAD_RENAME": return VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME;
            case "DURING_HEAD_DIRECTORY_SYNC":
                return VirtualSdGenerationStore.FaultPoint.DURING_HEAD_DIRECTORY_SYNC;
            case "AFTER_HEAD_DIRECTORY_SYNC":
                return VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_DIRECTORY_SYNC;
            default: return null;
        }
    }

    private final class DiagnosticHost implements EmulatorSessionOwner.Host {
        final E3DiagnosticRunCoordinator.RunControl control;
        final VirtualSdGenerationStore store;
        final VirtualSdGenerationStore.WorkingSession working;
        final Path systemDirectory;
        final long previousCounter;
        volatile EmulatorSessionOwner.HostToken ownerToken;
        volatile Thread frameThread;
        volatile boolean coreInitializationAttempted;
        volatile boolean coreInitialized;
        volatile boolean loadAttempted;
        volatile boolean gameLoaded;
        volatile boolean framesCompleted;
        volatile VirtualSdGenerationStore.TransactionResult transaction;
        private EmulatorSessionOwner.StopResult stopResult;

        DiagnosticHost(E3DiagnosticRunCoordinator.RunControl control,
                       VirtualSdGenerationStore store,
                       VirtualSdGenerationStore.WorkingSession working,
                       Path systemDirectory, long previousCounter) {
            this.control = control;
            this.store = store;
            this.working = working;
            this.systemDirectory = systemDirectory;
            this.previousCounter = previousCounter;
        }

        @Override public boolean initializeCore() {
            if (control.cancellationRequested()) return false;
            coreInitializationAttempted = true;
            String issue = NativeDs.initializeE3(systemDirectory.toString(), working.saveDirectory.toString());
            coreInitialized = issue == null;
            if (issue != null) control.report("E3 core initialization refused: " + issue);
            return coreInitialized && !control.cancellationRequested();
        }

        @Override public boolean startLoops(long token) {
            if (control.cancellationRequested()) return false;
            frameThread = new Thread(() -> {
                int frames = 0;
                while (frames < 180 && owner.isCurrentRun(ownerToken, token) &&
                        !control.cancellationRequested() && !Thread.currentThread().isInterrupted()) {
                    NativeDs.frame();
                    frames++;
                }
                framesCompleted = frames == 180;
            }, "E3-native-frames-" + control.runId());
            frameThread.start();
            return true;
        }

        @Override public boolean stopAndSave() {
            return stopAndSaveResult().outcome == VirtualSdGenerationStore.Outcome.COMMITTED;
        }

        @Override public EmulatorSessionOwner.StopResult stopAndSaveResult() {
            if (stopResult != null) return stopResult;
            Thread frames = frameThread;
            if (frames != null && frames != Thread.currentThread() && frames.isAlive()) {
                frames.interrupt();
                try { frames.join(1800); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);
                }
                if (frames.isAlive())
                    return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);
            }
            if (control.cancellationRequested())
                return remember(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
            if (working == null || !coreInitialized || !gameLoaded || !framesCompleted)
                return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);

            try {
                long[] fields = NativeDs.closeE3Dldi();
                if (fields == null || fields.length != 22)
                    return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);
                VirtualSdGenerationStore.CoreCloseReceipt receipt = NativeDs.e3Receipt(fields);
                if (!receipt.closeReturned || !receipt.closeAttempted || !receipt.closeSucceeded)
                    return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);
                if (fields[7] <= 0 || fields[11] <= 0 || !receipt.permitsCommit(IMAGE_BYTES))
                    return remember(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);

                E3DiagnosticFatRecord.Record record = E3DiagnosticFatRecord.read(working.imageFile, true);
                if (record.previous != previousCounter || record.counter != previousCounter + 1)
                    return remember(VirtualSdGenerationStore.Outcome.NOT_COMMITTED, true);
                String candidateId = UUID.randomUUID().toString();
                transaction = store.commitTransaction(working, receipt, "none", candidateId, control);
                return remember(transaction.outcome, transaction.cleanupResolved);
            } catch (Exception failure) {
                control.report("E3 close or publication failed: " + String.valueOf(failure.getMessage()));
                return remember(VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED, false);
            }
        }

        @Override public void unloadGame() {
            if (loadAttempted) NativeDs.unload();
            gameLoaded = false;
        }

        @Override public void shutdownCore() {
            if (coreInitializationAttempted) NativeDs.shutdown();
            coreInitialized = false;
        }

        private EmulatorSessionOwner.StopResult remember(VirtualSdGenerationStore.Outcome outcome,
                                                          boolean quiesced) {
            stopResult = new EmulatorSessionOwner.StopResult(outcome, quiesced, true);
            return stopResult;
        }
    }

    private void requireTestIdentity() throws IOException {
        if (!BuildConfig.E3_TEST || !BuildConfig.E3_EXPECTED_PACKAGE.equals(getPackageName()))
            throw new IOException("E3 identity check failed");
    }

    private String formatResult(VirtualSdGenerationStore.Outcome outcome,
            VirtualSdGenerationStore.TransactionIdentity identity,
            VirtualSdGenerationStore.Generation generation,
            VirtualSdGenerationStore.Generation oldHead,
            VirtualSdGenerationStore.TransactionResult baseline,
            String failure, boolean releaseAdmission) {
        StringBuilder text = new StringBuilder("PLUMP E3C — ").append(outcome).append('\n');
        if (outcome == VirtualSdGenerationStore.Outcome.NO_PENDING)
            text.append("No emulator generation transaction was attempted; there is no commit outcome to report.\n");
        if (generation != null) {
            text.append("Verified generation: ").append(generation.sequence)
                    .append(" (candidate ").append(generation.id).append(")\n")
                    .append("Image SHA-256: ").append(generation.imageSha256).append('\n');
        } else if (oldHead != null) {
            text.append("Prior verified generation remains: ").append(oldHead.sequence)
                    .append(" (SHA-256 ").append(oldHead.imageSha256).append(")\n");
        }
        if (identity != null)
            text.append("Expected parent: ").append(identity.expectedHeadGenerationId)
                    .append(" at sequence ").append(identity.expectedHeadSequence).append('\n');
        if (baseline != null && baseline.outcome == VirtualSdGenerationStore.Outcome.COMMITTED &&
                identity == baseline.identity)
            text.append("Only the preformatted seed baseline was published; no emulator generation commit occurred.\n");
        if (failure != null) text.append(failure).append('\n');
        if (!releaseAdmission)
            text.append("Native or storage cleanup is unresolved. This process remains blocked; cold recovery is required.\n");
        text.append("Storage is private to ").append(getPackageName())
                .append(". Physical Android power-loss durability is unverified.");
        return text.toString();
    }

    private E3DiagnosticRunCoordinator.RunResult reconcilePendingOnce(
            E3DiagnosticRunCoordinator.RunControl control) {
        VirtualSdGenerationStore.Outcome outcome = VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED;
        VirtualSdGenerationStore.TransactionResult result = null;
        String message;
        boolean releaseAdmission = false;
        try {
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.PREFLIGHT);
            requireTestIdentity();
            Path privateRoot = getFilesDir().toPath().toRealPath();
            if (!privateRoot.startsWith(getDataDir().toPath().toRealPath()))
                throw new IOException("Experimental storage is not within this app's private data");
            CheckpointStore.Identity identity = new CheckpointStore.Identity(
                    "ds", E3DiagnosticFatRecord.ROM_SHA256, "melondsds-libretro",
                    CORE_REV, FRONTEND_REV, BuildConfig.E3_CORE_SHA256, "arm64-v8a", 1);
            Path root = privateRoot.resolve("e3-only")
                    .resolve("rom-" + E3DiagnosticFatRecord.ROM_SHA256).resolve("canonical");
            VirtualSdGenerationStore store = new VirtualSdGenerationStore(root, identity,
                    "canonical", IMAGE_BYTES, this::syncDirectory,
                    new VirtualSdFat16Validator(), point -> { });
            control.checkpoint(E3DiagnosticRunCoordinator.Phase.COMMIT);
            result = store.reconcilePendingStrict(control);
            outcome = result.outcome;
            releaseAdmission = outcome != VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED &&
                    result.cleanupResolved;
            if (result.identity == null && outcome == VirtualSdGenerationStore.Outcome.NO_PENDING) {
                message = "No pending E3 transaction was found; no metadata was changed.";
            } else if (outcome == VirtualSdGenerationStore.Outcome.COMMITTED) {
                message = "Cold strict reconciliation verified the exact candidate generation and parent.";
            } else if (outcome == VirtualSdGenerationStore.Outcome.NOT_COMMITTED) {
                message = "Cold strict reconciliation proved the old HEAD remained authoritative; the candidate was quarantined.";
            } else {
                message = "Strict reconciliation could not prove the exact old or candidate authority.";
            }
            if (result.failure != null) message += " " + result.failure.getMessage();
        } catch (IOException | RuntimeException failure) {
            outcome = VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED;
            message = "Strict reconciliation failed closed: " + failure.getClass().getSimpleName() +
                    ": " + String.valueOf(failure.getMessage());
        }
        if (!releaseAdmission)
            message += " The process remains blocked until cold recovery proves cleanup.";
        return new E3DiagnosticRunCoordinator.RunResult(outcome,
                result == null ? null : result.identity,
                result == null ? null : result.generation, message, releaseAdmission);
    }

    private long allocatableBytes(Path dir) throws IOException {
        StorageManager storage = getSystemService(StorageManager.class);
        if (storage == null) throw new IOException("Android storage allocation service unavailable");
        return storage.getAllocatableBytes(storage.getUuidForPath(dir.toFile()));
    }

    private byte[] verifiedFixture() throws IOException {
        byte[] bytes;
        try (InputStream input = getAssets().open("e3-dldi-alpha.nds")) {
            java.io.ByteArrayOutputStream bounded = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (bounded.size() + n > 512 * 1024)
                    throw new IOException("Oversized E3 diagnostic ROM asset");
                bounded.write(buffer, 0, n);
            }
            bytes = bounded.toByteArray();
        }
        if (bytes.length < 512 || bytes.length > 512 * 1024 ||
                !sha256(bytes).equals(E3DiagnosticFatRecord.ROM_SHA256))
            throw new IOException("Packaged CC0 DLDI fixture is missing or incompatible");
        return bytes;
    }

    private void verifyNativeLibrary(E3DiagnosticRunCoordinator.RunControl control) throws IOException {
        control.checkCancelled();
        File binary = new File(getApplicationInfo().nativeLibraryDir, "libmelondsds_libretro.so");
        if (!BuildConfig.E3_CORE_SHA256.matches("[0-9a-f]{64}") ||
                !Files.isRegularFile(binary.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !BuildConfig.E3_CORE_SHA256.equals(sha256(binary.toPath(), control)))
            throw new IOException("Experimental ARM64 core is missing or has wrong ELF SHA-256");
        control.checkCancelled();
    }

    private void createVerifiedSeed(Path target, E3DiagnosticRunCoordinator.RunControl control)
            throws IOException {
        control.checkCancelled();
        if (Files.isSymbolicLink(target)) throw new IOException("Symlinked E3 baseline");
        if (!BuildConfig.E3_BASELINE_SHA256.matches("[0-9a-f]{64}"))
            throw new IOException("E3 baseline identity was not pinned at build time");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            new VirtualSdFat16Validator().validate(target);
            control.checkCancelled();
            if (!BuildConfig.E3_BASELINE_SHA256.equals(sha256(target, control)))
                throw new IOException("Existing E3 baseline does not match this candidate APK");
            return;
        }
        control.checkCancelled();
        Path temporary = target.resolveSibling(".seed-" + UUID.randomUUID());
        boolean moved = false;
        try {
            try (InputStream input = new GZIPInputStream(getAssets().open("e3-fat16-baseline.e3seed"));
                 FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                byte[] buffer = new byte[65536];
                long bytes = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    control.checkCancelled();
                    bytes += n;
                    if (bytes > IMAGE_BYTES) throw new IOException("Unexpected E3 asset expansion");
                    output.write(buffer, 0, n);
                }
                if (bytes != IMAGE_BYTES) throw new IOException("Incomplete E3 FAT16 asset");
                control.checkCancelled();
                output.getFD().sync();
            }
            control.checkCancelled();
            new VirtualSdFat16Validator().validate(temporary);
            control.checkCancelled();
            if (!BuildConfig.E3_BASELINE_SHA256.equals(sha256(temporary, control)))
                throw new IOException("Unpacked E3 FAT16 baseline has wrong SHA-256");
            control.checkCancelled();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            syncDirectory(target.getParent());
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private void syncDirectory(Path directory) throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(directory.toString(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
        } catch (android.system.ErrnoException failure) {
            throw new IOException("E3 private-directory durability sync unavailable", failure);
        } finally {
            if (fd != null) try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
        }
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = new FileInputStream(path.toFile())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest())
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(Path path, E3DiagnosticRunCoordinator.RunControl control)
            throws IOException {
        try (InputStream input = new FileInputStream(path.toFile())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1;) {
                control.checkCancelled();
                digest.update(buffer, 0, n);
            }
            control.checkCancelled();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
