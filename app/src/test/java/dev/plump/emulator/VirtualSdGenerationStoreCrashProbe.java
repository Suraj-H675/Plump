package dev.plump.emulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class VirtualSdGenerationStoreCrashProbe {
    private static final long IMAGE_BYTES = 1024 * 1024;
    private static final CheckpointStore.Identity IDENTITY = new CheckpointStore.Identity("ds",
            repeat('a', 64), "melondsds-libretro", repeat('c', 40), repeat('d', 40),
            repeat('b', 64), "x86_64", 1);

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String candidateId = args[1];
        VirtualSdGenerationStore store = new VirtualSdGenerationStore(root, IDENTITY,
                "canonical", IMAGE_BYTES, VirtualSdGenerationStoreCrashProbe::syncDirectory,
                VirtualSdGenerationStoreCrashProbe::validateImage, point -> {
                    if (point == VirtualSdGenerationStore.FaultPoint.AFTER_HEAD_RENAME)
                        throw new IOException("simulated owner death after HEAD rename");
                });
        VirtualSdGenerationStore.WorkingSession session = store.openSession();
        try (FileChannel image = FileChannel.open(session.imageFile, StandardOpenOption.WRITE)) {
            image.position(8192);
            image.write(ByteBuffer.wrap(new byte[]{77}));
            image.force(true);
        }
        VirtualSdGenerationStore.CoreCloseReceipt receipt = new VirtualSdGenerationStore.CoreCloseReceipt(
                1, 4, 0, 0, IMAGE_BYTES, 0, 0, 0, 0, false, false,
                true, true, true, true, true, true);
        VirtualSdGenerationStore.TransactionResult result = store.commitTransaction(
                session, receipt, "none", candidateId, VirtualSdGenerationStore.NO_CANCELLATION);
        if (result.outcome != VirtualSdGenerationStore.Outcome.RECOVERY_REQUIRED ||
                result.cleanupResolved || !result.headRenameAttempted || result.directorySyncConfirmed ||
                !Files.exists(root.resolve("PENDING_TRANSACTION.vsd")))
            Runtime.getRuntime().halt(9);
        Runtime.getRuntime().halt(0);
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void validateImage(Path path) throws IOException {
        if (Files.size(path) != IMAGE_BYTES) throw new IOException("invalid image size");
        byte[] header = new byte[8];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(header);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
        }
        if (!java.util.Arrays.equals(header,
                "PLUMPFAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IOException("invalid test image");
    }

    private static String repeat(char value, int length) {
        char[] values = new char[length];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
