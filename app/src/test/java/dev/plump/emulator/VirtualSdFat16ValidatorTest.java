package dev.plump.emulator;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Sparse 128 MiB FAT16 fixtures; no host fsck executable or game data. */
public class VirtualSdFat16ValidatorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final VirtualSdFat16Validator validator = new VirtualSdFat16Validator();

    private Path fixture() throws Exception {
        Path path = temp.newFile("fat16.img").toPath();
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) {
            f.setLength(VirtualSdFat16Validator.IMAGE_BYTES);
        }
        byte[] boot = new byte[512];
        boot[0] = (byte)0xeb; boot[1] = 0x3c; boot[2] = (byte)0x90;
        le16(boot, 11, 512); boot[13] = 4;
        le16(boot, 14, 4); boot[16] = 2; le16(boot, 17, 512);
        boot[21] = (byte)0xf8; le16(boot, 22, 256);
        le32(boot, 32, 262144);
        byte[] kind = "FAT16   ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(kind, 0, boot, 54, kind.length);
        boot[510] = 0x55; boot[511] = (byte)0xaa;
        put(path, 0, boot);
        fatEntry(path, 0, 0xfff8);
        fatEntry(path, 1, 0xffff);
        return path;
    }

    private static void put(Path path, long offset, byte[] bytes) throws IOException {
        try (FileChannel c = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) c.write(b, offset + b.position());
        }
    }
    private static void le16(byte[] data, int p, int value) {
        data[p] = (byte)value; data[p + 1] = (byte)(value >>> 8);
    }
    private static void le32(byte[] data, int p, int value) {
        le16(data, p, value); le16(data, p + 2, value >>> 16);
    }
    private static void fatEntry(Path path, int cluster, int value) throws IOException {
        byte[] entry = new byte[2];
        le16(entry, 0, value);
        put(path, 4 * 512L + cluster * 2L, entry);
        put(path, (4 + 256) * 512L + cluster * 2L, entry);
    }
    private static void expectRejected(Path path) throws Exception {
        try {
            new VirtualSdFat16Validator().validate(path);
            fail("Invalid virtual image was accepted");
        } catch (IOException expected) { }
    }

    @Test public void acceptsSparseValid128MiBFat16WithoutChangingBytes() throws Exception {
        Path image = fixture();
        validator.validate(image);
        validator.validate(image);
        assertEquals(VirtualSdFat16Validator.IMAGE_BYTES, Files.size(image));
    }

    @Test public void acceptsFileChainWithExactSize() throws Exception {
        Path image = fixture();
        fatEntry(image, 2, 0xffff);
        byte[] entry = new byte[32];
        System.arraycopy("A001    BIN".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, entry, 0, 11);
        entry[11] = 0x20; le16(entry, 26, 2); le32(entry, 28, 24);
        put(image, 516 * 512L, entry);
        put(image, 548 * 512L, new byte[24]);
        validator.validate(image);
    }

    @Test public void refusesCorruptFatReplica() throws Exception {
        Path image = fixture();
        put(image, (4 + 256) * 512L + 4, new byte[]{(byte)0xff, (byte)0xff});
        expectRejected(image);
    }

    @Test public void refusesOrphanClusterEvenWithMatchingFats() throws Exception {
        Path image = fixture();
        fatEntry(image, 4, 0xffff);
        expectRejected(image);
    }

    @Test public void refusesBadBootGeometryWithoutRepair() throws Exception {
        Path image = fixture();
        put(image, 54, "FAT32   ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        expectRejected(image);
    }

    @Test public void refusesTruncatedOrSymlinkedImages() throws Exception {
        Path image = fixture();
        try (RandomAccessFile f = new RandomAccessFile(image.toFile(), "rw")) {
            f.setLength(512);
        }
        expectRejected(image);
        Path link = temp.getRoot().toPath().resolve("fat16-link.img");
        Files.createSymbolicLink(link, image);
        expectRejected(link);
    }

    @Test public void refusesBrokenCrossLinkedFatChain() throws Exception {
        Path image = fixture();
        fatEntry(image, 2, 3);
        fatEntry(image, 3, 2);
        byte[] entry = new byte[32];
        System.arraycopy("A001    BIN".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, entry, 0, 11);
        entry[11] = 0x20; le16(entry, 26, 2); le32(entry, 28, 4096);
        put(image, 516 * 512L, entry);
        expectRejected(image);
    }

    @Test public void e2NativeCloseReceiptAcceptsOnlyStrictZeroErrorState() {
        long[] status = new long[22];
        status[0] = 1; status[1] = 4; status[5] = VirtualSdFat16Validator.IMAGE_BYTES;
        status[16] = 1; status[17] = 1; status[18] = 1;
        status[19] = 1; status[20] = 1; status[21] = 1;
        assertTrue(NativeDs.e3Receipt(status).permitsCommit(VirtualSdFat16Validator.IMAGE_BYTES));
        status[3] = 1;
        assertFalse(NativeDs.e3Receipt(status).permitsCommit(VirtualSdFat16Validator.IMAGE_BYTES));
        status[3] = 0;
        status[15] = 1; // Folder sync must never gain write authority.
        assertFalse(NativeDs.e3Receipt(status).permitsCommit(VirtualSdFat16Validator.IMAGE_BYTES));
        try { NativeDs.e3Receipt(new long[21]); fail("Wrong ABI accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
