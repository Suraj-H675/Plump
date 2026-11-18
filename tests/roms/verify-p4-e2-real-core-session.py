#!/usr/bin/env python3
"""Run one genuine BlocksDS DLDI session against a VirtualSdGenerationStore working tree.

Host-only E2 harness. The caller owns the working image; this script only lets the
pinned experimental melonDS core use that image, requests an explicit close,
and records the native FAT health receipt. It never publishes a generation.
"""

from __future__ import annotations

import ctypes as ct
import hashlib
import json
import os
from pathlib import Path
import resource
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
IMAGE_LEN = 128 * 1024 * 1024
MAX_PER_FILE = 256 * 1024 * 1024
IDENTITY = "ALPHA"
ROM_SHA = "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588"


class GameInfo(ct.Structure):
    _fields_ = [("path", ct.c_char_p), ("data", ct.c_void_p), ("size", ct.c_size_t), ("meta", ct.c_char_p)]


class Variable(ct.Structure):
    _fields_ = [("key", ct.c_char_p), ("value", ct.c_char_p)]


class DldiStatusV1(ct.Structure):
    _fields_ = [
        ("version", ct.c_uint32), ("state", ct.c_uint32),
        ("first_error", ct.c_uint32), ("last_error", ct.c_uint32),
        ("last_fat_result", ct.c_uint32), ("image_bytes", ct.c_uint64),
        ("sectors_read", ct.c_uint64), ("sectors_written", ct.c_uint64),
        ("read_failures", ct.c_uint64), ("write_failures", ct.c_uint64),
        ("dldi_read_calls", ct.c_uint64), ("dldi_write_calls", ct.c_uint64),
        ("dldi_read_failures", ct.c_uint64), ("dldi_write_failures", ct.c_uint64),
        ("read_only", ct.c_uint32), ("folder_sync", ct.c_uint32),
        ("dirty", ct.c_uint32), ("flush_attempted", ct.c_uint32),
        ("flush_succeeded", ct.c_uint32), ("close_attempted", ct.c_uint32),
        ("close_succeeded", ct.c_uint32),
    ]


Environment = ct.CFUNCTYPE(ct.c_bool, ct.c_uint, ct.c_void_p)
Video = ct.CFUNCTYPE(None, ct.c_void_p, ct.c_uint, ct.c_uint, ct.c_size_t)
Audio = ct.CFUNCTYPE(None, ct.c_int16, ct.c_int16)
AudioBatch = ct.CFUNCTYPE(ct.c_size_t, ct.POINTER(ct.c_int16), ct.c_size_t)
Poll = ct.CFUNCTYPE(None)
Input = ct.CFUNCTYPE(ct.c_int16, ct.c_uint, ct.c_uint, ct.c_uint, ct.c_uint)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def status(core) -> dict[str, int]:
    fn = getattr(core, "melondsds_dldi_status_v1")
    fn.argtypes = [ct.POINTER(DldiStatusV1)]
    fn.restype = ct.c_bool
    output = DldiStatusV1()
    if not fn(ct.byref(output)):
        raise AssertionError("melonDS DLDI status snapshot rejected")
    return {name: getattr(output, name) for name, _ in output._fields_}


