package dev.plump.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Host-only two-process crash/recovery probe for the E2 working-image boundary. */
public final class VirtualSdCrashRecoveryProbe {
    private static final long IMAGE_BYTES = 128L * 1024L * 1024L;
    private static final String ROM_SHA =
            "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588";
    private static final String CORE_FRONTEND_REV = "f394adbacb5722ee97c1b37c8064da9a25818310";
    private static final String CORE_REV = "16f127dbb587a73371827ce79689c5d237c4b59b";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "usage: VirtualSdCrashRecoveryProbe owner|recover CORE ROM OUTPUT_ROOT");
        if ("owner".equals(args[0])) owner(args[1], args[2], args[3]);
        else if ("recover".equals(args[0])) recover(args[1], args[2], args[3]);
        else throw new IllegalArgumentException("unknown crash probe mode");
    }

    private static void owner(String coreArg, String romArg, String rootArg) throws Exception {
        Path core = Path.of(coreArg).toAbsolutePath().normalize();
        Path rom = Path.of(romArg).toAbsolutePath().normalize();
        Path root = Path.of(rootArg).toAbsolutePath().normalize();
        Path baseline = Path.of(System.getenv("PLUMP_E2_REAL_BASELINE_IMAGE")).toAbsolutePath().normalize();
        require(core, rom, baseline);
        Files.createDirectories(root);
        VirtualSdGenerationStore store = store(root, core, rom);
        VirtualSdGenerationStore.Generation first = store.initialize(baseline);
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        Path system = root.resolve("crash-system");
        Path result = root.resolve("crash-run");
        Files.createDirectories(system);
        Files.createDirectories(result);
        Path script = Path.of("tests/roms/verify-p4-e2-real-core-session.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("PLUMP_E2_PYTHON", "python3");
        Process child = new ProcessBuilder(python, script.toString(), session.saveDirectory.toString(),
                system.toString(), rom.toString(), core.toString(), result.toString(), "--hard-stop")
                .directory(Path.of(".").toFile()).inheritIO().start();
        if (!child.waitFor(90, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IOException("E2 crash child timed out");
        }
        if (child.exitValue() != 87) throw new IOException("Expected hard-stop exit 87, got " + child.exitValue());
        if (!Files.isRegularFile(session.imageFile)) throw new IOException("Crash session image disappeared");
        if (first.sequence != 1) throw new IOException("Unexpected canonical baseline sequence");
        Runtime.getRuntime().halt(87);
    }

    private static void recover(String coreArg, String romArg, String rootArg) throws Exception {
        Path core = Path.of(coreArg).toAbsolutePath().normalize();
        Path rom = Path.of(romArg).toAbsolutePath().normalize();
        Path root = Path.of(rootArg).toAbsolutePath().normalize();
        Path baseline = Path.of(System.getenv("PLUMP_E2_REAL_BASELINE_IMAGE")).toAbsolutePath().normalize();
        require(core, rom, baseline);
        VirtualSdGenerationStore store = store(root, core, rom);
        VirtualSdGenerationStore.Generation current = store.current();
        if (current.sequence != 1 || !sha256(baseline).equals(current.imageSha256))
            throw new IOException("Crash recovery promoted or changed canonical virtual-SD progress");
        VirtualSdGenerationStore.WorkingSession recovered = store.openSession();
        try {
            if (!sha256(recovered.imageFile).equals(current.imageSha256))
                throw new IOException("Recovered working image does not match canonical baseline");
        } finally {
            recovered.close();
        }
        try (var entries = Files.newDirectoryStream(root.resolve("canonical/quarantine"))) {
            if (!entries.iterator().hasNext()) throw new IOException("Abandoned crash session was not quarantined");
        }
        System.out.println("P4_E2_REAL_CORE_PROCESS_CRASH_RECOVERY_PASS");
        System.out.println("canonical_generation=" + current.id);
    }

    private static VirtualSdGenerationStore store(Path root, Path core, Path rom) throws Exception {
        CheckpointStore.Identity identity = new CheckpointStore.Identity(
                "ds", sha256(rom), "melondsds-libretro", CORE_REV, CORE_FRONTEND_REV,
                sha256(core), "x86_64", 1);
        return new VirtualSdGenerationStore(root.resolve("canonical"), identity, "canonical", IMAGE_BYTES,
                VirtualSdCrashRecoveryProbe::syncDirectory, VirtualSdCrashRecoveryProbe::validateFat,
                ignored -> { });
    }

    private static void require(Path core, Path rom, Path baseline) throws Exception {
        if (Files.isSymbolicLink(core) || !Files.isRegularFile(core) || Files.isSymbolicLink(rom) || !Files.isRegularFile(rom) ||
                Files.isSymbolicLink(baseline) || !Files.isRegularFile(baseline) || Files.size(baseline) != IMAGE_BYTES)
            throw new IOException("Invalid E2 crash probe fixture");
        if (!sha256(rom).equals(ROM_SHA)) throw new IOException("Unexpected E2 ROM SHA-256");
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void validateFat(Path image) throws IOException {
        try {
            Process process = new ProcessBuilder("fsck.fat", "-nv", image.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("FAT validation timed out");
            }
            if (process.exitValue() != 0) throw new IOException("FAT validation failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("FAT validation interrupted", interrupted);
        }
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
}
