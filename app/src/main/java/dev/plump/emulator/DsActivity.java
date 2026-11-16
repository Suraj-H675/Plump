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
import android.view.MotionEvent;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** P3 technical slice: two real DS screens; isolated from the accepted GBA host. */
public final class DsActivity extends Activity {
    private static final int REQUEST_ROM = 63;
    private static final int MAX_ROM = 64 * 1024 * 1024;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger audioUnderruns = new AtomicInteger();
    private final EmulatorSessionOwner sessionOwner = EmulatorSessionOwner.process();
    private EmulatorSessionOwner.HostToken hostToken;
    private Thread emulatorThread, audioThread;
    private DsControlsView left, right;
    private TouchPanel bottom;
    private SurfaceView topDisplay;
    private LinearLayout screenRow, extraControls, portraitRoot;
    private Button pauseButton, expandButton;
    private TextView status;
    private GameSaveStore saveStore;
    private GameSaveStore activeSaveStore;
    private CheckpointStore checkpointStore;
    private HistoricalBranchStore branchStore;
    private ActiveTimelineStore timelineStore;
    private HistoricalBranchStore.Branch activeBranch;
    private CheckpointStore.Identity activeIdentity;
    private byte[] activeRom;
    private File systemDirectory, canonicalCoreDirectory;
    private String gameId;
    private long frames, lastStats;
    private boolean coreReady, loaded, nativeGameLoaded, userPaused, foreground, enlarged;
    private volatile long runToken;
    private int measuredContentWidth, measuredContentHeight;

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private void message(String message) { status.setText(message); }
    private void error(String message, long callbackRun) {
        runOnUiThread(() -> {
            if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && runToken == callbackRun)
                message(message);
        });
    }

    private final class TouchPanel extends SurfaceView {
        private int pointerId = -1;
        TouchPanel() {
            super(DsActivity.this);
            setContentDescription("Nintendo DS lower touchscreen, tap or drag directly");
            // SurfaceView must not have an opaque background (P2 device rendering defect).
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, width * 192 / 256);
        }

        void release() {
            pointerId = -1;
            if (coreReady && hostToken != null && sessionOwner.isCurrentHost(hostToken))
                NativeDs.touch(false, 0, 0);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) { release(); return true; }
            if (action == MotionEvent.ACTION_DOWN) pointerId = event.getPointerId(event.getActionIndex());
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                if (pointerId == event.getPointerId(event.getActionIndex())) {
                    release();
                    performClick();
                    return true;
                }
            }
            int index = event.findPointerIndex(pointerId);
            if (index < 0) return true;
            float x = event.getX(index), y = event.getY(index);
            boolean inside = DsTouchMapper.inside(x, y, getWidth(), getHeight());
            if (coreReady && hostToken != null && sessionOwner.isCurrentHost(hostToken) &&
                    (inside ? sessionOwner.isCurrentRun(hostToken, runToken) : true)) NativeDs.touch(inside,
                    DsTouchMapper.x(x, getWidth()), DsTouchMapper.y(y, getHeight()));
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        portraitRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 19, 23));
        root.setFitsSystemWindows(true);
        root.setMotionEventSplittingEnabled(true);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setText("DS — reselect a ROM after restart; native cartridge save is the fallback, no frame resume");
        status.setPadding(dp(8), 0, dp(4), 0);
        toolbar.addView(status, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> openRom());
        toolbar.addView(open, new LinearLayout.LayoutParams(dp(78), dp(56)));
        pauseButton = new Button(this);
        pauseButton.setText("Pause");
        pauseButton.setOnClickListener(v -> togglePause());
        toolbar.addView(pauseButton, new LinearLayout.LayoutParams(dp(79), dp(56)));
        root.addView(toolbar);

        SurfaceView top = new SurfaceView(this) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                setMeasuredDimension(width, width * 192 / 256);
            }
        };
        topDisplay = top;
        top.setContentDescription("Nintendo DS upper 256 by 192 display");
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        top.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.topSurface(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.topSurface(holder.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.topSurface(null);
            }
        });

        expandButton = new Button(this);
        expandButton.setText("Enlarge lower screen");
        expandButton.setOnClickListener(v -> changeLowerSize());
        root.addView(expandButton, new LinearLayout.LayoutParams(-1, dp(43)));

        screenRow = new LinearLayout(this);
        screenRow.setOrientation(LinearLayout.HORIZONTAL);
        screenRow.setMotionEventSplittingEnabled(true);
        screenRow.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom = new TouchPanel();
        left = new DsControlsView(this, true, this::sendButtons);
        right = new DsControlsView(this, false, this::sendButtons);
        screenRow.addView(left);
        screenRow.addView(bottom);
        screenRow.addView(right);
        root.addView(screenRow);
        extraControls = new LinearLayout(this);
        extraControls.setOrientation(LinearLayout.HORIZONTAL);
        extraControls.setMotionEventSplittingEnabled(true);
        extraControls.setVisibility(View.GONE);
        root.addView(extraControls);
        setContentView(root);

        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int width = r - l, height = b - t;
            if (width > 0 && height > 0 &&
                    (width != measuredContentWidth || height != measuredContentHeight)) {
                updateLowerLayout(width, height);
            }
        });
        root.post(() -> updateLowerLayout(root.getWidth(), root.getHeight()));
        bottom.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.bottomSurface(holder.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.bottomSurface(holder.getSurface());
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                bottom.release();
                if (hostToken != null && sessionOwner.isCurrentHost(hostToken) && coreReady)
                    NativeDs.bottomSurface(null);
            }
        });

        if (NativeDs.LOAD_ERROR != null) {
            message("DS core library unavailable: " + NativeDs.LOAD_ERROR);
            return;
        }
        try {
            File sys = new File(getFilesDir(), "nds-system");
            File coreSaves = new File(getFilesDir(), "nds-core-data");
            systemDirectory = sys;
            canonicalCoreDirectory = coreSaves;
            if ((!sys.isDirectory() && !sys.mkdirs()) ||
                (!coreSaves.isDirectory() && !coreSaves.mkdirs()))
                throw new IOException("Cannot create private core directories");
            saveStore = new GameSaveStore(new File(getFilesDir(), "nds-native-saves"), directory -> {
                FileDescriptor fd = null;
                try {
                    fd = Os.open(directory.getAbsolutePath(), android.system.OsConstants.O_RDONLY, 0);
                    Os.fsync(fd);
                } catch (android.system.ErrnoException failure) {
                    throw new IOException("Directory durability sync failed", failure);
                } finally {
                    // Os.open's FileDescriptor is closed using the same API that opened it.
                    if (fd != null) try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
                }
            });
            checkpointStore = new CheckpointStore(new File(getFilesDir(), "checkpoints").toPath(), directory -> {
                FileDescriptor fd = null;
                try {
                    fd = Os.open(directory.toAbsolutePath().toString(), OsConstants.O_RDONLY, 0);
                    Os.fsync(fd);
                } catch (android.system.ErrnoException failure) {
                    throw new IOException("Checkpoint directory sync failed", failure);
                } finally {
                    if (fd != null) try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
                }
            }, path -> new android.os.StatFs(path.toString()).getAvailableBytes(), point -> { });
            branchStore = new HistoricalBranchStore(new File(getFilesDir(), "historical-branches").toPath(),
                    directory -> syncPath(directory),
                    path -> new android.os.StatFs(path.toString()).getAvailableBytes(),
                    directory -> syncSaveDirectory(directory), point -> { });
            timelineStore = new ActiveTimelineStore(new File(getFilesDir(), "active-timelines").toPath(),
                    directory -> syncPath(directory));
            hostToken = sessionOwner.attach(EmulatorSessionOwner.Console.DS, new EmulatorSessionOwner.Host() {
                @Override public boolean initializeCore() {
                    if (NativeDs.LOAD_ERROR != null) {
                        message("DS core library unavailable: " + NativeDs.LOAD_ERROR);
                        return false;
                    }
                    String issue = NativeDs.initialize(sys.getAbsolutePath(), coreSaves.getAbsolutePath());
                    if (issue != null) { message("Cannot initialize melonDS: " + issue); return false; }
                    coreReady = true;
                    return true;
                }
                @Override public boolean stopAndSave() { return DsActivity.this.stopAndSave(); }
                @Override public boolean startLoops(long token) { return startOwnedLoops(token); }
                @Override public CheckpointStore checkpointStore() { return checkpointStore; }
                @Override public HistoricalBranchStore historicalBranchStore() { return branchStore; }
                @Override public ActiveTimelineStore activeTimelineStore() { return timelineStore; }
                @Override public EmulatorSessionOwner.RestoreDisposition restoreHistoricalBranch(
                        HistoricalBranchStore.Branch branch) { return restoreBranch(branch); }
                @Override public CheckpointStore.Identity checkpointIdentity() {
                    if (gameId == null) return null;
                    try {
                        return PinnedCheckpointIdentity.ds(DsActivity.this, gameId,
                                NativeDs.serializerApiVersion());
                    } catch (IOException failure) { throw new IllegalStateException(failure); }
                }
                @Override public CheckpointStore.Capture captureCheckpoint() {
                    int capabilities = NativeDs.serializerCapabilities();
                    int apiVersion = NativeDs.serializerApiVersion();
                    if (capabilities != PinnedCheckpointIdentity.REQUIRED_CAPABILITIES || apiVersion != 1)
                        return CheckpointStore.Capture.unsupported(apiVersion,
                                "Pinned melonDS serializer capability is unsupported");
                    byte[][] captured;
                    try { captured = NativeDs.captureCheckpoint(); }
                    catch (OutOfMemoryError exhausted) {
                        return CheckpointStore.Capture.unsupported(apiVersion,
                                "Not enough memory for a bounded melonDS checkpoint capture");
                    }
                    if (captured == null || captured.length != 2 || captured[0] == null || captured[1] == null)
                        return CheckpointStore.Capture.unsupported(apiVersion,
                                "Pinned melonDS state or cartridge EEPROM capture failed validation");
                    return CheckpointStore.Capture.supported(apiVersion, captured[0], captured[1]);
                }
                @Override public void checkpointFailed(String detail) {
                    android.util.Log.w("Plump", "DS automatic checkpoint unavailable: " + detail);
                }
                @Override public void unloadGame() {
                    if (nativeGameLoaded) NativeDs.unload();
                    nativeGameLoaded = loaded = false;
                    gameId = null;
                    activeRom = null;
                    activeBranch = null;
                    activeIdentity = null;
                    activeSaveStore = saveStore;
                    frames = 0;
                }
                @Override public void shutdownCore() {
                    if (coreReady) NativeDs.shutdown();
                    coreReady = false;
                }
            }, new SessionReceiptStore(new File(getFilesDir(), "session-receipts").toPath()));
            if (!sessionOwner.initialize(hostToken)) {
                message("DS core initialization failed or a previous host is unresolved");
                return;
            }
            if (top.getHolder().getSurface().isValid()) NativeDs.topSurface(top.getHolder().getSurface());
            if (bottom.getHolder().getSurface().isValid()) NativeDs.bottomSurface(bottom.getHolder().getSurface());
            message("Select a DS ROM. After Activity or process recreation, native cartridge save is the fallback; exact-frame resume is unavailable.");
        } catch (IllegalStateException unavailable) {
            message("DS session still needs recovery; this Activity cannot reattach safely");
        } catch (IOException failure) {
            message("DS private storage unavailable: " + failure.getMessage());
        }
    }

    private void updateLowerLayout(int width, int height) {
        if (width <= 0 || height <= 0) return;
        measuredContentWidth = width;
        measuredContentHeight = height;
        DsPortraitSizing.Panels panels = DsPortraitSizing.calculate(
                width, height, dp(56), dp(43), dp(130), enlarged);
        bottom.release();
        left.releaseAll();
        right.releaseAll();
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
                panels.topWidth, panels.topHeight());
        topParams.gravity = Gravity.CENTER_HORIZONTAL;
        topDisplay.setLayoutParams(topParams);
        if (enlarged) {
            if (left.getParent() == screenRow) screenRow.removeView(left);
            if (right.getParent() == screenRow) screenRow.removeView(right);
            if (left.getParent() != extraControls) {
                extraControls.addView(left, new LinearLayout.LayoutParams(0, panels.controlsHeight, 1f));
                extraControls.addView(right, new LinearLayout.LayoutParams(0, panels.controlsHeight, 1f));
            }
            extraControls.setVisibility(View.VISIBLE);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, panels.controlsHeight, 1f));
            right.setLayoutParams(new LinearLayout.LayoutParams(0, panels.controlsHeight, 1f));
            bottom.setLayoutParams(new LinearLayout.LayoutParams(
                    panels.lowerWidth, panels.lowerHeight()));
        } else {
            if (left.getParent() == extraControls) extraControls.removeView(left);
            if (right.getParent() == extraControls) extraControls.removeView(right);
            if (left.getParent() != screenRow) {
                screenRow.addView(left, 0);
                screenRow.addView(right);
            }
            extraControls.setVisibility(View.GONE);
            int sideWidth = (width - panels.lowerWidth) / 2;
            left.setLayoutParams(new LinearLayout.LayoutParams(sideWidth, panels.lowerHeight()));
            bottom.setLayoutParams(new LinearLayout.LayoutParams(
                    panels.lowerWidth, panels.lowerHeight()));
            right.setLayoutParams(new LinearLayout.LayoutParams(
                    width - panels.lowerWidth - sideWidth, panels.lowerHeight()));
        }
        sendButtons();
    }

    private void changeLowerSize() {
        enlarged = !enlarged;
        expandButton.setText(enlarged ? "Restore lower screen" : "Enlarge lower screen");
        if (portraitRoot != null) updateLowerLayout(portraitRoot.getWidth(), portraitRoot.getHeight());
    }

    private void sendButtons() {
        if (coreReady && hostToken != null && sessionOwner.isCurrentHost(hostToken) && left != null && right != null) {
            int mask = left.mask() | right.mask();
            if (mask == 0 || sessionOwner.isCurrentRun(hostToken, runToken)) NativeDs.buttons(mask);
        }
    }

    private void openRom() {
        if (!coreReady) { message("DS core is not initialized"); return; }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_ROM);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_ROM || result != RESULT_OK || data == null || data.getData() == null || !coreReady) return;
        byte[] bytes;
        try { bytes = readRom(data.getData()); }
        catch (IOException failure) { message("Cannot read DS file: " + failure.getMessage()); return; }
        String identity = identity(bytes);
        final GameSaveStore.Recovery[] recovery = new GameSaveStore.Recovery[1];
        final ActiveTimelineStore.Resolution[] timeline = new ActiveTimelineStore.Resolution[1];
        final CheckpointStore.Identity[] pinnedIdentity = new CheckpointStore.Identity[1];
        EmulatorSessionOwner.OpenResult opened = sessionOwner.open(hostToken, identity,
                new EmulatorSessionOwner.GameLoader() {
                    @Override public String loadAndRestore() {
                        try {
                            pinnedIdentity[0] = PinnedCheckpointIdentity.ds(DsActivity.this, identity,
                                    NativeDs.serializerApiVersion());
                        } catch (IOException failure) {
                            return "Pinned DS identity is unavailable; saved histories were preserved";
                        }
                        timeline[0] = timelineStore.resolveCold(true, pinnedIdentity[0], saveStore, branchStore);
                        if (!timeline[0].mayLoad())
                            return "Timeline recovery requires an explicit internal choice: " + timeline[0].detail;
                        activeIdentity = pinnedIdentity[0];
                        activeBranch = timeline[0].branch;
                        activeSaveStore = activeBranch == null ? saveStore : activeBranch.saves;
                        String initError = initializeForTimeline(activeBranch);
                        if (initError != null) return "DS core could not open selected timeline storage: " + initError;
                        String issue = NativeDs.load(bytes);
                        if (issue != null) return "DS ROM rejected: " + issue;
                        nativeGameLoaded = true;
                        recovery[0] = new GameSaveStore.Recovery(timeline[0].nativeSave,
                                timeline[0].detail, timeline[0].nativeGeneration,
                                timeline[0].nativeSave == null ? GameSaveStore.Status.UNKNOWN : GameSaveStore.Status.AVAILABLE);
                        if (timeline[0].nativeSave != null) {
                            String restoreError = NativeDs.restore(timeline[0].nativeSave);
                            byte[] checked = restoreError == null ? NativeDs.snapshot() : null;
                            if (restoreError != null || checked == null || !Arrays.equals(timeline[0].nativeSave, checked))
                                return "DS save preserved; core restore validation failed";
                            android.util.Log.i("PlumpDS", "DS cartridge save restored and byte-verified: generation=" +
                                    timeline[0].nativeGeneration);
                        }
                        loaded = true;
                        activeRom = bytes.clone();
                        gameId = identity;
                        frames = 0;
                        userPaused = false;
                        return null;
                    }

                    @Override public void discardFailedLoad() {
                        if (nativeGameLoaded) NativeDs.unload();
                        nativeGameLoaded = loaded = false;
                        gameId = null;
                        activeRom = null;
                        activeBranch = null;
                        activeIdentity = null;
                        activeSaveStore = saveStore;
                        frames = 0;
                    }

                    @Override public String selectedBranchId() {
                        return timeline[0] == null || timeline[0].branch == null ? null : timeline[0].branch.id;
                    }
                });
        if (!opened.opened) {
            message("DS ROM/session open failed: " + opened.error);
            return;
        }
        pauseButton.setText("Pause");
        GameSaveStore.Recovery prior = recovery[0];
        String ready = prior.warning != null ? prior.warning :
                (NativeDs.saveSize() == 0 ? "DS loaded — no cartridge save region; virtual SD is disabled" :
                        NativeDs.saveSize() > 1024 * 1024 ?
                        "DS loaded — unsupported cartridge-save size; do not rely on saving" :
                        "DS loaded — native cartridge save active");
        if (timeline[0].classification == ActiveTimelineStore.Classification.BRANCH_NATIVE_ONLY)
            ready = "DS historical branch native save restored and verified — exact machine resume unavailable";
        else if (timeline[0].potentialExactStateAvailable())
            ready = "DS branch native save restored; a matching state exists but was not auto-loaded";
        if (opened.previousReceipt.corrupt)
            ready += " — prior session receipt is corrupt; no frame resume";
        else if (opened.previousReceipt.state == SessionReceiptStore.State.RUNNING)
            ready += " — last receipt says RUNNING; no frame resume";
        message(ready);
        if (foreground && !sessionOwner.resume(hostToken))
            message("DS loaded, but run start is blocked by an unresolved session transition");
    }

    private byte[] readRom(Uri file) throws IOException {
        try (InputStream stream = getContentResolver().openInputStream(file)) {
            if (stream == null) throw new IOException("No document stream");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                if (output.size() > MAX_ROM - length) throw new IOException("Test ROM exceeds 64 MiB");
                output.write(buffer, 0, length);
            }
            if (output.size() < 512) throw new IOException("Incomplete DS cartridge");
            return output.toByteArray();
        }
    }

    private static String identity(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void togglePause() {
        if (!loaded) { message("Choose an NDS test ROM first"); return; }
        if (!userPaused) {
            userPaused = true;
            pauseButton.setText("Resume");
            boolean saved = sessionOwner.pause(hostToken);
            message(saved ? "DS paused — native save and session receipt flushed where available"
                    : "DS pause unresolved — save or receipt failed; switching and resume are blocked");
        } else {
            if (!foreground) return;
            userPaused = false;
            if (sessionOwner.resume(hostToken)) pauseButton.setText("Pause");
            else {
                userPaused = true;
                message("DS resume blocked until stop, save and receipt are resolved");
            }
        }
    }

    private boolean startOwnedLoops(long token) {
        if (!loaded || !coreReady || !foreground || userPaused) return false;
        if ((emulatorThread != null && emulatorThread.isAlive()) || (audioThread != null && audioThread.isAlive()))
            return false;
        if (!running.compareAndSet(false, true)) return false;
        runToken = token;
        lastStats = SystemClock.elapsedRealtime();
        audioUnderruns.set(0);
        try {
        emulatorThread = new Thread(() -> {
            long period = (long) (1_000_000_000.0 / NativeDs.rate());
            long deadline = System.nanoTime();
            long previousSave = SystemClock.elapsedRealtime();
            DsFrameTiming timings = new DsFrameTiming();
            while (running.get() && sessionOwner.isCurrentRun(hostToken, token)) {
                long start = System.nanoTime();
                NativeDs.frame();
                long frameNs = System.nanoTime() - start;
                timings.record(frameNs);
                ++frames;
                long now = SystemClock.elapsedRealtime();
                if (now - lastStats > 3000) {
                    long intervalMs = now - lastStats;
                    lastStats = now;
                    String metrics = NativeDs.stats() +
                            " measuredFps=" + String.format(Locale.ROOT, "%.2f",
                                timings.count() * 1000.0 / intervalMs) +
                            " targetFps=" + String.format(Locale.ROOT, "%.2f", NativeDs.rate()) +
                            " avgCore=" + timings.averageMillis() +
                            "ms peakCore=" + timings.peakMillis() +
                            "ms p95Core=" + timings.percentileMillis(95) +
                            "ms p99Core=" + timings.percentileMillis(99) +
                            "ms audioUnderruns=" + audioUnderruns.get();
                    android.util.Log.i("PlumpDS", metrics);
                    runOnUiThread(() -> {
                        if (sessionOwner.isCurrentRun(hostToken, token)) message("DS " + metrics);
                    });
                    timings.reset();
                }
                if (now - previousSave >= 30_000) {
                    save(false, token);
                    previousSave = now;
                }
                deadline += period;
                long wait = deadline - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -3 * period) deadline = System.nanoTime();
            }
        }, "Plump-DS-core");
        emulatorThread.start();
        audioThread = new Thread(() -> pumpAudio(token), "Plump-DS-audio");
        audioThread.start();
        return true;
        } catch (RuntimeException failure) {
            running.set(false);
            join(emulatorThread);
            join(audioThread);
            return false;
        }
    }

    private void pumpAudio(long token) {
        AudioTrack output = null;
        try {
            int rate = NativeDs.audioRate();
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("Unsupported DS audio format");
            output = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    Math.max(min, 16384), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack unavailable");
            output.play();
            short[] samples = new short[2048];
            long lastUnderrunSample = SystemClock.elapsedRealtime();
            while (running.get() && sessionOwner.isCurrentRun(hostToken, token)) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastUnderrunSample >= 1000) {
                    audioUnderruns.set(output.getUnderrunCount());
                    lastUnderrunSample = now;
                }
                int size = NativeDs.drain(samples);
                if (size == 0) { SystemClock.sleep(5); continue; }
                int written = 0;
                while (written < size && running.get() && sessionOwner.isCurrentRun(hostToken, token)) {
                    int result = output.write(samples, written, size - written, AudioTrack.WRITE_NON_BLOCKING);
                    if (result < 0) throw new IllegalStateException("PCM output failed: " + result);
                    if (result == 0) SystemClock.sleep(3);
                    else written += result;
                }
            }
        } catch (Exception failure) {
            error("DS sound unavailable: " + failure.getMessage(), token);
            running.set(false);
            sessionOwner.noteSaveFailure(hostToken, token);
        } finally {
            if (output != null) try { output.pause(); output.flush(); output.release(); }
            catch (IllegalStateException ignored) { }
        }
    }

    private boolean stopAndSave() {
        running.set(false);
        if (bottom != null && coreReady) bottom.release();
        if (left != null) left.releaseAll();
        if (right != null) right.releaseAll();
        if (coreReady) NativeDs.buttons(0);
        boolean coreStopped = join(emulatorThread);
        boolean audioStopped = join(audioThread);
        if (!coreStopped || !audioStopped) {
            message("DS threads did not stop; save and core unload blocked");
            return false;
        }
        emulatorThread = audioThread = null;
        if (coreReady) NativeDs.clearAudio();
        return save(true, 0);
    }

    private boolean join(Thread thread) {
        if (thread == null) return true;
        thread.interrupt();
        try { thread.join(1800); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !thread.isAlive();
    }

    private boolean save(boolean report, long callbackRun) {
        GameSaveStore destination = activeSaveStore == null ? saveStore : activeSaveStore;
        if (!loaded || frames == 0 || gameId == null || destination == null) return true;
        boolean writeAttempted = false;
        try {
            if (activeIdentity == null || timelineStore == null)
                throw new IOException("Active timeline identity is unavailable; cartridge save blocked");
            String branchId = activeBranch == null ? null : activeBranch.id;
            timelineStore.assertWritable(activeIdentity, branchId, destination);
            int size = NativeDs.saveSize();
            if (size == 0) return true; // Homebrew virtual SD files are NOT cartridge SRAM.
            if (size < 0 || size > 1024 * 1024)
                throw new IOException("Unsupported DS SRAM size " + size + "; existing progress not flushed");
            byte[] data = NativeDs.snapshot();
            if (data == null || data.length != size)
                throw new IOException("DS cartridge SRAM returned no complete snapshot");
            writeAttempted = true;
            destination.write(gameId, data);
            ActiveTimelineStore.SelectionResult pointer = timelineStore.recordDurableSave(
                    activeIdentity, branchId, destination, data);
            if (!pointer.committed || pointer.nativeGeneration <= 0 ||
                    !ActiveTimelineStore.sha256(data).equals(pointer.nativeSha256))
                throw new IOException("Active timeline native generation failed post-write verification");
            return true;
        } catch (IOException failure) {
            if (writeAttempted && activeIdentity != null && timelineStore != null)
                timelineStore.markAmbiguous(activeIdentity);
            if (callbackRun != 0) sessionOwner.noteSaveFailure(hostToken, callbackRun);
            if (report) message("DS cartridge SRAM NOT saved: " + failure.getMessage());
            else {
                String identity = gameId;
                runOnUiThread(() -> {
                    if (hostToken != null && sessionOwner.isCurrentHost(hostToken) &&
                            identity != null && identity.equals(gameId))
                        message("DS periodic cartridge save failed: " + failure.getMessage());
                });
            }
            return false;
        }
    }

    private EmulatorSessionOwner.RestoreDisposition restoreBranch(HistoricalBranchStore.Branch branch) {
        byte[] rom = activeRom;
        if (branch == null || rom == null || gameId == null || !gameId.equals(branch.identity.romSha256))
            return EmulatorSessionOwner.RestoreDisposition.FAILED;
        activeBranch = branch;
        activeSaveStore = branch.saves; // Branch routing is installed before state load can touch EEPROM.
        try {
            retireCoreWithoutSave();
            String issue = NativeDs.initialize(branch.systemDirectory.toString(), branch.coreDataDirectory.toString());
            if (issue != null) throw new IllegalStateException(issue);
            coreReady = true;
            reattachSurfaces();
            issue = NativeDs.load(rom);
            if (issue != null) throw new IllegalStateException(issue);
            nativeGameLoaded = true;
            issue = NativeDs.restoreCheckpoint(branch.machineState(), branch.nativeSave());
            if (issue != null) throw new IllegalStateException(issue);
            loaded = true;
            frames = 0;
            userPaused = true;
            return EmulatorSessionOwner.RestoreDisposition.RESTORED_PAUSED;
        } catch (RuntimeException restoreFailure) {
            android.util.Log.e("PlumpDS", "Historical DS restore failed; retiring the core and preserving both timelines",
                    restoreFailure);
        }
        try { retireCoreWithoutSave(); } catch (RuntimeException ignored) { }
        return EmulatorSessionOwner.RestoreDisposition.FAILED;
    }

    private String initializeForTimeline(HistoricalBranchStore.Branch branch) {
        retireCoreWithoutSave();
        File system = branch == null ? systemDirectory : branch.systemDirectory.toFile();
        File coreData = branch == null ? canonicalCoreDirectory : branch.coreDataDirectory.toFile();
        String issue = NativeDs.initialize(system.getAbsolutePath(), coreData.getAbsolutePath());
        if (issue != null) return issue;
        coreReady = true;
        reattachSurfaces();
        return null;
    }

    private void reattachSurfaces() {
        if (topDisplay != null && topDisplay.getHolder().getSurface().isValid())
            NativeDs.topSurface(topDisplay.getHolder().getSurface());
        if (bottom != null && bottom.getHolder().getSurface().isValid())
            NativeDs.bottomSurface(bottom.getHolder().getSurface());
    }

    private void retireCoreWithoutSave() {
        if (coreReady) NativeDs.shutdown();
        coreReady = nativeGameLoaded = loaded = false;
        frames = 0;
    }

    private void syncPath(java.nio.file.Path path) throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(path.toAbsolutePath().toString(), OsConstants.O_RDONLY, 0);
            Os.fsync(fd);
        } catch (android.system.ErrnoException failure) {
            throw new IOException("Private branch directory sync failed", failure);
        } finally {
            if (fd != null) try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
        }
    }

    private void syncSaveDirectory(File directory) throws IOException { syncPath(directory.toPath()); }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (loaded && !userPaused && !sessionOwner.resume(hostToken))
            message("DS resume blocked; prior stop, save or receipt is unresolved");
    }

    @Override protected void onPause() {
        foreground = false;
        if (hostToken != null && !sessionOwner.pause(hostToken))
            message("DS background pause unresolved; core switch and resume are blocked");
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (hostToken != null && !sessionOwner.closeHost(hostToken))
            android.util.Log.e("PlumpDS", "DS host close unresolved; retaining core ownership to prevent unsafe reuse");
        super.onDestroy();
    }
}
