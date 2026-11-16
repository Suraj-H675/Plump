package dev.plump.emulator;

import java.io.File;
import java.io.IOException;

/** Host-only verification against exact APK-extracted ELF files, not a gameplay entry point. */
public final class PackagedCheckpointIdentityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: FILE CORE_ID debug|release accept|reject");
        boolean debuggable;
        if ("debug".equals(args[2])) debuggable = true;
        else if ("release".equals(args[2])) debuggable = false;
        else throw new IllegalArgumentException("Unknown APK variant");
        boolean shouldReject = "reject".equals(args[3]);
        if (!shouldReject && !"accept".equals(args[3]))
            throw new IllegalArgumentException("Unknown expectation");
        String pin = PinnedCheckpointIdentity.packagedDigestForVariant(args[1], debuggable);
        try {
            String actual = PinnedCheckpointIdentity.verifyInstalledBinary(new File(args[0]), pin, args[1]);
            if (shouldReject) throw new AssertionError("Unexpected installed core was accepted");
            System.out.println("PASS production Java runtime identity " + args[2] + "/" + args[1]
                    + ": " + actual);
        } catch (IOException mismatch) {
            if (!shouldReject || !mismatch.getMessage().contains("does not match")) throw mismatch;
            System.out.println("PASS production Java rejects unexpected " + args[2] + "/" + args[1]);
        }
    }
}
