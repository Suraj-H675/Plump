package dev.plump.emulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Read-only E3 fixture record verifier; no host-side game-save writes. */
final class E3DiagnosticFatRecord {
    static final int ROM_ID = 0xA1A10001;
    static final String ROM_SHA256 =
            "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588";
    static final class Record {
        final long counter;
        final long previous;
        final boolean present;
        Record(long counter, long previous, boolean present) {
            this.counter = counter; this.previous = previous; this.present = present;
        }
    }

    private E3DiagnosticFatRecord() { }

    static Record read(Path image, boolean requireReport) throws IOException {
        new VirtualSdFat16Validator().validate(image);
        try (FileChannel channel = FileChannel.open(image, StandardOpenOption.READ)) {
            byte[] boot = readBytes(channel, 0, 512);
            int clustersPerSector = boot[13] & 0xff;
            int reserved = u16(boot, 14);
            int fatSectors = u16(boot, 22);
            int rootCount = u16(boot, 17);
            long root = (reserved + fatSectors * 2L) * 512;
            long data = root + ((rootCount * 32 + 511) / 512) * 512L;
            int clusterBytes = clustersPerSector * 512;
            byte[] fat = readBytes(channel, reserved * 512L, fatSectors * 512);
            byte[] rootDirectory = readBytes(channel, root, rootCount * 32);
            int folder = entryCluster(rootDirectory, "PLUMP      ");
            if (folder < 2) {
                if (requireReport) throw new IOException("E3 game did not create PLUMP FAT folder");
                return new Record(0, 0, false);
            }
            byte[] directory = directory(channel, fat, data, clusterBytes, folder);
            int state = entryCluster(directory, "A001    BIN");
            int report = entryCluster(directory, "A001    TXT");
            if (state < 2 || report < 2) throw new IOException("E3 diagnostic record/report missing");
            byte[] stateBytes = file(channel, fat, data, clusterBytes, state, entrySize(directory, "A001    BIN"));
            if (stateBytes.length != 24) throw new IOException("E3 diagnostic record length mismatch");
            ByteBuffer record = ByteBuffer.wrap(stateBytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = record.getInt(), version = record.getInt(), identity = record.getInt();
            long count = Integer.toUnsignedLong(record.getInt());
            long checksum = Integer.toUnsignedLong(record.getInt());
            long complement = Integer.toUnsignedLong(record.getInt());
            long fnv = 0x811c9dc5L;
            for (int n = 0; n < 16; n++) {
                fnv = ((fnv ^ (stateBytes[n] & 0xff)) * 0x01000193L) & 0xffffffffL;
            }
            if (magic != 0x4d554c50 || version != 1 || identity != ROM_ID ||
                    count == 0 || checksum != fnv || complement != ((~fnv) & 0xffffffffL))
                throw new IOException("E3 game-created record magic/identity/checksum invalid");
            if (!requireReport) return new Record(count, count - 1, true);
            byte[] reportBytes = file(channel, fat, data, clusterBytes, report, entrySize(directory, "A001    TXT"));
            if (reportBytes.length > 256) throw new IOException("Oversized E3 report");
            String text = new String(reportBytes, StandardCharsets.US_ASCII);
            String expected = "PLUMP_E1B_RESULT=PASS\nROM=ALPHA\nROM_ID=A1A10001\n" +
                    "PREVIOUS=" + (count - 1) + "\nCOUNTER=" + count +
                    "\nCHECKSUM=" + String.format(java.util.Locale.ROOT, "%08X", fnv) +
                    "\nRECORD_BYTES=24\nERROR=NONE\n";
            if (!expected.equals(text)) throw new IOException("E3 in-game close/reopen report mismatches record");
            return new Record(count, count - 1, true);
        }
    }

    private static int entryCluster(byte[] dir, String shortName) throws IOException {
        for (int p = 0; p + 32 <= dir.length; p += 32) {
            int first = dir[p] & 0xff;
            if (first == 0) break;
            if (first == 0xe5 || (dir[p + 11] & 0xff) == 0x0f) continue;
            if (shortName.equals(new String(dir, p, 11, StandardCharsets.US_ASCII)))
                return u16(dir, p + 26);
        }
        return -1;
    }

    private static int entrySize(byte[] dir, String shortName) throws IOException {
        for (int p = 0; p + 32 <= dir.length; p += 32) {
            if ((dir[p] & 0xff) == 0) break;
            if ((dir[p] & 0xff) == 0xe5 || (dir[p + 11] & 0xff) == 0x0f) continue;
            if (shortName.equals(new String(dir, p, 11, StandardCharsets.US_ASCII))) {
                long size = u32(dir, p + 28);
                if (size > 256 || size == 0) throw new IOException("E3 record/report invalid size");
                return (int)size;
            }
        }
        throw new IOException("E3 file length unavailable");
    }

    private static byte[] directory(FileChannel channel, byte[] fat, long data,
                                    int clusterBytes, int first) throws IOException {
        Set<Integer> seen = new HashSet<>();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int cluster = first;
        while (cluster >= 2 && cluster < 65520) {
            if (!seen.add(cluster) || out.size() > 128 * 1024)
                throw new IOException("E3 FAT directory cycle/size limit");
            out.write(readBytes(channel, data + (cluster - 2L) * clusterBytes, clusterBytes),
                    0, clusterBytes);
            int next = u16(fat, cluster * 2);
            if (next >= 0xfff8) return out.toByteArray();
            cluster = next;
        }
        throw new IOException("E3 FAT directory chain ended unexpectedly");
    }

    private static byte[] file(FileChannel channel, byte[] fat, long data,
                               int clusterBytes, int first, int length) throws IOException {
        byte[] output = new byte[length];
        Set<Integer> visited = new HashSet<>();
        int cluster = first, offset = 0;
        while (offset < length) {
            if (cluster < 2 || cluster >= 65520 || !visited.add(cluster))
                throw new IOException("E3 record FAT cluster chain invalid");
            byte[] bytes = readBytes(channel, data + (cluster - 2L) * clusterBytes, clusterBytes);
            int count = Math.min(clusterBytes, length - offset);
            System.arraycopy(bytes, 0, output, offset, count);
            offset += count;
            if (offset != length) cluster = u16(fat, cluster * 2);
        }
        return output;
    }

    private static byte[] readBytes(FileChannel c, long pos, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int n = c.read(buffer, pos + buffer.position());
            if (n <= 0) throw new IOException("Truncated E3 FAT image");
        }
        return buffer.array();
    }
    private static int u16(byte[] bytes, int p) {
        return (bytes[p] & 255) | ((bytes[p + 1] & 255) << 8);
    }
    private static long u32(byte[] bytes, int p) {
        return ((long)u16(bytes, p) | (long)u16(bytes, p + 2) << 16) & 0xffffffffL;
    }
}
