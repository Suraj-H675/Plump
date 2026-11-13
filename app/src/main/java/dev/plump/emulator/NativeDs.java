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
    static native String load(byte[] rom);
    static native void frame();
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
}
