#!/usr/bin/env python3
"""Convert the E1-B host harness's captured XRGB8888 frame to PNG."""

import argparse
import json
from pathlib import Path
import struct
import zlib


def chunk(kind: bytes, data: bytes) -> bytes:
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    metadata = json.loads(args.metadata.read_text())
    if metadata.get("pixel_format") != "XRGB8888":
        raise SystemExit("unexpected frame pixel format")
    width, height, pitch = (int(metadata[k]) for k in ("width", "height", "pitch"))
    raw = args.raw.read_bytes()
    if len(raw) != pitch * height or pitch < width * 4:
        raise SystemExit("captured frame size/pitch mismatch")

    scanlines = bytearray()
    for y in range(height):
        row = raw[y * pitch:y * pitch + width * 4]
        scanlines.append(0)  # PNG filter: None
        for x in range(0, len(row), 4):
            # Libretro XRGB8888 is 0x00RRGGBB in a little-endian host buffer.
            scanlines.extend((row[x + 2], row[x + 1], row[x]))

    png = bytearray(b"\x89PNG\r\n\x1a\n")
    png.extend(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
    png.extend(chunk(b"IDAT", zlib.compress(bytes(scanlines), 9)))
    png.extend(chunk(b"IEND", b""))
    args.output.write_bytes(png)
    print(f"{args.output}: {width}x{height} PNG, {len(png)} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
