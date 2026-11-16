package dev.plump.emulator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class HostCheckpointProbe {
    private HostCheckpointProbe() { }

    private static native byte[][] captureGba();
    private static native byte[][] captureDs();

    public static void runGba(String root, String romSha256, String hostBinarySha256) throws Exception {
        run(root, romSha256, hostBinarySha256, true);
    }

    public static void runDs(String root, String romSha256, String hostBinarySha256) throws Exception {
        run(root, romSha256, hostBinarySha256, false);
    }

    private static void run(String root, String romSha256, String hostBinarySha256, boolean gba) throws Exception {
        Path directory = Path.of(root);
        CheckpointStore store = new CheckpointStore(directory.resolve("checkpoints"));
        CheckpointStore.Identity identity = gba
                ? new CheckpointStore.Identity("gba", romSha256, "mgba-libretro",
                    "d58ea8a86f30598fbf3958585de3d33028535072", "none", hostBinarySha256, "x86_64", 1)
                : new CheckpointStore.Identity("ds", romSha256, "melonds-libretro-ds",
                    "16f127dbb587a73371827ce79689c5d237c4b59b", "f394adbacb5722ee97c1b37c8064da9a25818310",
                    hostBinarySha256, "x86_64", 1);
        EmulatorSessionOwner owner = new EmulatorSessionOwner();
        EmulatorSessionOwner.Console console = gba
                ? EmulatorSessionOwner.Console.GBA : EmulatorSessionOwner.Console.DS;
        EmulatorSessionOwner.Host host = new EmulatorSessionOwner.Host() {
            @Override public boolean initializeCore() { return true; }
            @Override public boolean stopAndSave() { return true; }
            @Override public boolean startLoops(long runToken) { return true; }
            @Override public void unloadGame() { }
            @Override public void shutdownCore() { }
            @Override public CheckpointStore checkpointStore() { return store; }
            @Override public CheckpointStore.Identity checkpointIdentity() { return identity; }
            @Override public CheckpointStore.Capture captureCheckpoint() {
                byte[][] arrays = gba ? captureGba() : captureDs();
                if (arrays == null || arrays.length != 2 || arrays[0] == null || arrays[1] == null)
                    return CheckpointStore.Capture.unsupported(1, "Native host capture rejected the core state");
                return CheckpointStore.Capture.supported(1, arrays[0], arrays[1]);
            }
        };
        EmulatorSessionOwner.HostToken token = owner.attach(console, host,
                new SessionReceiptStore(directory.resolve("receipts")));
        if (!owner.initialize(token)) throw new AssertionError("Host initialization failed");
        EmulatorSessionOwner.OpenResult opened = owner.open(token, romSha256, new EmulatorSessionOwner.GameLoader() {
            @Override public String loadAndRestore() { return null; }
            @Override public void discardFailedLoad() { }
        });
        if (!opened.opened) throw new AssertionError("Session open failed: " + opened.error);
        if (!owner.resume(token)) throw new AssertionError("Session resume failed");
        try {
            owner.createManualCheckpoint(token);
            throw new AssertionError("Manual capture accepted a running session");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("paused")) throw expected;
        }
        if (!owner.pause(token)) throw new AssertionError("Paused checkpoint boundary failed");
        CheckpointStore.Record firstManual = owner.createManualCheckpoint(token);
        CheckpointStore.Record secondManual = owner.createManualCheckpoint(token);
        if (firstManual.kind != CheckpointStore.Kind.MANUAL || secondManual.kind != CheckpointStore.Kind.MANUAL)
            throw new AssertionError("Manual checkpoint kind was not preserved");
        List<CheckpointStore.Record> records = store.listValid(identity);
        int autos = 0;
        int manuals = 0;
        for (CheckpointStore.Record record : records) {
            store.verify(record.id, identity);
            if (!"canonical".equals(record.branchId) || record.sessionRunToken <= 0)
                throw new AssertionError("Real owner capture lost canonical branch or run-token metadata");
            if (record.kind == CheckpointStore.Kind.AUTO) autos++;
            else manuals++;
        }
        if (autos != 1 || manuals != 2) throw new AssertionError("Expected one real auto and two manual checkpoints");
        long expectedState = gba ? 430144 : 19228045;
        long expectedNative = gba ? 32768 : 8192;
        if (firstManual.machineLength != expectedState || firstManual.nativeLength != expectedNative)
            throw new AssertionError("Real core checkpoint lengths differ from pinned fixture evidence");
        System.out.printf("PASS owner/store integration: %s actual core auto=1 manual=2 state=%d native=%d%n",
                gba ? "mGBA" : "melonDS", firstManual.machineLength, firstManual.nativeLength);
    }
}
