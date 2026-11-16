package dev.plump.emulator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public final class PinnedCheckpointIdentityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void debugAndReleaseHaveExplicitPackagedPins() throws Exception {
        assertEquals("10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6",
                PinnedCheckpointIdentity.packagedDigestForVariant("mgba-libretro", true));
        assertEquals("10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6",
                PinnedCheckpointIdentity.packagedDigestForVariant("mgba-libretro", false));
        assertEquals("83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30",
                PinnedCheckpointIdentity.packagedDigestForVariant("melonds-libretro-ds", true));
        assertEquals("83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30",
                PinnedCheckpointIdentity.packagedDigestForVariant("melonds-libretro-ds", false));
        assertNotEquals(PinnedCheckpointIdentity.MGBA_UNSTRIPPED_INPUT_SHA256,
                PinnedCheckpointIdentity.MGBA_DEBUG_PACKAGED_SHA256);
        try {
            PinnedCheckpointIdentity.packagedDigestForVariant("unknown-core", true);
            fail("Unknown core must not be assigned a default hash");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("No packaged")); }
    }

    @Test public void unexpectedInstalledBinaryIsRejectedRatherThanAccepted() throws Exception {
        File installed = temporary.newFile("libmgba_libretro.so");
        byte[] expectedBytes = "synthetic-core-binary-for-digest-test".getBytes(StandardCharsets.UTF_8);
        Files.write(installed.toPath(), expectedBytes);
        String expectedDigest = sha256(expectedBytes);
        assertEquals(expectedDigest,
                PinnedCheckpointIdentity.verifyInstalledBinary(installed, expectedDigest, "mgba-libretro"));

        Files.write(installed.toPath(), "modified-core".getBytes(StandardCharsets.UTF_8));
        try {
            PinnedCheckpointIdentity.verifyInstalledBinary(installed, expectedDigest, "mgba-libretro");
            fail("An unexpected installed digest was accepted");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("does not match")); }
    }

    @Test public void unstrippedSourceDigestIsNotAcceptedAsAnInstalledCore() throws Exception {
        File installed = temporary.newFile("libmgba_libretro.so");
        Files.write(installed.toPath(), "not-the-pinned-elf".getBytes(StandardCharsets.UTF_8));
        try {
            PinnedCheckpointIdentity.verifyInstalledBinary(installed,
                    PinnedCheckpointIdentity.MGBA_DEBUG_PACKAGED_SHA256, "mgba-libretro");
            fail("Unrecognized ELF must fail closed");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("does not match")); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder(64);
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