def main() -> int:
    if len(sys.argv) not in (6, 7) or (len(sys.argv) == 7 and sys.argv[6] != "--hard-stop"):
        raise SystemExit("usage: verify-p4-e2-real-core-session.py SESSION_DIR SYSTEM_DIR ROM CORE RESULT_DIR [--hard-stop]")
    session = Path(sys.argv[1]).resolve()
    system = Path(sys.argv[2]).resolve()
    rom_path = Path(sys.argv[3]).resolve()
    core_path = Path(sys.argv[4]).resolve()
    result_dir = Path(sys.argv[5]).resolve()
    hard_stop = len(sys.argv) == 7

    image = session / "melonDS DS" / "dldi_sd_card.bin"
    if image.is_symlink() or not image.is_file() or image.stat().st_size != IMAGE_LEN:
        raise AssertionError("E2 working image is missing, symlinked, or not 128 MiB")
    if (session / "melonDS DS" / "dldi_sd_card.bin.idx").exists():
        raise AssertionError("E2 working image unexpectedly has an .idx sidecar")
    if not rom_path.is_file() or sha256(rom_path) != ROM_SHA:
        raise AssertionError("E2 real-core fixture ROM identity mismatch")
    if not core_path.is_file():
        raise AssertionError("E2 experimental melonDS core is missing")
    system.mkdir(parents=True, exist_ok=True)
    result_dir.mkdir(parents=True, exist_ok=True)

    soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    resource.setrlimit(resource.RLIMIT_FSIZE,
                       (min(soft, MAX_PER_FILE) if soft > 0 else MAX_PER_FILE, hard))

    options = {
        b"melonds_network_mode": b"disabled",
        b"melonds_console_mode": b"ds",
        b"melonds_boot_mode": b"direct",
        b"melonds_sysfile_mode": b"builtin",
        b"melonds_render_mode": b"software",
        b"melonds_screen_layout1": b"top-bottom",
        b"melonds_number_of_screen_layouts": b"1",
        b"melonds_screen_gap": b"0",
        b"melonds_homebrew_sdcard": b"enabled",
        b"melonds_homebrew_readonly": b"disabled",
        b"melonds_homebrew_sync_sdcard_to_host": b"disabled",
        b"melonds_dsi_sdcard": b"disabled",
        b"melonds_dsi_sdcard_sync_sdcard_to_host": b"disabled",
    }
    paths = {9: os.fsencode(system), 30: os.fsencode(system), 31: os.fsencode(session)}
    seen: set[str] = set()
    frames = 0

    @Environment
    def environment(command, pointer):
        if not pointer:
            return False
        if command == 3:
            ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
            return True
        if command in paths:
            ct.cast(pointer, ct.POINTER(ct.c_char_p))[0] = paths[command]
            return True
        if command == 10:
            return ct.cast(pointer, ct.POINTER(ct.c_int))[0] == 1
        if command == 15:
            var = ct.cast(pointer, ct.POINTER(Variable)).contents
            value = options.get(var.key)
            if value is not None:
                seen.add(var.key.decode("ascii"))
            var.value = value
            return value is not None
        if command == 17:
            ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
            return True
        if command == 52:
            ct.cast(pointer, ct.POINTER(ct.c_uint))[0] = 2
            return True
        return command in (11, 16, 67)

    @Video
    def video(data, width, height, pitch):
        nonlocal frames
        if data and width == 256 and height == 384 and pitch >= 1024:
            frames += 1

    @Audio
    def audio(left, right):
        pass

    @AudioBatch
    def audio_batch(data, count):
        return count

    @Poll
    def poll():
        pass

    @Input
    def input_state(port, device, index, button):
        return 0

    core = ct.CDLL(str(core_path))
    setters = (
        ("retro_set_environment", environment), ("retro_set_video_refresh", video),
        ("retro_set_audio_sample", audio), ("retro_set_audio_sample_batch", audio_batch),
        ("retro_set_input_poll", poll), ("retro_set_input_state", input_state),
    )
    for name, callback in setters:
        setter = getattr(core, name)
        setter.argtypes = [type(callback)]
        setter(callback)
    core.retro_init.argtypes = []
    core.retro_init.restype = None
    core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
    core.retro_load_game.restype = ct.c_bool
    core.retro_run.argtypes = []
    core.retro_run.restype = None
    core.retro_unload_game.argtypes = []
    core.retro_unload_game.restype = None
    core.retro_deinit.argtypes = []
    core.retro_deinit.restype = None

    rom_data = ct.create_string_buffer(rom_path.read_bytes())
    game = GameInfo(os.fsencode(rom_path), ct.cast(rom_data, ct.c_void_p), len(rom_data) - 1, None)
    before = sha256(image)
    loaded = False
    close_returned = False
    after_status = None
    core.retro_init()
    try:
        loaded = bool(core.retro_load_game(ct.byref(game)))
        if not loaded:
            raise AssertionError("pinned melonDS refused the real E2 fixture")
        for _ in range(180):
            core.retro_run()
        if hard_stop:
            os._exit(87)
        before_status = status(core)
        close_fn = getattr(core, "melondsds_dldi_close_v1")
        close_fn.argtypes = []
        close_fn.restype = ct.c_bool
        close_returned = bool(close_fn())
        after_status = status(core)
    finally:
        if loaded:
            core.retro_unload_game()
        core.retro_deinit()

    if not close_returned:
        raise AssertionError(f"DLDI close rejected: {after_status}")
    if not after_status["close_attempted"] or not after_status["close_succeeded"]:
        raise AssertionError(f"DLDI close status is not successful: {after_status}")
    if after_status["first_error"] or after_status["last_error"]:
        raise AssertionError(f"DLDI storage reported an error: {after_status}")
    if after_status["dirty"] and not after_status["flush_succeeded"]:
        raise AssertionError(f"dirty DLDI storage did not flush successfully: {after_status}")
    if after_status["folder_sync"] or after_status["read_only"]:
        raise AssertionError(f"unsafe DLDI mode reached the core: {after_status}")
    if after_status["image_bytes"] != IMAGE_LEN or frames != 180:
        raise AssertionError(f"unexpected real-core run: frames={frames} status={after_status}")

    result = result_dir / "core-session.json"
    payload = {
        "core_sha256": sha256(core_path), "rom_sha256": ROM_SHA,
        "image_sha256_before": before, "image_sha256_after": sha256(image),
        "frames": frames, "options": sorted(seen),
        "dldi_status_before_close": before_status,
        "dldi_close_returned": close_returned,
        "dldi_status_after_close": after_status,
    }
    result.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    (result_dir / "core-session.properties").write_text(
        "version=%d\nstate=%d\nfirst_error=%d\nlast_error=%d\n"
        "image_bytes=%d\nread_failures=%d\nwrite_failures=%d\n"
        "dldi_read_failures=%d\ndldi_write_failures=%d\nread_only=%d\n"
        "folder_sync=%d\ndirty=%d\nflush_attempted=%d\nflush_succeeded=%d\n"
        "close_returned=%d\nclose_attempted=%d\nclose_succeeded=%d\nframes=%d\n" % (
            after_status["version"], after_status["state"], after_status["first_error"],
            after_status["last_error"], after_status["image_bytes"], after_status["read_failures"],
            after_status["write_failures"], after_status["dldi_read_failures"],
            after_status["dldi_write_failures"], after_status["read_only"],
            after_status["folder_sync"], after_status["dirty"], after_status["flush_attempted"],
            after_status["flush_succeeded"], int(close_returned), after_status["close_attempted"],
            after_status["close_succeeded"], frames))
    print("P4_E2_REAL_CORE_SESSION_PASS", result)


if __name__ == "__main__":
    main()
