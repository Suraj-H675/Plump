package dev.plump.emulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Separate-JVM hard-exit probe. This never references emulator/device save paths. */
public final class BranchPublicationProbe {
    private static final String ROM = "a".repeat(64);
    private static final CheckpointStore.Identity ID = new CheckpointStore.Identity("ds", ROM,
            "melonds-libretro-ds", "16f127dbb587a73371827ce79689c5d237c4b59b",
            "f394adbacb5722ee97c1b37c8064da9a25818310", "b".repeat(64), "arm64-v8a", 1);

    private static void sync(Path folder) throws IOException {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static CheckpointStore checkpoints(Path root) {
        return new CheckpointStore(root.resolve("checkpoints"), BranchPublicationProbe::sync,
                path -> Long.MAX_VALUE, point -> { });
    }

    private static HistoricalBranchStore branches(Path root, HistoricalBranchStore.FaultInjector faults) {
        return new HistoricalBranchStore(root.resolve("branches"), BranchPublicationProbe::sync,
                path -> Long.MAX_VALUE, directory -> sync(directory.toPath()), faults);
    }

    private static byte[] data(int count, int seed) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (seed + 19 * i);
        return bytes;
    }

    private static String sha(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path f : files) {
                if (!Files.isRegularFile(f) || Files.isSymbolicLink(f)) throw new AssertionError("Unexpected canonical entry");
                result.put(f.getFileName().toString(), sha(Files.readAllBytes(f)));
            }
        }
        return result;
    }

    private static void prepare(Path root) throws Exception {
        Files.createDirectories(root);
        GameSaveStore canonical = new GameSaveStore(root.resolve("canonical-plsv").toFile(),
                directory -> sync(directory.toPath()));
        canonical.write(ROM, data(8192, 5));
        canonical.write(ROM, data(8192, 87));
        Map<String, String> golden = snapshot(root.resolve("canonical-plsv"));
        if (golden.size() != 3) throw new AssertionError("Expected two valid canonical generations and receipt");
        StringBuilder serialized = new StringBuilder();
        golden.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> serialized.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n'));
        Files.write(root.resolve("canonical-shas.txt"), serialized.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        CheckpointStore.Record record = checkpoints(root).create(ID,
                CheckpointStore.Capture.supported(1, data(2048, 3), data(8192, 31)),
                CheckpointStore.Kind.MANUAL);
        Files.writeString(root.resolve("checkpoint-id.txt"), record.id, StandardOpenOption.CREATE_NEW);
        System.out.println("PASS prepare: canonical two generations + receipt and verified immutable checkpoint");
    }

    private static void kill(Path root, HistoricalBranchStore.FaultPoint point) throws Exception {
        String id = Files.readString(root.resolve("checkpoint-id.txt"));
        branches(root, candidate -> {
            if (candidate == point) Runtime.getRuntime().halt(87);
        }).create(checkpoints(root), id, ID);
        throw new AssertionError("Publication unexpectedly returned without process death");
    }

    private static void verify(Path root, HistoricalBranchStore.FaultPoint point) throws Exception {
        String golden = Files.readString(root.resolve("canonical-shas.txt"));
        StringBuilder actual = new StringBuilder();
        snapshot(root.resolve("canonical-plsv")).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> actual.append(e.getKey()).append(' ').append(e.getValue()).append('\n'));
        if (!golden.equals(actual.toString())) throw new AssertionError("Hard exit modified canonical PLSV history");
        String checkpointId = Files.readString(root.resolve("checkpoint-id.txt"));
        if (!checkpointId.equals(checkpoints(root).verify(checkpointId, ID).id))
            throw new AssertionError("Source checkpoint was damaged");

        int published = 0;
        Path folder = root.resolve("branches");
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, "branch-*")) {
                for (Path publishedPath : entries) {
                    String branchId = publishedPath.getFileName().toString().substring("branch-".length());
                    HistoricalBranchStore.Branch branch = branches(root, p -> { }).open(branchId, ID);
                    if (!checkpointId.equals(branch.checkpointId)) throw new AssertionError("Wrong checkpoint branch");
                    if (!java.util.Arrays.equals(branch.nativeSave(), branch.saves.load(ROM).bytes))
                        throw new AssertionError("Half-published branch native save");
                    ++published;
                }
            }
        }
        boolean afterRename = point == HistoricalBranchStore.FaultPoint.AFTER_PUBLISH ||
                point == HistoricalBranchStore.FaultPoint.AFTER_READBACK;
        if (published != (afterRename ? 1 : 0))
            throw new AssertionError("Expected " + (afterRename ? 1 : 0) + " verified branch(es), found " + published);
        System.out.println("PASS hard JVM exit " + point + ": canonical three files byte-identical; " +
                published + " complete published branch(es); original checkpoint intact");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) throw new IllegalArgumentException("prepare|kill|verify DIR [faultPoint]");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "prepare": prepare(root); break;
            case "kill": kill(root, HistoricalBranchStore.FaultPoint.valueOf(args[2])); break;
            case "verify": verify(root, HistoricalBranchStore.FaultPoint.valueOf(args[2])); break;
            default: throw new IllegalArgumentException(args[0]);
        }
    }
}
