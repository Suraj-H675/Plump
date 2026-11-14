package dev.plump.emulator;

import org.junit.Test;

import static org.junit.Assert.*;

public class SaveRamSizeValidatorTest {
    @Test public void acceptsTheTwoP2LegacySaveLengthsAtExactCoreSize() {
        for (int legacyBytes : new int[]{32768, 131072}) {
            assertTrue(SaveRamSizeValidator.supportsRestore(legacyBytes, legacyBytes));
            assertNull(SaveRamSizeValidator.restoreError(legacyBytes, legacyBytes));
            assertFalse(SaveRamSizeValidator.supportsRestore(legacyBytes / 2, legacyBytes));
            assertNotNull(SaveRamSizeValidator.restoreError(legacyBytes / 2, legacyBytes));
            assertFalse(SaveRamSizeValidator.supportsRestore(legacyBytes + 1, legacyBytes));
            assertNotNull(SaveRamSizeValidator.restoreError(legacyBytes + 1, legacyBytes));
        }
    }

    @Test public void acceptsOnlyVerified32KiBTo128KiBAutodetectionException() {
        assertTrue(SaveRamSizeValidator.isLegacySramAutodetection(32768, 131072));
        assertTrue(SaveRamSizeValidator.supportsRestore(32768, 131072));
        assertNull(SaveRamSizeValidator.restoreError(32768, 131072));
        assertFalse(SaveRamSizeValidator.supportsRestore(65536, 131072));
        assertFalse(SaveRamSizeValidator.supportsRestore(32768, 65536));
        assertFalse(SaveRamSizeValidator.supportsRestore(131072, 32768));
        assertFalse(SaveRamSizeValidator.supportsRestore(32767, 131072));
    }

    @Test public void rejectsMissingSaveOrMissingCoreRegion() {
        assertFalse(SaveRamSizeValidator.supportsRestore(0, 32768));
        assertFalse(SaveRamSizeValidator.supportsRestore(32768, 0));
        assertNotNull(SaveRamSizeValidator.restoreError(0, 32768));
        assertNotNull(SaveRamSizeValidator.restoreError(32768, 0));
    }
}
