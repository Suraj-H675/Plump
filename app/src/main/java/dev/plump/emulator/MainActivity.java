package dev.plump.emulator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** P2-only single-screen native emulator host; library/visual polish belongs to later gates. */
public final class MainActivity extends Activity {
    private static final int PICK_ROM = 42;
    private static final int MAX_ROM = 32 * 1024 * 1024;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private TextView status;
    private Button pauseButton;
    private ControlsView controls;
    private Thread coreThread;
    private Thread audioThread;
    private GameSaveStore saveStore;
    private String gameId;
    private volatile long framesRun;
    private long lastVideoDiagnosticsMs;
    private boolean nativeReady;
    private boolean coreInitialized;
    private boolean gameLoaded;
    private boolean userPaused;
    private boolean activityResumed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 17, 20));
        root.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setText("Plump P2 — Select a local GBA test ROM");
        status.setPadding(dp(8), dp(10), dp(4), dp(10));
        bar.addView(status, new LinearLayout.LayoutParams(0, dp(62), 1f));
        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> openRom());
        bar.addView(open, new LinearLayout.LayoutParams(dp(80), dp(56)));
        Button ds = new Button(this);
        ds.setText("DS");
        ds.setOnClickListener(v -> startActivity(new Intent(this, DsActivity.class)));
        bar.addView(ds, new LinearLayout.LayoutParams(dp(52), dp(56)));
        pauseButton = new Button(this);
        pauseButton.setText("Pause");
        pauseButton.setOnClickListener(v -> togglePause());
        bar.addView(pauseButton, new LinearLayout.LayoutParams(dp(85), dp(56)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        SurfaceView screen = new SurfaceView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = View.MeasureSpec.getSize(widthSpec);
                setMeasuredDimension(width, width * 2 / 3); // Exact GBA 240x160 ratio.
            }
        };
        // SurfaceView's native surface is composited BEHIND the Activity window.
        // A black View background paints over the transparent window hole and
        // hides genuine frames even when ANativeWindow_unlockAndPost succeeds.
        // Keep this View free of a background; the parent already supplies the
        // initial dark color before the first emulated frame arrives.
        root.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        controls = new ControlsView(this);
        root.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (NativeCore.LOAD_ERROR != null) {
            message("Native core missing: " + NativeCore.LOAD_ERROR);
            return;
        }
        String error = NativeCore.initialize();
        if (error != null) { message("Cannot initialize mGBA: " + error); return; }
        nativeReady = true;
        coreInitialized = true;
        screen.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { NativeCore.setSurface(holder.getSurface()); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                NativeCore.setSurface(holder.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { NativeCore.setSurface(null); }
        });
        try {
            saveStore = new GameSaveStore(new File(getFilesDir(), "native-saves"), directory -> {
                FileDescriptor fd = null;
                try {
                    fd = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                    Os.fsync(fd);
                } catch (android.system.ErrnoException exception) {
                    throw new IOException("Save directory sync failed", exception);
                } finally {
                    if (fd != null) {
                        try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
                    }
                }
            });
        } catch (IOException exception) {
            message("Native-save storage unavailable: " + exception.getMessage());
            nativeReady = false;
        }
    }

    private int dp(int pixels) { return (int) (pixels * getResources().getDisplayMetrics().density + .5f); }
    private void message(String detail) { status.setText(detail); }
    private void postError(String detail) { runOnUiThread(() -> message(detail)); }

    private void openRom() {
        if (!nativeReady) { message("Core or save storage not available"); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, PICK_ROM);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROM || result != RESULT_OK || data == null || data.getData() == null) return;
        if (!nativeReady) return;
        byte[] rom;
        try {
            rom = readLocalRom(data.getData());
        } catch (IOException exception) {
            message("Could not read ROM: " + exception.getMessage());
            return;
        }
        if (!stopLoopsAndSave()) { message("Previous core did not stop; ROM switch rejected"); return; }
        if (gameLoaded) NativeCore.unloadGame();
        gameLoaded = false;
        gameId = null;
        framesRun = 0;
        String error = NativeCore.loadGame(rom);
        if (error != null) { message("ROM rejected: " + error); return; }
        String identity = contentSha256(rom);
        GameSaveStore.Recovery recovered = saveStore.load(identity);
        if (recovered.bytes == null && recovered.warning != null) {
            NativeCore.unloadGame();
            message("Existing native save corrupt. Preserved for recovery; game not started.");
            return;
        }
        if (recovered.bytes != null) {
            String restoreError = NativeCore.restoreSaveRam(recovered.bytes);
            if (restoreError != null) {
                NativeCore.unloadGame();
                message("Native save preserved; restore rejected: " + restoreError);
                return;
            }
            // A successful JNI call alone does not establish that SRAM was
            // actually restored into the running core. Verify the complete
            // saved region before executing any new frames. Never discard or
            // overwrite an existing save if verification fails.
            byte[] readback = NativeCore.snapshotSaveRam();
            if (readback == null || readback.length < recovered.bytes.length ||
                    !MessageDigest.isEqual(recovered.bytes,
                            Arrays.copyOf(readback, recovered.bytes.length))) {
                NativeCore.unloadGame();
                message("Native save preserved; SRAM restore readback mismatch.");
                return;
            }
            android.util.Log.i("Plump", "Native SRAM restore readback VERIFIED: generation="
                    + recovered.generation + " bytes=" + recovered.bytes.length);
        }
        gameId = identity;
        gameLoaded = true;
        userPaused = false;
        pauseButton.setText("Pause");
        message(recovered.warning == null ?
                (recovered.bytes == null ? "GBA ready — game loaded" :
                        "GBA ready — cartridge SRAM restored and verified") : recovered.warning);
        if (activityResumed) startLoops();
    }

    private byte[] readLocalRom(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Storage provider returned no stream");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0, n;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > MAX_ROM) throw new IOException("ROM larger than 32 MiB");
                bytes.write(buffer, 0, n);
            }
            if (total < 192) throw new IOException("File is not a valid GBA cartridge image");
            return bytes.toByteArray();
        }
    }

    private static String contentSha256(byte[] rom) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(rom);
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void togglePause() {
        if (!gameLoaded) { message("Choose a GBA ROM first"); return; }
        if (!userPaused) {
            userPaused = true;
            if (stopLoopsAndSave()) message("Paused — cartridge save flushed");
            pauseButton.setText("Resume");
        } else {
            userPaused = false;
            pauseButton.setText("Pause");
            if (activityResumed) startLoops();
        }
    }

    private void startLoops() {
        if (!nativeReady || !gameLoaded || userPaused || !activityResumed || !running.compareAndSet(false, true)) return;
        coreThread = new Thread(() -> {
            long period = (long) (1_000_000_000.0 / NativeCore.frameRate());
            long next = System.nanoTime();
            long lastSave = SystemClock.elapsedRealtime();
            while (running.get()) {
                NativeCore.runFrame();
                framesRun++;
                long now = SystemClock.elapsedRealtime();
                if (now - lastVideoDiagnosticsMs >= 3000) {
                    lastVideoDiagnosticsMs = now;
                    String diagnostics = NativeCore.videoDiagnostics();
                    android.util.Log.i("Plump", "GBA video " + diagnostics);
                    runOnUiThread(() -> {
                        if (gameLoaded && !userPaused) message("GBA " + diagnostics);
                    });
                }
                if (now - lastSave >= 30000) {
                    flushNativeSave(false);
                    lastSave = now;
                }
                next += period;
                long nanos = next - System.nanoTime();
                if (nanos > 0) LockSupport.parkNanos(nanos);
                else if (nanos < -3 * period) next = System.nanoTime();
            }
        }, "Plump-emulation");
        coreThread.start();
        audioThread = new Thread(this::pumpAudio, "Plump-audio");
        audioThread.start();
    }

    private void pumpAudio() {
        AudioTrack track = null;
        try {
            int rate = NativeCore.audioRate();
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Unsupported 32 kHz audio route");
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    Math.max(min, 16384), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("AudioTrack did not initialize");
            track.play();
            short[] chunk = new short[2048];
            while (running.get()) {
                int samples = NativeCore.drainAudio(chunk);
                if (samples > 0) {
                    // WRITE_NON_BLOCKING prevents the UI pause/background path
                    // from waiting indefinitely for an audio device to drain.
                    // Partial writes must not silently discard the remaining PCM.
                    int offset = 0;
                    while (running.get() && offset < samples) {
                        int written = track.write(chunk, offset, samples - offset,
                                AudioTrack.WRITE_NON_BLOCKING);
                        if (written < 0) throw new IllegalStateException("AudioTrack write error " + written);
                        if (written == 0) SystemClock.sleep(3);
                        else offset += written;
                    }
                } else {
                    SystemClock.sleep(5);
                }
            }
        } catch (Exception exception) {
            postError("Audio output unavailable: " + exception.getMessage());
            running.set(false);
        } finally {
            if (track != null) {
                try { track.pause(); track.flush(); track.release(); } catch (IllegalStateException ignored) { }
            }
        }
    }

    private boolean stopLoopsAndSave() {
        running.set(false);
        if (controls != null && nativeReady) controls.releaseAll();
        if (nativeReady) NativeCore.setButtons(0);
        if (!joinThread(coreThread) || !joinThread(audioThread)) {
            message("Core or audio still stopping — no save was attempted");
            return false;
        }
        coreThread = audioThread = null;
        if (coreInitialized) NativeCore.discardAudio();
        return flushNativeSave(true);
    }

    private boolean joinThread(Thread thread) {
        if (thread == null) return true;
        thread.interrupt();
        try { thread.join(1800); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !thread.isAlive();
    }

    private boolean flushNativeSave(boolean reportFailure) {
        if (!gameLoaded || framesRun == 0 || saveStore == null || gameId == null) return true;
        try {
            byte[] data = NativeCore.snapshotSaveRam();
            if (data == null) throw new IOException("No native SRAM available");
            saveStore.write(gameId, data);
            return true;
        } catch (IOException exception) {
            if (reportFailure) message("Native save NOT written: " + exception.getMessage());
            else postError("Periodic native save failed: " + exception.getMessage());
            return false;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (gameLoaded && !userPaused) startLoops();
    }

    @Override protected void onPause() {
        activityResumed = false;
        stopLoopsAndSave();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (coreInitialized && stopLoopsAndSave()) {
            NativeCore.unloadGame();
            NativeCore.shutdown();
            coreInitialized = false;
        }
        super.onDestroy();
    }
}
