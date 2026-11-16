package dev.plump.emulator;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fresh-JVM crash matrix for selector recovery. Every kill process exits with Runtime.halt(87). */
public final class TimelineRecoveryHardKillProbe {
    private TimelineRecoveryHardKillProbe() { }

    private static final String ROM = "d".repeat(64);
    private static final CheckpointStore.Identity ID = new CheckpointStore.Identity("ds", ROM,
            "melonds-libretro-ds", "16f127dbb587a73371827ce79689c5d237c4b59b",
            "f394adbacb5722ee97c1b37c8064da9a25818310", "e".repeat(64), "arm64-v8a", 1);
    private static final byte[] MACHINE = bytes(4096, 11);
    private static final byte[] NATIVE = bytes(8192, 29);
    private static final byte[] ADVANCED = bytes(8192, 93);

    private static final class Stores {
        final Path root;
        final GameSaveStore canonical;
        final CheckpointStore checkpoints;
        final HistoricalBranchStore branches;
        final ActiveTimelineStore timelines;

        Stores(Path root) throws IOException {
            this.root = root;
            canonical = new GameSaveStore(root.resolve("canonical").toFile(), TimelineRecoveryHardKillProbe::syncSave);
            checkpoints = new CheckpointStore(root.resolve("checkpoints"),
                    TimelineRecoveryHardKillProbe::sync, ignored -> Long.MAX_VALUE, ignored -> { });
            branches = new HistoricalBranchStore(root.resolve("branches"),
                    TimelineRecoveryHardKillProbe::sync, ignored -> Long.MAX_VALUE,
                    TimelineRecoveryHardKillProbe::syncSave, ignored -> { });
            timelines = new ActiveTimelineStore(root.resolve("timelines"), TimelineRecoveryHardKillProbe::sync);
        }
    }

    private static void prepare(Path root) throws Exception {
        Files.createDirectories(root);
        Stores stores = new Stores(root);
        stores.canonical.write(ROM, bytes(8192, 3));
        stores.canonical.write(ROM, bytes(8192, 44));
        writeSnapshot(root.resolve("canonical.sha256"), snapshot(root.resolve("canonical")));
        CheckpointStore.Record checkpoint = stores.checkpoints.create(ID,
                CheckpointStore.Capture.supported(1, MACHINE, NATIVE), CheckpointStore.Kind.MANUAL);
        Files.writeString(root.resolve("checkpoint-id.txt"), checkpoint.id, StandardOpenOption.CREATE_NEW);
        if (!stores.timelines.selectCanonical(ID, stores.canonical).committed)
            throw new AssertionError("Canonical active selector did not commit");
        System.out.println("PASS PREPARED canonical three-file PLSV plus verified source checkpoint");
    }

    private static void kill(Path root, String point) throws Exception {
        Stores stores = new Stores(root);
        if ("BEFORE_BRANCH_SELECTION".equals(point)) halt(point);
        HistoricalBranchStore.Branch branch = stores.branches.create(stores.checkpoints,
                Files.readString(root.resolve("checkpoint-id.txt")), ID);
        Files.writeString(root.resolve("branch-id.txt"), branch.id, StandardOpenOption.CREATE_NEW);
        if ("AFTER_BRANCH_PUBLICATION".equals(point)) halt(point);

        ActiveTimelineStore.PendingSelection pending = stores.timelines.prepareBranch(ID, stores.branches, branch);
        if ("AFTER_SELECTOR_PREPARED".equals(point) || "BEFORE_CORE_INIT".equals(point)) halt(point);

        // These named cut points are host transaction barriers. D1 separately cold-starts and
        // advances each pinned engine through the real persisted pointer in a fresh process.
        HistoricalBranchStore.Branch reopened = stores.branches.open(branch.id, ID);
        if (!Files.isDirectory(reopened.systemDirectory) || !Files.isDirectory(reopened.coreDataDirectory))
            throw new AssertionError("Branch core-owned directories were not prepared");
        if ("AFTER_CORE_INIT".equals(point)) halt(point);

        GameSaveStore.Recovery nativeRecovery = reopened.saves.load(ROM);
        if (nativeRecovery.bytes == null || nativeRecovery.generation != 1 ||
                !java.util.Arrays.equals(nativeRecovery.bytes, NATIVE))
            throw new AssertionError("Branch native restore target failed readback");
        if ("AFTER_NATIVE_RESTORE".equals(point)) halt(point);

        if (!java.util.Arrays.equals(reopened.machineState(), MACHINE))
            throw new AssertionError("Immutable machine-state source failed readback");
        if ("AFTER_MACHINE_RESTORE".equals(point) || "BEFORE_RESUMABLE_COMMITTED".equals(point))
            halt(point);

        if (!stores.timelines.commit(pending).committed) throw new AssertionError("Resumable selector did not commit");
        if ("AFTER_RESUMABLE_COMMITTED".equals(point)) halt(point);
        if ("DURING_SUBSEQUENT_JOURNAL_WRITE".equals(point)) {
            GameSaveStore interrupted = new GameSaveStore(reopened.journalDirectory.toFile(),
                    TimelineRecoveryHardKillProbe::syncSave, failure -> {
                        if (failure == GameSaveStore.WritePoint.RECORD_PARTIAL_WRITE) halt(point);
                    });
            interrupted.write(ROM, ADVANCED);
            throw new AssertionError("Subsequent branch write failpoint did not halt");
        }
        throw new IllegalArgumentException("Unknown hard-kill point " + point);
    }

