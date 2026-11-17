#!/usr/bin/env python3
"""Read-only independent FAT16 record/report inspection for P4-E1-B.

Uses PyFatFS only as a host-side verifier. Positive test records must be
created and updated by the emulated ARM9 diagnostic, never by this script.
The sole host-side mutation is the explicitly requested corrupt-checksum
negative fixture, and it is restricted to disposable app/build working copies.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

from pyfatfs.PyFatFS import PyFatFS

ROOT = Path(__file__).resolve().parents[2]
RUNS = (ROOT / "app/build/p4-e1-b/runs").resolve()
IMAGE_BYTES = 128 * 1024 * 1024
MAGIC = 0x4D554C50
VERSION = 1
IDENTITIES = {
    "ALPHA": (0xA1A10001, "/PLUMP/A001.BIN", "/PLUMP/A001.TXT"),
    "BETA": (0xB2B20002, "/PLUMP/B002.BIN", "/PLUMP/B002.TXT"),
}


def checksum(data: bytes) -> int:
    value = 2166136261
    for byte in data:
        value = ((value ^ byte) * 16777619) & 0xFFFFFFFF
    return value


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_report(raw: bytes) -> dict[str, str]:
    text = raw.decode("ascii")
    result: dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line:
            raise ValueError(f"malformed report line: {line!r}")
        key, value = line.split("=", 1)
        if key in result:
            raise ValueError(f"duplicate report key: {key}")
        result[key] = value
    required = {"PLUMP_E1B_RESULT", "ROM", "ROM_ID", "PREVIOUS", "COUNTER",
                "CHECKSUM", "RECORD_BYTES", "ERROR"}
    if set(result) != required:
        raise ValueError(f"report keys mismatch: {sorted(result)}")
    return result


def inspect(args: argparse.Namespace) -> dict:
    image = args.image.resolve(strict=True)
    if image.is_symlink() or not image.is_relative_to(RUNS):
        raise ValueError("image must be a real file under ignored app/build/p4-e1-b/runs")
    if not image.is_file() or image.stat().st_size != IMAGE_BYTES:
        raise ValueError("working FAT image must be exactly 128 MiB")

    identity, state_path, report_path = IDENTITIES[args.identity]
    fs = PyFatFS(str(image), read_only=not args.corrupt_checksum)
    try:
        root_entries = sorted(fs.listdir("/"))
        has_directory = fs.isdir("/PLUMP")
        entries = sorted(fs.listdir("/PLUMP")) if has_directory else []
        state_exists = fs.isfile(state_path)
        report_exists = fs.isfile(report_path)
        state = b""
        report = b""
        if state_exists:
            with fs.openbin(state_path, "r") as handle:
                state = handle.read()
        if report_exists:
            with fs.openbin(report_path, "r") as handle:
                report = handle.read()

        if args.corrupt_checksum:
            if len(state) != 24:
                raise ValueError("corruption fixture requires an existing 24-byte record")
            words = struct.unpack("<6I", state)
            if (words[0] != MAGIC or words[1] != VERSION or
                    words[2] != identity or words[3] == 0 or
                    words[4] != checksum(state[:16]) or words[5] != (~words[4] & 0xFFFFFFFF)):
                raise ValueError("refusing to corrupt a record that is not currently valid")
            changed = bytearray(state)
            changed[16] ^= 0x01
            with fs.openbin(state_path, "w") as handle:
                handle.write(changed)
            state = bytes(changed)

        record = None
        record_valid = False
        if state_exists:
            if len(state) == 24:
                words = struct.unpack("<6I", state)
                record = {
                    "magic": f"{words[0]:08X}",
                    "version": words[1],
                    "identity": f"{words[2]:08X}",
                    "counter": words[3],
                    "checksum": f"{words[4]:08X}",
                    "checksum_inverse": f"{words[5]:08X}",
                }
                record_valid = (
                    words[0] == MAGIC and words[1] == VERSION and
                    words[2] == identity and words[3] > 0 and
                    words[4] == checksum(state[:16]) and
                    words[5] == (~words[4] & 0xFFFFFFFF)
                )

        parsed_report = parse_report(report) if report_exists else None
        output = {
            "image": str(image),
            "image_bytes": image.stat().st_size,
            "image_allocated_bytes": image.stat().st_blocks * 512,
            "identity": args.identity,
            "identity_value": f"{identity:08X}",
            "root_entries": root_entries,
            "plump_entries": entries,
            "state_path": state_path,
            "state_exists": state_exists,
            "state_bytes": len(state) if state_exists else None,
            "state_sha256": digest(state) if state_exists else None,
            "record_valid": record_valid,
            "record": record,
            "report_path": report_path,
            "report_exists": report_exists,
            "report_sha256": digest(report) if report_exists else None,
            "report": parsed_report,
        }
    finally:
        fs.close()

    if args.expect_missing and (state_exists or report_exists):
        raise ValueError("expected no diagnostic record or report on this volume")
    if args.expect_result:
        if parsed_report is None or parsed_report["PLUMP_E1B_RESULT"] != args.expect_result:
            raise ValueError(f"expected report result {args.expect_result}")
    if args.expect_error and (parsed_report is None or parsed_report["ERROR"] != args.expect_error):
        raise ValueError(f"expected report error {args.expect_error}")
    if args.expect_result == "PASS" and not record_valid:
        raise ValueError("PASS report is not backed by a valid record")
    if parsed_report is not None:
        if parsed_report["ROM"] != args.identity:
            raise ValueError("report ROM label does not match the requested identity")
        if int(parsed_report["ROM_ID"], 16) != identity:
            raise ValueError("report ROM identity does not match the requested identity")
        if int(parsed_report["RECORD_BYTES"]) != 24:
            raise ValueError("report record size is not the expected 24 bytes")
        if args.expect_result == "PASS":
            if record is None or parsed_report["CHECKSUM"] != record["checksum"]:
                raise ValueError("report checksum does not match the FAT record")
            if int(parsed_report["PREVIOUS"]) + 1 != int(parsed_report["COUNTER"]):
                raise ValueError("report does not describe exactly one counter advance")
    if args.expect_result == "ERROR" and args.expect_error == "BAD_RECORD" and record_valid:
        raise ValueError("BAD_RECORD report unexpectedly has a valid saved record")
    if args.expect_previous is not None and (
            parsed_report is None or int(parsed_report["PREVIOUS"]) != args.expect_previous):
        raise ValueError(f"expected previous counter {args.expect_previous}")
    if args.expect_counter is not None and (
            parsed_report is None or int(parsed_report["COUNTER"]) != args.expect_counter):
        raise ValueError(f"expected new counter {args.expect_counter}")
    if args.expect_counter is not None and args.expect_result == "PASS" and (
            record is None or record["counter"] != args.expect_counter):
        raise ValueError(f"FAT record does not contain counter {args.expect_counter}")
    if args.expect_invalid_record and (not state_exists or record_valid):
        raise ValueError("expected a present invalid record")
    if args.expect_valid_record and not record_valid:
        raise ValueError("expected a valid record")

    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("identity", choices=sorted(IDENTITIES))
    parser.add_argument("--expect-result", choices=("PASS", "ERROR"))
    parser.add_argument("--expect-error")
    parser.add_argument("--expect-previous", type=int)
    parser.add_argument("--expect-counter", type=int)
    parser.add_argument("--expect-missing", action="store_true")
    parser.add_argument("--expect-valid-record", action="store_true")
    parser.add_argument("--expect-invalid-record", action="store_true")
    parser.add_argument("--corrupt-checksum", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args), indent=2, sort_keys=True))
        return 0
    except Exception as error:
        print(f"P4_E1B_FAT_INSPECTION_FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
