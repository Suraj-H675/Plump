package dev.plump.emulator;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class PinnedCheckpointIdentity {
    static final int CAP_SIZE = 1;
    static final int CAP_SERIALIZE = 2;
    static final int CAP_UNSERIALIZE = 4;
    static final int CAP_API_VERSION = 8;
    static final int REQUIRED_CAPABILITIES = CAP_SIZE | CAP_SERIALIZE | CAP_UNSERIALIZE | CAP_API_VERSION;
    static final String MGBA_REVISION = "d58ea8a86f30598fbf3958585de3d33028535072";
    // Source/build provenance only. Android installs the Gradle-stripped ELF, not this input file.
    static final String MGBA_UNSTRIPPED_INPUT_SHA256 = "05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429";
    // Pin each packaged variant independently. If future stripping differs, packaging regression fails closed.
    static final String MGBA_DEBUG_PACKAGED_SHA256 = "10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6";
    static final String MGBA_RELEASE_PACKAGED_SHA256 = "10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6";
    static final String MELONDS_FRONTEND_REVISION = "f394adbacb5722ee97c1b37c8064da9a25818310";
    static final String MELONDS_CORE_REVISION = "16f127dbb587a73371827ce79689c5d237c4b59b";
    static final String MELONDS_UNSTRIPPED_INPUT_SHA256 = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30";
    static final String MELONDS_DEBUG_PACKAGED_SHA256 = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30";
    static final String MELONDS_RELEASE_PACKAGED_SHA256 = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30";

    private PinnedCheckpointIdentity() { }

    static CheckpointStore.Identity gba(Context context, String romSha256, int apiVersion) throws IOException {
        return create(context, "gba", romSha256, "mgba-libretro", MGBA_REVISION,
                "none", "libmgba_libretro.so", apiVersion);
    }

    static CheckpointStore.Identity ds(Context context, String romSha256, int apiVersion) throws IOException {
        return create(context, "ds", romSha256, "melonds-libretro-ds", MELONDS_CORE_REVISION,
                MELONDS_FRONTEND_REVISION, "libmelondsds_libretro.so", apiVersion);
    }

    private static CheckpointStore.Identity create(Context context, String console, String romSha256,
                                                    String coreId, String revision, String frontendRevision,
                                                    String binaryName, int apiVersion) throws IOException {
        if (context == null) throw new IOException("Checkpoint context is unavailable");
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0)
            throw new IOException("Runtime ABI is unavailable");
        ApplicationInfo application = context.getApplicationInfo();
        if (application == null || application.nativeLibraryDir == null)
            throw new IOException("Installed checkpoint core directory is unavailable");
        boolean debuggable = (application.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String expectedBinary = packagedDigestForVariant(coreId, debuggable);
        File binary = new File(application.nativeLibraryDir, binaryName);
        String actualBinary = verifyInstalledBinary(binary, expectedBinary, coreId);
        return new CheckpointStore.Identity(console, romSha256, coreId, revision,
                frontendRevision, actualBinary, Build.SUPPORTED_ABIS[0], apiVersion);
    }

    static String packagedDigestForVariant(String coreId, boolean debuggable) throws IOException {
        if ("mgba-libretro".equals(coreId))
            return debuggable ? MGBA_DEBUG_PACKAGED_SHA256 : MGBA_RELEASE_PACKAGED_SHA256;
        if ("melonds-libretro-ds".equals(coreId))
            return debuggable ? MELONDS_DEBUG_PACKAGED_SHA256 : MELONDS_RELEASE_PACKAGED_SHA256;
        throw new IOException("No packaged checkpoint core pin for " + coreId);
    }

    static String verifyInstalledBinary(File binary, String expectedBinary, String coreId) throws IOException {
        String actualBinary = digest(binary);
        if (!expectedBinary.equals(actualBinary))
            throw new IOException("Installed " + coreId + " binary does not match the checkpoint pin");
        return actualBinary;
    }

    private static String digest(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Installed checkpoint core binary is unavailable");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