    private static void verify(Path root, String point) throws Exception {
        Stores stores = new Stores(root);
        String before = Files.readString(root.resolve("canonical.sha256"));
        String after = serialize(snapshot(root.resolve("canonical")));
        if (!before.equals(after)) throw new AssertionError("Canonical PLSV/receipt bytes changed at " + point);
        String checkpointId = Files.readString(root.resolve("checkpoint-id.txt"));
        if (!checkpointId.equals(stores.checkpoints.verify(checkpointId, ID).id))
            throw new AssertionError("Immutable source checkpoint was damaged at " + point);

        ActiveTimelineStore.Resolution result = stores.timelines.resolveCold(true, ID,
                stores.canonical, stores.branches);
        ActiveTimelineStore.Classification expected;
        switch (point) {
            case "BEFORE_BRANCH_SELECTION":
            case "AFTER_BRANCH_PUBLICATION":
                expected = ActiveTimelineStore.Classification.CANONICAL;
                break;
            case "AFTER_SELECTOR_PREPARED":
            case "BEFORE_CORE_INIT":
            case "AFTER_CORE_INIT":
            case "AFTER_NATIVE_RESTORE":
            case "AFTER_MACHINE_RESTORE":
            case "BEFORE_RESUMABLE_COMMITTED":
                expected = ActiveTimelineStore.Classification.AMBIGUOUS;
                break;
            case "AFTER_RESUMABLE_COMMITTED":
            case "DURING_SUBSEQUENT_JOURNAL_WRITE":
                expected = ActiveTimelineStore.Classification.POTENTIAL_EXACT_STATE;
                break;
            default: throw new IllegalArgumentException("Unknown verification point " + point);
        }
        if (result.classification != expected)
            throw new AssertionError("Recovery at " + point + " classified " + result.classification +
                    ", expected " + expected + ": " + result.detail);
        if (expected == ActiveTimelineStore.Classification.POTENTIAL_EXACT_STATE) {
            if (result.nativeGeneration != 1 || !java.util.Arrays.equals(result.nativeSave, NATIVE))
                throw new AssertionError("Incomplete branch journal was mistaken for a committed generation");
        }
        System.out.println("PASS FRESH-JVM RECOVERY " + point + " => " + result.classification +
                "; canonical three-file PLSV unchanged; source checkpoint valid");
    }

    private static void halt(String point) {
        System.out.println("HALT(87) " + point);
        System.out.flush();
        Runtime.getRuntime().halt(87);
    }

    private static void writeSnapshot(Path file, List<String> rows) throws IOException {
        Files.writeString(file, serialize(rows), StandardOpenOption.CREATE_NEW);
    }

    private static List<String> snapshot(Path directory) throws IOException {
        List<String> rows = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new AssertionError("Unexpected canonical file entry");
                rows.add(path.getFileName() + " " + ActiveTimelineStore.sha256(Files.readAllBytes(path)));
            }
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    private static String serialize(List<String> rows) {
        return String.join("\n", rows) + "\n";
    }

    private static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static void syncSave(java.io.File directory) throws IOException { sync(directory.toPath()); }

    private static byte[] bytes(int size, int seed) {
        byte[] result = new byte[size];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + 31 * i);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("prepare|kill|verify DIR POINT");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "prepare": prepare(root); break;
            case "kill": kill(root, args[2]); break;
            case "verify": verify(root, args[2]); break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}
