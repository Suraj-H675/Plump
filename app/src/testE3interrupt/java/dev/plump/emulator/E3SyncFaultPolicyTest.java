package dev.plump.emulator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Prevent an inert UI-selected sync fault from silently publishing a save. */
public final class E3SyncFaultPolicyTest {
    @Test public void explicitE3SyncModeArmsEvenWithoutAThrowingStoreFaultPoint() {
        assertTrue(E3SyncFaultPolicy.shouldArmSyntheticEio(
                true, "dev.plump.emulator.e3sync", "SIMULATED_FSYNC_EIO"));
    }

    @Test public void cannotArmForProductionOrOtherTestPackages() {
        for (String wrong : new String[]{"dev.plump.emulator", "dev.plump.emulator.e3test",
                "dev.plump.emulator.e3fault", "dev.plump.emulator.e3close"}) {
            assertFalse(wrong, E3SyncFaultPolicy.shouldArmSyntheticEio(
                    true, wrong, "SIMULATED_FSYNC_EIO"));
        }
    }

    @Test public void disabledAndNormalModesCannotArm() {
        assertFalse(E3SyncFaultPolicy.shouldArmSyntheticEio(
                false, "dev.plump.emulator.e3sync", "SIMULATED_FSYNC_EIO"));
        assertFalse(E3SyncFaultPolicy.shouldArmSyntheticEio(
                true, "dev.plump.emulator.e3sync", "NONE"));
    }
}
