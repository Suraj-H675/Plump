package dev.plump.emulator;

import android.view.Surface;

/** JNI entry points for the single in-process mGBA Libretro session. */
final class NativeCore {
    static final String LOAD_ERROR;
    static {
        String failure = null;
        try {
            System.loadLibrary("mgba_libretro");
            System.loadLibrary("plump_host");
        } catch (UnsatisfiedLinkError error) {
            failure = error.getMessage();
        }
        LOAD_ERROR = failure;
    }

    private NativeCore() {}

    static native String initialize();
    static native String loadGame(byte[] rom);
    static native void runFrame();
    static native int serializerCapabilities();
    static native int serializerApiVersion();
    static native byte[][] captureCheckpoint();
    static native String restoreCheckpoint(byte[] machineState, byte[] nativeSave);
    static native byte[] snapshotSaveRam();
    static String restoreSaveRam(byte[] bytes) {
        if (bytes == null) return "No saved SRAM data";
        int coreSize = saveRamSize();
        String sizeError = SaveRamSizeValidator.restoreError(bytes.length, coreSize);
        return sizeError == null ? restoreSaveRamChecked(bytes) : sizeError;
    }
    private static native int saveRamSize();
    private static native String restoreSaveRamChecked(byte[] bytes);
    static native void setButtons(int mask);
    static native void setSurface(Surface surface);
    static native String videoDiagnostics();
    static native int drainAudio(short[] samples);
    static native void discardAudio();
    static native double frameRate();
    static native int audioRate();
    static native void unloadGame();
    static native void shutdown();
}
