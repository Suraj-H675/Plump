package dev.plump.emulator;

final class SaveRamSizeValidator {
    private SaveRamSizeValidator() {}

    /**
     * Pinned mGBA advertises a 128 KiB AUTODETECT region at ROM load, then
     * resolves the licensed GBA SRAM fixture to 32 KiB after first execution.
     * P2 already persisted those legitimate 32 KiB PLSV v1 generations.
     * The native adapter additionally restricts this one exception to the
     * pre-first-frame restore boundary. No other truncation is accepted.
     */
    static boolean isLegacySramAutodetection(int savedBytes, int coreBytes) {
        return savedBytes == 32 * 1024 && coreBytes == 128 * 1024;
    }

    static boolean supportsRestore(int savedBytes, int coreBytes) {
        return savedBytes > 0 && coreBytes > 0 &&
                (savedBytes == coreBytes || isLegacySramAutodetection(savedBytes, coreBytes));
    }

    static String restoreError(int savedBytes, int coreBytes) {
        if (coreBytes <= 0) return "The loaded core has no compatible save RAM region";
        if (!supportsRestore(savedBytes, coreBytes))
            return "Native save size mismatch: expected " + coreBytes + " bytes, found " + savedBytes;
        return null;
    }
}
