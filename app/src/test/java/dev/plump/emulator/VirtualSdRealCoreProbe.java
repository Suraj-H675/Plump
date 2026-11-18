package dev.plump.emulator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Host-only E2 integration probe. It drives the real pinned experimental
 * melonDS process against the production VirtualSdGenerationStore working
 * directory, then commits only after the native close receipt passes.
 */
public final class VirtualSdRealCoreProbe {
    private static final long IMAGE_BYTES = 128L * 1024L * 1024L;
    private static final String ROM_SHA =
            "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588";
    private static final String CORE_FRONTEND_REV = "f394adbacb5722ee97c1b37c8064da9a25818310";
    private static final String CORE_REV = "16f127dbb587a73371827ce79689c5d237c4b59b";

    private VirtualSdRealCoreProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "usage: VirtualSdRealCoreProbe CORE ROM OUTPUT_ROOT");
        Path core = Path.of(args[0]).toAbsolutePath().normalize();
        Path rom = Path.of(args[1]).toAbsolutePath().normalize();
        Path root = Path.of(args[2]).toAbsolutePath().normalize();
        Path baseline = Path.of(System.getenv("PLUMP_E2_REAL_BASELINE_IMAGE"))
                .toAbsolutePath().normalize();
        Path python = Path.of(System.getenv().getOrDefault("PLUMP_E2_PYTHON", "python3"));
        requireRegular(core);
        requireRegular(rom);
        requireRegular(baseline);
        if (!sha256(rom).equals(ROM_SHA)) throw new IOException("Unexpected E2 fixture ROM SHA-256");
        if (Files.size(baseline) != IMAGE_BYTES) throw new IOException("E2 baseline must be 128 MiB");
        Files.createDirectories(root);

        CheckpointStore.Identity identity = new CheckpointStore.Identity(
                "ds", sha256(rom), "melondsds-libretro", CORE_REV, CORE_FRONTEND_REV,
                sha256(core), "x86_64", 1);
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(
                root.resolve("canonical"), identity, "canonical", IMAGE_BYTES,
                VirtualSdRealCoreProbe::syncDirectory,
                VirtualSdRealCoreProbe::validateFat,
                ignored -> { });
        VirtualSdGenerationStore.Generation first = store.initialize(baseline);
        String firstSha = first.imageSha256;

        VirtualSdGenerationStore.Generation second = runAndCommit(store, first, core, rom, python, root, 1);
        if (firstSha.equals(second.imageSha256)) throw new IOException("First real DLDI session did not change the image");
        if (second.sequence != 2) throw new IOException("Unexpected second-generation sequence");

        VirtualSdGenerationStore.Generation third = runAndCommit(store, second, core, rom, python, root, 2);
        if (third.sequence != 3) throw new IOException("Unexpected third-generation sequence");
        if (second.imageSha256.equals(third.imageSha256)) throw new IOException("Second real DLDI session did not change the image");
        if (!first.imageSha256.equals(sha256(first.imageFile))) throw new IOException("Canonical baseline mutated");
        if (!second.imageSha256.equals(sha256(second.imageFile))) throw new IOException("Generation 2 mutated");
        if (!third.imageSha256.equals(sha256(third.imageFile))) throw new IOException("Generation 3 is not self-consistent");
        if (!third.id.equals(store.current().id)) throw new IOException("Published head is not generation 3");

        System.out.println("P4_E2_REAL_CORE_STORE_PASS");
        System.out.println("generation1=" + first.id + " sha256=" + first.imageSha256);
        System.out.println("generation2=" + second.id + " sha256=" + second.imageSha256);
        System.out.println("generation3=" + third.id + " sha256=" + third.imageSha256);
    }

    private static VirtualSdGenerationStore.Generation runAndCommit(
            VirtualSdGenerationStore store, VirtualSdGenerationStore.Generation base,
            Path core, Path rom, Path python, Path root, int generation) throws Exception {
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        Path result = root.resolve("core-run-" + generation);
        Files.createDirectories(result);
        Path system = root.resolve("system-" + generation);
        Files.createDirectories(system);
        Path script = Path.of("tests/roms/verify-p4-e2-real-core-session.py").toAbsolutePath().normalize();
        Process process = new ProcessBuilder(python.toString(), script.toString(),
                session.saveDirectory.toString(), system.toString(), rom.toString(), core.toString(),
                result.toString()).directory(Path.of(".").toFile()).inheritIO().start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("E2 real-core session timed out");
        }
        if (process.exitValue() != 0) throw new IOException("E2 real-core session failed with exit " + process.exitValue());
        Properties properties = new Properties();
        try (var input = Files.newInputStream(result.resolve("core-session.properties"))) {
            properties.load(input);
        }
        VirtualSdGenerationStore.CoreCloseReceipt receipt = new VirtualSdGenerationStore.CoreCloseReceipt(
                integer(properties, "version"), integer(properties, "state"), integer(properties, "first_error"),
                integer(properties, "last_error"), longValue(properties, "image_bytes"),
                longValue(properties, "read_failures"), longValue(properties, "write_failures"),
                longValue(properties, "dldi_read_failures"), longValue(properties, "dldi_write_failures"),
                bool(properties, "read_only"), bool(properties, "folder_sync"), bool(properties, "dirty"),
                bool(properties, "flush_attempted"), bool(properties, "flush_succeeded"),
                bool(properties, "close_returned"), bool(properties, "close_attempted"),
                bool(properties, "close_succeeded"));
        if (!receipt.permitsCommit(IMAGE_BYTES)) throw new IOException("Native E2 close receipt did not permit publication");
        return store.commit(session, receipt);
    }

    private static int integer(Properties p, String key) { return Integer.parseInt(require(p, key)); }
    private static long longValue(Properties p, String key) { return Long.parseLong(require(p, key)); }
    private static boolean bool(Properties p, String key) { return "1".equals(require(p, key)); }
    private static String require(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing E2 receipt field " + key);
        return value;
    }

    private static void requireRegular(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Expected regular file: " + path);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void validateFat(Path image) throws IOException {
        Process process = new ProcessBuilder("fsck.fat", "-nv", image.toString()).redirectErrorStream(true).start();
        final boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("FAT validation interrupted", interrupted);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("FAT validation timed out");
        }
        if (process.exitValue() != 0) throw new IOException("FAT validation failed: exit " + process.exitValue());
    }
}
