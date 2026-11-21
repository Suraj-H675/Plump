package dev.plump.emulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-only integrity validator for the one 128 MiB FAT16 geometry approved
 * for the experimental E3 DS DLDI cartridge. No Android fsck binary, repairs,
 * formatting or filesystem writes. This is NOT a universal FAT implementation.
 *
 * Checks both FATs, BPB bounds, each reachable file/directory allocation chain,
 * length, cycles, cross-links and orphaned clusters. Unknown formats fail closed.
 * File contents are separately protected by the E2 SHA-256 generation receipt.
 */
final class VirtualSdFat16Validator implements VirtualSdGenerationStore.ImageValidator {
    static final long IMAGE_BYTES = 128L * 1024 * 1024;
    private static final int SECTOR = 512;
    private static final int MAX_CLUSTERS = 65524;
    private static final int MAX_DEPTH = 12;

    @Override public void validate(Path image) throws IOException {
        if (Files.isSymbolicLink(image) ||
                !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(image) != IMAGE_BYTES)
            throw new IOException("Only a regular 128 MiB FAT16 E3 working image is supported");
        try (FileChannel channel = FileChannel.open(image, StandardOpenOption.READ)) {
            byte[] boot = read(channel, 0, SECTOR);
            if ((boot[0] & 0xff) != 0xeb && (boot[0] & 0xff) != 0xe9)
                throw new IOException("Missing FAT16 boot jump");
            if (u16(boot, 510) != 0xaa55 || u16(boot, 11) != SECTOR)
                throw new IOException("FAT16 boot signature or sector size mismatch");
            int sectorsPerCluster = boot[13] & 0xff;
            int reservedSectors = u16(boot, 14);
            int fats = boot[16] & 0xff;
            int rootEntries = u16(boot, 17);
            int sectorsPerFat = u16(boot, 22);
            int total = u16(boot, 19) != 0 ? u16(boot, 19) : (int)u32(boot, 32);
            int media = boot[21] & 0xff;
            if (sectorsPerCluster == 0 || sectorsPerCluster > 64 ||
                    (sectorsPerCluster & (sectorsPerCluster - 1)) != 0 ||
                    reservedSectors < 1 || fats != 2 || rootEntries < 16 ||
                    rootEntries > 512 || sectorsPerFat < 1 || total != IMAGE_BYTES / SECTOR ||
                    u32(boot, 28) != 0 || media < 0xf0)
                throw new IOException("Unsupported or inconsistent FAT16 geometry");
            if (!"FAT16   ".equals(new String(boot, 54, 8,
                    java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("FAT16 type field mismatch");

            int rootSectors = (rootEntries * 32 + SECTOR - 1) / SECTOR;
            long rootSector = reservedSectors + 2L * sectorsPerFat;
            long dataSector = rootSector + rootSectors;
            long clusters = (total - dataSector) / sectorsPerCluster;
            if (clusters < 4085 || clusters > MAX_CLUSTERS ||
                    clusters + 2 > sectorsPerFat * SECTOR / 2 ||
                    dataSector >= total)
                throw new IOException("FAT16 allocation table is too small or inconsistent");

            byte[] fatFirst = read(channel, reservedSectors * (long)SECTOR,
                    sectorsPerFat * SECTOR);
            byte[] fatSecond = read(channel, (reservedSectors + (long)sectorsPerFat) * SECTOR,
                    sectorsPerFat * SECTOR);
            if (!Arrays.equals(fatFirst, fatSecond))
                throw new IOException("FAT16 redundant allocation tables disagree");
            if ((u16(fatFirst, 0) & 0xff) != media ||
                    u16(fatFirst, 0) < 0xfff0 || u16(fatFirst, 2) < 0xfff8)
                throw new IOException("Invalid FAT16 reserved allocation entries");

            Scan scan = new Scan(channel, fatFirst, (int)clusters,
                    dataSector * SECTOR, sectorsPerCluster * SECTOR);
            byte[] root = read(channel, rootSector * SECTOR, rootSectors * SECTOR);
            scan.directory(root, 0);
            for (int cluster = 2; cluster < clusters + 2; cluster++) {
                int entry = u16(fatFirst, cluster * 2);
                if (entry != 0 && !scan.claimed[cluster])
                    throw new IOException("Unreferenced FAT16 cluster " + cluster);
                if (entry == 0 && scan.claimed[cluster])
                    throw new IOException("Referenced free FAT16 cluster " + cluster);
            }
            if (channel.size() != IMAGE_BYTES)
                throw new IOException("FAT16 image changed while being validated");
        }
    }

    private static final class Scan {
        final FileChannel channel;
        final byte[] fat;
        final boolean[] claimed;
        final int clusters;
        final long dataOffset;
        final int bytesPerCluster;

        Scan(FileChannel channel, byte[] fat, int clusters, long dataOffset,
             int bytesPerCluster) {
            this.channel = channel;
            this.fat = fat;
            this.clusters = clusters;
            claimed = new boolean[clusters + 2];
            this.dataOffset = dataOffset;
            this.bytesPerCluster = bytesPerCluster;
        }

        int[] chain(int initial, long expectedBytes, boolean directory) throws IOException {
            if (initial == 0 && !directory && expectedBytes == 0) return new int[0];
            if (initial < 2 || initial >= clusters + 2)
                throw new IOException("Out-of-range FAT16 first cluster");
            int[] visited = new int[clusters];
            int count = 0;
            int cluster = initial;
            for (;;) {
                if (cluster < 2 || cluster >= clusters + 2 || claimed[cluster])
                    throw new IOException("FAT16 allocation loop or cross-linked chain");
                claimed[cluster] = true;
                visited[count++] = cluster;
                int next = u16(fat, cluster * 2);
                if (next >= 0xfff8) break;
                if (next == 0 || next == 1 || next == 0xfff7 ||
                        next >= 0xfff0 || count >= clusters)
                    throw new IOException("Broken FAT16 cluster chain");
                cluster = next;
            }
            if (!directory && (expectedBytes + bytesPerCluster - 1) / bytesPerCluster != count)
                throw new IOException("FAT16 file allocation does not match its recorded size");
            return Arrays.copyOf(visited, count);
        }

        void directory(byte[] entries, int depth) throws IOException {
            if (depth > MAX_DEPTH)
                throw new IOException("FAT16 directory nesting exceeds diagnostic limit");
            Set<String> names = new HashSet<>();
            for (int offset = 0; offset + 32 <= entries.length; offset += 32) {
                int first = entries[offset] & 0xff;
                if (first == 0) break;
                if (first == 0xe5) continue; // Deleted entry; chain no longer allocated.
                int attrs = entries[offset + 11] & 0xff;
                if (attrs == 0x0f) continue; // Long-file-name metadata, no cluster ownership.
                if ((attrs & 0x08) != 0) continue; // Volume label.
                if ((attrs & 0xc0) != 0)
                    throw new IOException("Invalid FAT16 directory attributes");
                int initial = u16(entries, offset + 26);
                long size = u32(entries, offset + 28);
                String name = new String(entries, offset, 11,
                        java.nio.charset.StandardCharsets.US_ASCII);
                if (!names.add(name))
                    throw new IOException("Duplicate FAT16 directory entry");
                if (name.startsWith(".          ") || name.startsWith("..         "))
                    continue;
                boolean childDirectory = (attrs & 0x10) != 0;
                if (childDirectory && size != 0)
                    throw new IOException("FAT16 subdirectory has nonzero size");
                int[] sectors = chain(initial, size, childDirectory);
                if (childDirectory) {
                    for (int cluster : sectors) {
                        long offsetBytes = dataOffset + (long)(cluster - 2) * bytesPerCluster;
                        directory(read(channel, offsetBytes, bytesPerCluster), depth + 1);
                    }
                }
            }
        }
    }

    private static byte[] read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes, offset + bytes.position());
            if (count <= 0) throw new IOException("Truncated FAT16 image");
        }
        return bytes.array();
    }
    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }
    private static long u32(byte[] bytes, int offset) {
        return (long)u16(bytes, offset) | (long)u16(bytes, offset + 2) << 16;
    }
}
