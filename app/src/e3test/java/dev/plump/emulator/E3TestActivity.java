package dev.plump.emulator;

import android.app.Activity;
import android.os.Bundle;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * E3-only intentionally plain diagnostic runner. No public ROM picker,
 * state loader, production storage import, or branch promotion.
 *
 * An explicit tap creates a private 128 MiB FAT image (once), copies the
 * latest verified generation to a writable E2 session, steps the real core,
 * synchronously quiesces/health-closes it through EmulatorSessionOwner and
 * publishes only after both FAT validation and game-level record readback.
 */
public final class E3TestActivity extends Activity {
    private static final long IMAGE_BYTES = VirtualSdFat16Validator.IMAGE_BYTES;
    private static final long PREFLIGHT_BYTES = 768L * 1024 * 1024;
    private static final String CORE_REV = "16f127dbb587a73371827ce79689c5d237c4b59b";
    private static final String FRONTEND_REV = "f394adbacb5722ee97c1b37c8064da9a25818310";
    private final AtomicBoolean busy = new AtomicBoolean();
    private final EmulatorSessionOwner owner = EmulatorSessionOwner.process();
    private volatile boolean coreInitialized, gameLoaded, framesCompleted, committed;
    private volatile Thread frameThread;
    private EmulatorSessionOwner.HostToken hostToken;
    private VirtualSdGenerationStore store;
    private VirtualSdGenerationStore.WorkingSession working;
    private VirtualSdGenerationStore.Generation result;
    private long previousCounter;
    private TextView status;
    private Button run;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BuildConfig.E3_TEST || !"dev.plump.emulator.e3test".equals(getPackageName())) {
            finish();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 38, 28, 28);
        status = new TextView(this);
        status.setText("PLUMP E3A — ISOLATED EXPERIMENT\n" +
                "Only the embedded CC0 DLDI counter fixture is permitted.\n" +
                "Each run attempts one genuine write. Unverified generations stay quarantined.\n" +
                "This build does NOT use or import ordinary Plump data.\n\n" +
                "Tap Run only during the separately authorized device test.");
        run = new Button(this);
        run.setText("Run isolated diagnostic once");
        run.setOnClickListener(view -> {
            if (!busy.compareAndSet(false, true)) return;
            run.setEnabled(false);
            new Thread(this::runDiagnosticOnce, "E3-private-dldi-diagnostic").start();
        });
        layout.addView(status);
        layout.addView(run);
        setContentView(layout);
    }

    private void show(String message) {
        runOnUiThread(() -> { if (!isFinishing()) status.setText(message); });
    }

    private void runDiagnosticOnce() {
        String outcome;
        try {
            if (!BuildConfig.E3_TEST || !"dev.plump.emulator.e3test".equals(getPackageName()))
                throw new IOException("E3 identity check failed");
            byte[] rom = verifiedFixture();
            verifyNativeLibrary();
            Path privateRoot = getFilesDir().toPath().toRealPath();
            if (!privateRoot.startsWith(getDataDir().toPath().toRealPath()))
                throw new IOException("Experimental storage is not within this app's private data");
            preflight(privateRoot);

            Path base = privateRoot.resolve("e3-only");
            Files.createDirectories(base);
            Path seed = base.resolve("baseline-fat16.bin");
            createVerifiedSeed(seed);
            CheckpointStore.Identity identity = new CheckpointStore.Identity(
                    "ds", E3DiagnosticFatRecord.ROM_SHA256, "melondsds-libretro",
                    CORE_REV, FRONTEND_REV, BuildConfig.E3_CORE_SHA256, "arm64-v8a", 1);
            store = new VirtualSdGenerationStore(
                    base.resolve("rom-" + E3DiagnosticFatRecord.ROM_SHA256).resolve("canonical"),
                    identity, "canonical", IMAGE_BYTES,
                    this::syncDirectory, new VirtualSdFat16Validator(), point -> { });
            VirtualSdGenerationStore.Generation head = store.current();
            if (head == null) head = store.initialize(seed);
            if (head.sequence >= 4)
                throw new IOException("E3 diagnostic has reached the three-write test limit; no automatic cleanup");
            E3DiagnosticFatRecord.Record prior = E3DiagnosticFatRecord.read(head.imageFile, head.sequence > 1);
            previousCounter = prior.counter;
            working = store.openSession();
            framesCompleted = committed = coreInitialized = gameLoaded = false;
            result = null;

            final Path system = base.resolve("system");
            Files.createDirectories(system);
            hostToken = owner.attach(EmulatorSessionOwner.Console.DS, new EmulatorSessionOwner.Host() {
                @Override public boolean initializeCore() {
                    String error = NativeDs.initializeE3(system.toString(),
                            working.saveDirectory.toString());
                    coreInitialized = error == null;
                    if (error != null) show("E3 core initialization refused: " + error);
                    return coreInitialized;
                }
                @Override public boolean startLoops(long token) {
                    frameThread = new Thread(() -> {
                        int frames = 0;
                        while (frames < 180 && owner.isCurrentRun(hostToken, token) &&
                                !Thread.currentThread().isInterrupted()) {
                            NativeDs.frame();
                            frames++;
                        }
                        framesCompleted = frames == 180;
                    }, "E3-native-frames");
                    frameThread.start();
                    return true;
                }
                @Override public boolean stopAndSave() { return closeAndCommit(); }
                @Override public void unloadGame() {
                    if (gameLoaded) NativeDs.unload();
                    gameLoaded = false;
                }
                @Override public void shutdownCore() {
                    if (coreInitialized) NativeDs.shutdown();
                    coreInitialized = false;
                }
            }, new SessionReceiptStore(base.resolve("e3-session-receipts")));
            if (!owner.initialize(hostToken))
                throw new IOException("Exclusive E3 core ownership could not be initialized");
            EmulatorSessionOwner.OpenResult opened = owner.open(
                    hostToken, E3DiagnosticFatRecord.ROM_SHA256, new EmulatorSessionOwner.GameLoader() {
                        @Override public String loadAndRestore() {
                            String issue = NativeDs.load(rom);
                            gameLoaded = issue == null;
                            return issue;
                        }
                        @Override public void discardFailedLoad() {
                            if (gameLoaded) NativeDs.unload();
                            gameLoaded = false;
                        }
                    });
            if (!opened.opened) throw new IOException("E3 emulator refused fixture: " + opened.error);
            if (!owner.resume(hostToken))
                throw new IOException("Exclusive E3 frame session could not be started");
            Thread frames = frameThread;
            if (frames == null) throw new IOException("E3 frame worker was not created");
            try { frames.join(15_000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("E3 worker interrupted", interrupted);
            }
            if (frames.isAlive() || !framesCompleted)
                throw new IOException("E3 frame execution did not quiesce at the approved boundary");
            if (!owner.pause(hostToken) || !committed || result == null)
                throw new IOException("E3 native FAT close or immutable generation commit failed");
            if (!owner.closeHost(hostToken))
                throw new IOException("E3 owner cannot retire completed native core");
            hostToken = null;
            outcome = "PLUMP E3A — VERIFIED TEST WRITE\n" +
                    "Actual ARM9 DLDI counter: " + previousCounter + " → " + (previousCounter + 1) +
                    "\nCommitted immutable generation: " + result.sequence +
                    "\nImage SHA-256: " + result.imageSha256 +
                    "\nReal native close/health: verified\n" +
                    "All storage is private to dev.plump.emulator.e3test.\n" +
                    "This does not establish physical Android power-loss durability.";
        } catch (Exception failure) {
            outcome = "PLUMP E3A — NOT COMMITTED\n" +
                    failure.getClass().getSimpleName() + ": " + failure.getMessage() +
                    "\nExisting verified generations remain intact.\n" +
                    "Do not claim recovery without a new independent check.";
        } finally {
            boolean quiesced = true;
            Thread steps = frameThread;
            if (steps != null && steps.isAlive()) {
                steps.interrupt();
                try { steps.join(2200); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    quiesced = false;
                }
                if (steps.isAlive()) quiesced = false;
            }
            if (quiesced && working != null && !committed) {
                // After any abnormal session, quiesce/retire first. The E2
                // working session is quarantined; never automatically promote.
                if (coreInitialized) {
                    try { NativeDs.shutdown(); } catch (RuntimeException ignored) { }
                    coreInitialized = false;
                }
                try { working.close(); } catch (IOException ignored) { }
            }
            if (quiesced && hostToken != null) {
                try { owner.closeHost(hostToken); } catch (RuntimeException ignored) { }
                hostToken = null;
            }
            if (quiesced) {
                working = null;
                busy.set(false);
                runOnUiThread(() -> { if (!isFinishing()) run.setEnabled(true); });
            } else {
                // No unsafe native shutdown or working-store unlock while a
                // frame may still access FAT. Stay blocked until process death.
                outcome = "PLUMP E3A — UNQUIESCED CORE; NO COMMIT\n" +
                        "Native frame worker did not stop; full app-process restart required.\n" +
                        "Existing immutable generations remain untouched.";
            }
        }
        show(outcome);
    }

    /** Called only by the owner's exclusive paused-session transition. */
    private boolean closeAndCommit() {
        if (committed) return true; // closeHost may retry a completed pause.
        Thread steps = frameThread;
        if (steps != null && steps != Thread.currentThread() && steps.isAlive()) {
            // The owner transitions RUNNING -> QUIESCING before this callback.
            // Interrupt, join, and reject early incomplete runs; native DLDI
            // closure is forbidden while any frame worker is still active.
            steps.interrupt();
            try { steps.join(1800); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (steps.isAlive()) return false;
        }
        if (working == null || !coreInitialized || !gameLoaded || !framesCompleted ||
                steps == null || steps.isAlive()) return false;
        try {
            long[] status = NativeDs.closeE3Dldi();
            if (status == null || status.length != 22 || status[7] <= 0 || status[11] <= 0)
                throw new IOException("No verified genuine DLDI sectors written");
            VirtualSdGenerationStore.CoreCloseReceipt receipt = NativeDs.e3Receipt(status);
            if (!receipt.permitsCommit(IMAGE_BYTES))
                throw new IOException("Native E2 DLDI close receipt failed strict checks");
            E3DiagnosticFatRecord.Record record = E3DiagnosticFatRecord.read(working.imageFile, true);
            if (record.previous != previousCounter || record.counter != previousCounter + 1)
                throw new IOException("In-game FAT record did not advance coherently");
            result = store.commit(working, receipt);
            committed = true;
            return true;
        } catch (Exception failure) {
            show("E3 FAIL CLOSED — no generation published: " + failure.getMessage());
            return false;
        }
    }

    private void preflight(Path dir) throws IOException {
        long free = Files.getFileStore(dir).getUsableSpace();
        if (free < PREFLIGHT_BYTES)
            throw new IOException("Insufficient app-private space for 128 MiB snapshots plus rollback margin");
        StorageManager storage = getSystemService(StorageManager.class);
        if (storage == null || storage.getAllocatableBytes(storage.getUuidForPath(dir.toFile())) <
                PREFLIGHT_BYTES)
            throw new IOException("Android storage allocation preflight rejected E3 snapshots");
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

    private void verifyNativeLibrary() throws IOException {
        File binary = new File(getApplicationInfo().nativeLibraryDir, "libmelondsds_libretro.so");
        if (!BuildConfig.E3_CORE_SHA256.matches("[0-9a-f]{64}") ||
                !Files.isRegularFile(binary.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                !BuildConfig.E3_CORE_SHA256.equals(sha256(Files.readAllBytes(binary.toPath()))))
            throw new IOException("Experimental ARM64 core is missing or has wrong ELF SHA-256");
    }

    private void createVerifiedSeed(Path target) throws IOException {
        // A fresh, host-preformatted image is packaged as a compressed asset
        // only in this test variant. Recovery never formats or repairs images.
        if (Files.isSymbolicLink(target)) throw new IOException("Symlinked E3 baseline");
        if (!BuildConfig.E3_BASELINE_SHA256.matches("[0-9a-f]{64}"))
            throw new IOException("E3 baseline identity was not pinned at build time");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            new VirtualSdFat16Validator().validate(target);
            if (!BuildConfig.E3_BASELINE_SHA256.equals(sha256(target)))
                throw new IOException("Existing E3 baseline does not match this candidate APK");
            return;
        }
        Path temporary = target.resolveSibling(".seed-" + java.util.UUID.randomUUID());
        try {
            try (InputStream input = new GZIPInputStream(getAssets().open("e3-fat16-baseline.e3seed"));
                 FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                byte[] buffer = new byte[65536];
                long bytes = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    bytes += n;
                    if (bytes > IMAGE_BYTES) throw new IOException("Unexpected E3 asset expansion");
                    output.write(buffer, 0, n);
                }
                if (bytes != IMAGE_BYTES) throw new IOException("Incomplete E3 FAT16 asset");
                output.getFD().sync();
            }
            new VirtualSdFat16Validator().validate(temporary);
            if (!BuildConfig.E3_BASELINE_SHA256.equals(sha256(temporary)))
                throw new IOException("Unpacked E3 FAT16 baseline has wrong SHA-256");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(target.getParent());
        } catch (IOException failure) {
            // Preserve incomplete source for forensic inspection; never treat
            // it as a committed baseline or overwrite an existing seed.
            throw failure;
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

    @Override protected void onPause() {
        if (hostToken != null && owner.hasLoadedGame(hostToken)) {
            if (!owner.pause(hostToken))
                show("E3 interrupted — no verified generation committed; cold recovery required");
        }
        super.onPause();
    }
}
