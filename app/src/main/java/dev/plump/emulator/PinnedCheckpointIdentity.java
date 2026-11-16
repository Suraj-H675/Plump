package dev.plump.emulator;

import android.content.Context;
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
    static final String MGBA_BINARY_SHA256 = "05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429";
    static final String MELONDS_FRONTEND_REVISION = "f394adbacb5722ee97c1b37c8064da9a25818310";
    static final String MELONDS_CORE_REVISION = "16f127dbb587a73371827ce79689c5d237c4b59b";
    static final String MELONDS_BINARY_SHA256 = "83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30";

    private PinnedCheckpointIdentity() { }

    static CheckpointStore.Identity gba(Context context, String romSha256, int apiVersion) throws IOException {
        return create(context, "gba", romSha256, "mgba-libretro", MGBA_REVISION,
                "none", MGBA_BINARY_SHA256, "libmgba_libretro.so", apiVersion);
    }

    static CheckpointStore.Identity ds(Context context, String romSha256, int apiVersion) throws IOException {
        return create(context, "ds", romSha256, "melonds-libretro-ds", MELONDS_CORE_REVISION,
                MELONDS_FRONTEND_REVISION, MELONDS_BINARY_SHA256, "libmelondsds_libretro.so", apiVersion);
    }

    private static CheckpointStore.Identity create(Context context, String console, String romSha256,
                                                    String coreId, String revision, String frontendRevision,
                                                    String expectedBinary, String binaryName, int apiVersion) throws IOException {
        if (context == null) throw new IOException("Checkpoint context is unavailable");
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0)
            throw new IOException("Runtime ABI is unavailable");
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, binaryName);
        String actualBinary = digest(binary);
        if (!expectedBinary.equals(actualBinary))
            throw new IOException("Installed " + coreId + " binary does not match the checkpoint pin");
        return new CheckpointStore.Identity(console, romSha256, coreId, revision,
                frontendRevision, actualBinary, Build.SUPPORTED_ABIS[0], apiVersion);
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
