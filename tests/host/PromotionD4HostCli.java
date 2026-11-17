package dev.plump.emulator;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Host-only, pinned-core D4 test bridge. No personal ROMs or on-device services. */
public final class PromotionD4HostCli {
    private static final String GBA_ROM = "c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5";
    private static final String DS_ROM = "6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70";
    private static final String GBA_CORE = "af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c";
    private static final String DS_CORE = "e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616";

    static CheckpointStore.Identity identity(String console) {
        if ("gba".equals(console)) return new CheckpointStore.Identity("gba", GBA_ROM,
                "mgba-libretro", "d58ea8a86f30598fbf3958585de3d33028535072",
                "none", GBA_CORE, "x86_64", 1);
        if ("ds".equals(console)) return new CheckpointStore.Identity("ds", DS_ROM,
                "melonds-libretro-ds", "16f127dbb587a73371827ce79689c5d237c4b59b",
                "f394adbacb5722ee97c1b37c8064da9a25818310",
                DS_CORE, "x86_64", 1);
        throw new IllegalArgumentException("Unsupported native game console");
    }

    static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    static void syncSave(File directory) throws IOException { sync(directory.toPath()); }
    static String sha(byte[] bytes) { return ActiveTimelineStore.sha256(bytes); }

    static Map<String, String> hashes(Path path) throws IOException {
        Map<String, String> value = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            for (Path p : entries) {
                if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected or unsafe save entry");
                value.put(p.getFileName().toString(), sha(Files.readAllBytes(p)));
            }
        }
        if (value.size() != 3) throw new IOException("Expected PLSV 0/1 and receipt exactly");
        return value;
    }

    static final class Stores {
        final Path root;
        final CheckpointStore.Identity id;
        final GameSaveStore canonical;
        final CheckpointStore checkpoints;
        final HistoricalBranchStore branches;
        final ActiveTimelineStore timeline;
        final NativeSavePromotionStore.NativeSaveMode mode;

        Stores(Path root, String console, NativeSavePromotionStore.FaultInjector promotionFault,
               GameSaveStore.FailureInjector saveFault) throws IOException {
            this.root = root;
            id = identity(console);
            canonical = new GameSaveStore(root.resolve("canonical-plsv").toFile(), PromotionD4HostCli::syncSave, saveFault);
            checkpoints = new CheckpointStore(root.resolve("checkpoints"),
                    PromotionD4HostCli::sync, ignored -> Long.MAX_VALUE, ignored -> { });
            branches = new HistoricalBranchStore(root.resolve("historical-branches"),
                    PromotionD4HostCli::sync, ignored -> Long.MAX_VALUE,
                    PromotionD4HostCli::syncSave, ignored -> { });
            timeline = new ActiveTimelineStore(root.resolve("active-timelines"),
                    PromotionD4HostCli::sync, ActiveTimelineStore.NO_FAULTS, promotionFault);
            mode = console.equals("gba") ? NativeSavePromotionStore.NativeSaveMode.GBA_CARTRIDGE_SRAM_32K :
                    NativeSavePromotionStore.NativeSaveMode.DS_CARTRIDGE_EEPROM_8K;
        }
        HistoricalBranchStore.Branch branch() throws IOException {
            return branches.open(Files.readString(root.resolve("branch-id.txt")).trim(), id);
        }
        String transaction() throws IOException { return Files.readString(root.resolve("transaction-id.txt")).trim(); }
        int nativeLength() { return mode == NativeSavePromotionStore.NativeSaveMode.GBA_CARTRIDGE_SRAM_32K ? 32768 : 8192; }
    }

    static final class Host implements EmulatorSessionOwner.Host {
        final Stores stores;
        Host(Stores stores) { this.stores = stores; }
        @Override public boolean initializeCore() { return true; }
        @Override public boolean stopAndSave() { return true; }
        @Override public boolean startLoops(long token) { return true; }
        @Override public void unloadGame() { }
        @Override public void shutdownCore() { }
        @Override public CheckpointStore.Identity checkpointIdentity() { return stores.id; }
        @Override public ActiveTimelineStore activeTimelineStore() { return stores.timeline; }
        @Override public HistoricalBranchStore historicalBranchStore() { return stores.branches; }
        @Override public GameSaveStore canonicalGameSaveStore() { return stores.canonical; }
        @Override public NativeSavePromotionStore.NativeSaveMode nativeSavePromotionMode() { return stores.mode; }
    }

    static final class Owned implements AutoCloseable {
        final EmulatorSessionOwner owner = new EmulatorSessionOwner();
        final EmulatorSessionOwner.HostToken token;
        Owned(Stores stores) throws IOException {
            token = owner.attach(stores.id.console.equals("gba") ? EmulatorSessionOwner.Console.GBA :
                    EmulatorSessionOwner.Console.DS, new Host(stores),
                    new SessionReceiptStore(stores.root.resolve("receipts")));
            if (!owner.initialize(token)) throw new IOException("Exclusive host initialization failed");
            String branchId = stores.branch().id;
            EmulatorSessionOwner.OpenResult opened = owner.open(token, stores.id.romSha256,
                    new EmulatorSessionOwner.GameLoader() {
                        @Override public String loadAndRestore() { return null; }
                        @Override public String selectedBranchId() { return branchId; }
                        @Override public void discardFailedLoad() { }
                    });
            if (!opened.opened) throw new IOException("Exclusive host branch open refused: " + opened.error);
        }
        @Override public void close() { /* branch host discarded with JVM; no disk write */ }
    }

    static void seed(Stores stores, Path source) throws Exception {
        if (Files.exists(stores.root.resolve("branch-id.txt"))) throw new IOException("Already seeded");
        byte[] state = Files.readAllBytes(source.resolve("s_old.bin"));
        byte[] oldNative = Files.readAllBytes(source.resolve("n_state.bin"));
        byte[] canonicalNative = Files.readAllBytes(source.resolve("n_latest.bin"));
        byte[] branchNative = Files.readAllBytes(source.resolve("n_branch.bin"));
        if (state.length <= 0 || oldNative.length != stores.nativeLength() ||
                canonicalNative.length != stores.nativeLength() || branchNative.length != stores.nativeLength() ||
                Arrays.equals(canonicalNative, branchNative)) throw new IOException("Unverified game input sizes/progress");
        stores.canonical.write(stores.id.romSha256, canonicalNative);
        stores.canonical.write(stores.id.romSha256, canonicalNative);
        Map<String, String> before = hashes(stores.root.resolve("canonical-plsv"));
        Files.writeString(stores.root.resolve("canonical-before.txt"), before.toString(), StandardOpenOption.CREATE_NEW);
        CheckpointStore.Record cp = stores.checkpoints.create(stores.id,
                CheckpointStore.Capture.supported(1, state, oldNative), CheckpointStore.Kind.MANUAL);
        HistoricalBranchStore.Branch branch = stores.branches.create(stores.checkpoints, cp.id, stores.id);
        branch.saves.write(stores.id.romSha256, branchNative);
        stores.timeline.selectCanonical(stores.id, stores.canonical);
        ActiveTimelineStore.SelectionResult active = stores.timeline.selectBranch(stores.id, stores.branches, branch);
        if (!active.committed) throw new IOException("Branch ownership did not become durable");
        Files.writeString(stores.root.resolve("branch-before.txt"), hashes(branch.journalDirectory).toString(),
                StandardOpenOption.CREATE_NEW);
        Files.writeString(stores.root.resolve("branch-id.txt"), branch.id, StandardOpenOption.CREATE_NEW);
        Files.writeString(stores.root.resolve("checkpoint-id.txt"), cp.id, StandardOpenOption.CREATE_NEW);
        System.out.println("PASS D4 SEEDED " + stores.id.console + " canonical1=" + sha(canonicalNative) +
                " branch2=" + sha(branchNative) + " full canonical PLSV=" + before);
    }

    static void assertBranch(Stores s, Path source) throws Exception {
        if (!Files.readString(s.root.resolve("branch-before.txt")).equals(hashes(s.branch().journalDirectory).toString()))
            throw new IOException("D4 original branch PLSV bytes changed");
        String cp = Files.readString(s.root.resolve("checkpoint-id.txt"));
        if (!s.checkpoints.verify(cp, s.id).id.equals(cp)) throw new IOException("Historical source checkpoint changed");
        byte[] expected = Files.readAllBytes(source.resolve("n_branch.bin"));
        if (!Arrays.equals(expected, s.branch().saves.load(s.id.romSha256).bytes))
            throw new IOException("Independent branch progress lost");
    }

    static void assertAnchor(Stores s, Path source) throws Exception {
        Path game = s.root.resolve("active-timelines/native-save-promotions/" + s.id.console + "-" + s.id.romSha256);
        Path tx = game.resolve(s.transaction());
        Path anchor = tx.resolve("anchor");
        if (!Files.isDirectory(anchor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(anchor))
            throw new IOException("Missing immutable rollback anchor");
        Map<String, String> original = new TreeMap<>();
        original.put(s.id.romSha256 + ".0.sav", sha(Files.readAllBytes(anchor.resolve("record0"))));
        original.put(s.id.romSha256 + ".1.sav", sha(Files.readAllBytes(anchor.resolve("record1"))));
        original.put(s.id.romSha256 + ".history", sha(Files.readAllBytes(anchor.resolve("history"))));
        if (!Files.readString(s.root.resolve("canonical-before.txt")).equals(original.toString()))
            throw new IOException("Rollback anchor does not preserve all 3 original PLSV files");
        if (!Arrays.equals(Files.readAllBytes(source.resolve("n_latest.bin")),
                Files.readAllBytes(anchor.resolve("canonical.native"))))
            throw new IOException("Rollback anchor does not retain native counter 1");
        System.out.println("PASS D4 ROLLBACK ANCHOR " + s.id.console + " entire ORIGINAL canonical PLSV intact and native counter1 recoverable");
    }

    static void promote(Stores s) throws Exception {
        try (Owned host = new Owned(s)) {
            NativeSavePromotionStore.Proposal proposal = host.owner.proposeNativeSavePromotion(host.token);
            NativeSavePromotionStore.Approval approved = host.owner.approveNativeSavePromotion(
                    host.token, proposal, proposal.confirmationText);
            Files.writeString(s.root.resolve("transaction-id.txt"), approved.transactionId, StandardOpenOption.CREATE_NEW);
            NativeSavePromotionStore.Result outcome = host.owner.promoteNativeSaveBranch(host.token, approved);
            if (outcome.classification != NativeSavePromotionStore.Classification.COMMITTED)
                throw new IOException("Explicit promotion did not commit");
            if (host.owner.resume(host.token)) throw new IOException("Old branch core remained resumable");
            System.out.println("PASS D4 EXPLICIT PROMOTION " + s.id.console +
                    " new canonical gen=" + outcome.canonicalGeneration + " sha=" + outcome.canonicalSha256);
        }
    }

    static void extract(Stores s, Path source) throws Exception {
        assertBranch(s, source);
        assertAnchor(s, source);
        ActiveTimelineStore.Resolution resolved = s.timeline.resolveCold(true, s.id, s.canonical, s.branches);
        if (resolved.classification != ActiveTimelineStore.Classification.CANONICAL)
            throw new IOException("Promoted canonical progress not selected at cold restart: " + resolved.classification);
        byte[] expected = Files.readAllBytes(source.resolve("n_branch.bin"));
        if (!Arrays.equals(expected, resolved.nativeSave))
            throw new IOException("Cold canonical progress was not promoted branch progress");
        Files.write(s.root.resolve("canonical2.bin"), resolved.nativeSave);
        Files.write(s.root.resolve("anchor1.bin"), Files.readAllBytes(s.root.resolve("active-timelines/native-save-promotions/" +
                s.id.console + "-" + s.id.romSha256 + "/" + s.transaction() + "/anchor/canonical.native")));
        Files.write(s.root.resolve("branch2.bin"), s.branch().saves.load(s.id.romSha256).bytes);
        System.out.println("PASS D4 COLD CANONICAL2 " + s.id.console + " sha=" + sha(resolved.nativeSave) +
                " branch2 untouched; old canonical1 anchor intact");
    }

    static void writeNext(Stores s, Path nativeFile) throws Exception {
        byte[] advanced = Files.readAllBytes(nativeFile);
        if (advanced.length != s.nativeLength()) throw new IOException("Real next game save geometry changed");
        s.timeline.assertWritable(s.id, null, s.canonical);
        s.canonical.write(s.id.romSha256, advanced);
        ActiveTimelineStore.SelectionResult pointer = s.timeline.recordDurableSave(s.id, null, s.canonical, advanced);
        if (!pointer.committed || !pointer.nativeSha256.equals(sha(advanced)))
            throw new IOException("New genuine canonical in-game save was not durably owned");
        System.out.println("PASS D4 NEXT CANONICAL REAL SAVE " + s.id.console + " generation=" +
                pointer.nativeGeneration + " sha=" + pointer.nativeSha256);
    }

    static void extractLatest(Stores s, Path source) throws Exception {
        assertBranch(s, source);
        assertAnchor(s, source);
        ActiveTimelineStore.Resolution resolved = s.timeline.resolveCold(true, s.id, s.canonical, s.branches);
        if (resolved.classification != ActiveTimelineStore.Classification.CANONICAL ||
                !Arrays.equals(resolved.nativeSave, Files.readAllBytes(s.root.resolve("canonical3.bin"))))
            throw new IOException("Latest canonical progress did not survive cold process");
        Files.write(s.root.resolve("last-cold.bin"), resolved.nativeSave);
        System.out.println("PASS D4 COLD CANONICAL3 " + s.id.console + " sha=" + sha(resolved.nativeSave));
    }

    static void rollback(Stores s, Path source) throws Exception {
        assertBranch(s, source); assertAnchor(s, source);
        NativeSavePromotionStore.Result result = s.timeline.promotions().rollback(s.id, s.canonical,
                s.branches, s.transaction());
        if (result.classification != NativeSavePromotionStore.Classification.ROLLED_BACK ||
                !Arrays.equals(s.canonical.load(s.id.romSha256).bytes,
                        Files.readAllBytes(source.resolve("n_latest.bin"))))
            throw new IOException("Explicit rollback did not recover old canonical native progress");
        System.out.println("PASS D4 EXPLICIT ROLLBACK " + s.id.console + " generation=" + result.canonicalGeneration);
    }

    static void verifyRollback(Stores s, Path source) throws Exception {
        assertBranch(s, source); assertAnchor(s, source);
        ActiveTimelineStore.Resolution recovered = s.timeline.resolveCold(true, s.id, s.canonical, s.branches);
        byte[] original = Files.readAllBytes(source.resolve("n_latest.bin"));
        if (recovered.classification != ActiveTimelineStore.Classification.CANONICAL ||
                !Arrays.equals(recovered.nativeSave, original) ||
                s.timeline.promotions().inspect(s.id).classification != NativeSavePromotionStore.Classification.ROLLED_BACK)
            throw new IOException("Explicitly rolled back canonical save did not survive a fresh process");
        Files.write(s.root.resolve("rollback1.bin"), recovered.nativeSave);
        System.out.println("PASS D4 COLD EXPLICIT ROLLBACK " + s.id.console + " native original sha=" +
                sha(recovered.nativeSave) + "; branch2 still independent and anchor immutable");
    }

    /** A real fresh-JVM decision after a killed transaction; never invoked by cold auto-load. */
    static void recoverInterrupted(Stores s, Path source, boolean finish) throws Exception {
        assertBranch(s, source);
        // The anchor is guaranteed only after the pre-anchor publication boundary;
        // manual finish/rollback is responsible for publishing it if absent.
        NativeSavePromotionStore.Result result = finish ?
                s.timeline.promotions().recoverFinish(s.id, s.canonical, s.branches, s.transaction()) :
                s.timeline.promotions().recoverRollback(s.id, s.canonical, s.branches, s.transaction());
        NativeSavePromotionStore.Classification expected = finish ?
                NativeSavePromotionStore.Classification.COMMITTED :
                NativeSavePromotionStore.Classification.ROLLED_BACK;
        if (result.classification != expected ||
                s.timeline.promotions().inspect(s.id).classification != expected)
            throw new IOException("Explicit post-crash recovery did not durably commit");
        assertAnchor(s, source);
        ActiveTimelineStore.Resolution cold = s.timeline.resolveCold(true, s.id, s.canonical, s.branches);
        byte[] expectedBytes = Files.readAllBytes(source.resolve(finish ? "n_branch.bin" : "n_latest.bin"));
        if (cold.classification != ActiveTimelineStore.Classification.CANONICAL ||
                !Arrays.equals(expectedBytes, cold.nativeSave) ||
                cold.nativeGeneration != result.canonicalGeneration ||
                !sha(cold.nativeSave).equals(result.canonicalSha256))
            throw new IOException("Explicit post-crash choice is not independently cold-recoverable");
        System.out.println("PASS D4 EXPLICIT POST-KILL " + (finish ? "FINISH" : "ROLLBACK") +
                " " + s.id.console + " generation=" + result.canonicalGeneration +
                " SHA-256=" + result.canonicalSha256);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException(
                "seed|promote|extract|write-next|extract-latest|rollback|verify-rollback|recover-finish|recover-rollback ROOT gba|ds [SOURCE_OR_NATIVE]");
        String operation = args[0];
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        String console = args[2];
        Stores stores = new Stores(root, console, NativeSavePromotionStore.NO_FAULTS, ignored -> { });
        Path optional = args.length >= 4 ? Path.of(args[3]).toAbsolutePath().normalize() : null;
        switch (operation) {
            case "seed": seed(stores, require(optional)); break;
            case "promote": promote(stores); break;
            case "extract": extract(stores, require(optional)); break;
            case "write-next": writeNext(stores, require(optional)); break;
            case "extract-latest": extractLatest(stores, require(optional)); break;
            case "rollback": rollback(stores, require(optional)); break;
            case "verify-rollback": verifyRollback(stores, require(optional)); break;
            case "recover-finish": recoverInterrupted(stores, require(optional), true); break;
            case "recover-rollback": recoverInterrupted(stores, require(optional), false); break;
            default: throw new IllegalArgumentException(operation);
        }
    }
    private static Path require(Path path) throws IOException {
        if (path == null) throw new IOException("Missing disposable fixture input path");
        return path;
    }
}
