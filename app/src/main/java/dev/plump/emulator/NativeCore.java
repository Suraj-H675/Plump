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
    static native byte[] snapshotSaveRam();
    static native String restoreSaveRam(byte[] bytes);
    static native void setButtons(int mask);
    static native void setSurface(Surface surface);
    static native int drainAudio(short[] samples);
    static native void discardAudio();
    static native double frameRate();
    static native int audioRate();
    static native void unloadGame();
    static native void shutdown();
}
