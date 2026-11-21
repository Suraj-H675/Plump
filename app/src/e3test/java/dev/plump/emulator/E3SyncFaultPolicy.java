package dev.plump.emulator;

/** Narrow, pure-Java policy for the separately installed E3G sync test. */
final class E3SyncFaultPolicy {
    private E3SyncFaultPolicy() { }

    static boolean shouldArmSyntheticEio(boolean enabled, String packageName, String mode) {
        return enabled && "dev.plump.emulator.e3sync".equals(packageName) &&
                "SIMULATED_FSYNC_EIO".equals(mode);
    }
}
