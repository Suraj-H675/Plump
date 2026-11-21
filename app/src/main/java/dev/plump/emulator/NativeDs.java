package dev.plump.emulator;

import android.view.Surface;

/** P3-only Libretro melonDS DS software renderer host (separate from accepted mGBA). */
final class NativeDs {
    static final String LOAD_ERROR;
    static {
        String problem = null;
        try {
            System.loadLibrary("melondsds_libretro");
            System.loadLibrary("plump_ds_host");
        } catch (UnsatisfiedLinkError error) {
            problem = error.getMessage();
        }
        LOAD_ERROR = problem;
    }

    private NativeDs() {}
    static native String initialize(String privateSystemDir, String privateSaveDir);
    /**
     * E3 test build only; this JNI symbol is deliberately absent from the
     * production host library. Requires an exact E2-instrumented core.
     */
    static native String initializeE3(String privateSystemDir, String workingSaveDir);
    /** Quiesced E3 core only. Returns 21 fixed ABI fields plus close() result. */
    static native long[] closeE3Dldi();
    /** E3 test library only. Refuses any package other than isolated e3native. */
    static native boolean configureE3NativeFault(String packageName, String mode);
    /** Non-mutating native DLDI status snapshot; 22nd field means close was not invoked. */
    static native long[] snapshotE3DldiStatus();
    static native String load(byte[] rom);
    static native void frame();
    static native int serializerCapabilities();
    static native int serializerApiVersion();
    static native byte[][] captureCheckpoint();
    static native String restoreCheckpoint(byte[] machineState, byte[] nativeSave);
    static native int saveSize();
    static native byte[] snapshot();
    static native String restore(byte[] save);
    static native void buttons(int bitMask);
    static native void touch(boolean down, int x, int y);
    static native void topSurface(Surface surface);
    static native void bottomSurface(Surface surface);
    static native int drain(short[] pcm);
    static native void clearAudio();
    static native double rate();
    static native int audioRate();
    static native String stats();
    static native void unload();
    static native void shutdown();

    static VirtualSdGenerationStore.CoreCloseReceipt e3Receipt(long[] fields) {
        if (fields == null || fields.length != 22)
            throw new IllegalArgumentException("Missing exact E2 DLDI close status");
        for (long field : fields) {
            if (field < 0) throw new IllegalArgumentException("Negative E2 DLDI counter");
        }
        if (fields[0] > Integer.MAX_VALUE || fields[1] > Integer.MAX_VALUE ||
                fields[2] > Integer.MAX_VALUE || fields[3] > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid E2 DLDI receipt header");
        return new VirtualSdGenerationStore.CoreCloseReceipt(
                (int)fields[0], (int)fields[1], (int)fields[2], (int)fields[3],
                fields[5], fields[8], fields[9], fields[12], fields[13],
                fields[14] != 0, fields[15] != 0, fields[16] != 0,
                fields[17] != 0, fields[18] != 0, fields[21] != 0,
                fields[19] != 0, fields[20] != 0);
    }
}
