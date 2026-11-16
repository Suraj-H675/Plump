package dev.plump.emulator;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Real-core D1 bridge: each CLI invocation is a new JVM and resolves the durable PLSV selector. */
public final class ActiveTimelineHostCli {
    private ActiveTimelineHostCli() { }

    private static final class Stores {
        final Path root;
        final String rom;
        final CheckpointStore.Identity identity;
        final GameSaveStore canonical;
        final CheckpointStore checkpoints;
        final HistoricalBranchStore branches;
        final ActiveTimelineStore timelines;

        Stores(Path root, String console, String rom, String coreSha) throws IOException {
            this.root = root.toAbsolutePath().normalize();
            this.rom = rom;
            boolean gba = "gba".equals(console);
            identity = gba
                    ? new CheckpointStore.Identity("gba", rom, "mgba-libretro",
                        "d58ea8a86f30598fbf3958585de3d33028535072", "none", coreSha, "x86_64", 1)
                    : new CheckpointStore.Identity("ds", rom, "melonds-libretro-ds",
                        "16f127dbb587a73371827ce79689c5d237c4b59b",
                        "f394adbacb5722ee97c1b37c8064da9a25818310", coreSha, "x86_64", 1);
            canonical = new GameSaveStore(this.root.resolve("canonical-plsv").toFile(),
                    ActiveTimelineHostCli::syncSaveDirectory);
            checkpoints = new CheckpointStore(this.root.resolve("checkpoints"),
                    ActiveTimelineHostCli::syncDirectory, ignored -> Long.MAX_VALUE, ignored -> { });
            branches = new HistoricalBranchStore(this.root.resolve("historical-branches"),
                    ActiveTimelineHostCli::syncDirectory, ignored -> Long.MAX_VALUE,
                    ActiveTimelineHostCli::syncSaveDirectory, ignored -> { });
            timelines = new ActiveTimelineStore(this.root.resolve("active-timelines"),
                    ActiveTimelineHostCli::syncDirectory);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) throw new IllegalArgumentException(
                "setup|resolve|write ROOT CONSOLE ROM_SHA CORE_SHA [files...]");
        String action = args[0];
        Stores stores = new Stores(Path.of(args[1]), args[2], args[3], args[4]);
        if ("setup".equals(action)) {
            if (args.length != 8) throw new IllegalArgumentException("setup requires machine/native/progressed files");
            CheckpointStore.Record checkpoint = stores.checkpoints.create(stores.identity,
                    CheckpointStore.Capture.supported(1, Files.readAllBytes(Path.of(args[5])),
                            Files.readAllBytes(Path.of(args[6]))), CheckpointStore.Kind.MANUAL);
            HistoricalBranchStore.Branch branch = stores.branches.create(
                    stores.checkpoints, checkpoint.id, stores.identity);
            byte[] progressed = Files.readAllBytes(Path.of(args[7]));
            branch.saves.write(stores.rom, progressed);
            ActiveTimelineStore.SelectionResult selected = stores.timelines.selectBranch(
                    stores.identity, stores.branches, branch);
            if (!selected.committed || selected.nativeGeneration != 2 ||
                    !ActiveTimelineStore.sha256(progressed).equals(selected.nativeSha256))
                throw new AssertionError("Progressed branch selector failed publication readback");
            Files.writeString(stores.root.resolve("active-branch.txt"), branch.id,
                    StandardOpenOption.CREATE_NEW);
            System.out.println("PASS REAL-CORE POINTER SELECTED branch=" + branch.id +
                    " generation=" + selected.nativeGeneration + " native_sha256=" + selected.nativeSha256);
        } else if ("resolve".equals(action)) {
            if (args.length != 6) throw new IllegalArgumentException("resolve requires output file");
            ActiveTimelineStore.Resolution result = stores.timelines.resolveCold(true,
                    stores.identity, stores.canonical, stores.branches);
            if (result.classification != ActiveTimelineStore.Classification.BRANCH_NATIVE_ONLY ||
                    result.nativeSave == null || result.nativeGeneration < 2)
                throw new AssertionError("Cold selector did not resolve latest native-only branch: " +
                        result.classification + " " + result.detail);
            Files.write(Path.of(args[5]), result.nativeSave, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.out.println("PASS FRESH-JVM RESOLVE " + result.classification + " generation=" +
                    result.nativeGeneration + " native_sha256=" + result.nativeSha256 + " branch=" + result.branch.id);
        } else if ("write".equals(action)) {
            if (args.length != 6) throw new IllegalArgumentException("write requires native payload file");
            ActiveTimelineStore.Resolution result = stores.timelines.resolveCold(true,
                    stores.identity, stores.canonical, stores.branches);
            if (!result.mayLoad() || result.branch == null)
                throw new AssertionError("Cannot select a writable active branch: " + result.classification);
            byte[] data = Files.readAllBytes(Path.of(args[5]));
            stores.timelines.assertWritable(stores.identity, result.branch.id, result.branch.saves);
            result.branch.saves.write(stores.rom, data);
            ActiveTimelineStore.SelectionResult pointer = stores.timelines.recordDurableSave(
                    stores.identity, result.branch.id, result.branch.saves, data);
            if (!pointer.committed || pointer.nativeGeneration != result.nativeGeneration + 1 ||
                    !pointer.nativeSha256.equals(ActiveTimelineStore.sha256(data)))
                throw new AssertionError("Branch PLSV write and selector did not agree");
            System.out.println("PASS FRESH-JVM BRANCH WRITE generation=" + pointer.nativeGeneration +
                    " native_sha256=" + pointer.nativeSha256);
        } else throw new IllegalArgumentException("Unknown action " + action);
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static void syncSaveDirectory(File directory) throws IOException { syncDirectory(directory.toPath()); }
}
