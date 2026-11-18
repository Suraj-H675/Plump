#!/usr/bin/env python3
"""Disposable P4-E1 pinned melonDS virtual-SD mount/boot audit.

This is a host-only, deliberately NON-PRODUCTION DLDI option override. It does
not assert that a homebrew program opened or wrote a file: the pinned core may
itself inject the boot ROM into the FAT image before any game-level I/O.
"""

import ctypes as ct
import hashlib
import json
import os
from pathlib import Path
import resource
import sys

ROOT = Path(__file__).resolve().parents[2]
HOST_CORE = Path(os.environ.get(
    "PLUMP_HOST_CORE", str(ROOT / "app/build/p3-melonds-host/src/libretro/melondsds_libretro.so")))
CORE_SHA = os.environ.get(
    "PLUMP_HOST_CORE_SHA256", "e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616")
E1B_ROM_ROOT = Path(os.environ.get(
    "PLUMP_E1B_ROM_ROOT", str(ROOT / "app/build/p4-e1-b")))
GAMES = {
    "upstream-memory": (
        ROOT / "app/build/p3-melonds-source/test/nds/tests_sys_mem_regions.nds",
        "9d7f86224faec8da2b22b103cb2ff9a84c9120cc33b5cc43498d108924b5813f"),
    "cc0-input": (
        ROOT / "app/build/p3-input-diagnostic/p3_input_diagnostic.nds",
        "109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d"),
    "plump-alpha": (
        E1B_ROM_ROOT / "rom-alpha/plump_e1b_alpha.nds",
        "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588"),
    "plump-beta": (
        E1B_ROM_ROOT / "rom-beta/plump_e1b_beta.nds",
        "e01a1934e49732c6266bef6b51e64ae74ae5d0d5a426d802cf311f29854823e5"),
}
WRITER_GAMES = {"plump-alpha", "plump-beta"}
IMAGE_LEN = 128 * 1024 * 1024
MAX_PER_FILE = 160 * 1024 * 1024


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


class GameInfo(ct.Structure):
    _fields_ = [("path", ct.c_char_p), ("data", ct.c_void_p),
                ("size", ct.c_size_t), ("meta", ct.c_char_p)]


class Variable(ct.Structure):
    _fields_ = [("key", ct.c_char_p), ("value", ct.c_char_p)]


class DldiStatusV1(ct.Structure):
    _fields_ = [
        ("version", ct.c_uint32), ("state", ct.c_uint32),
        ("first_error", ct.c_uint32), ("last_error", ct.c_uint32),
        ("last_fat_result", ct.c_uint32),
        ("image_bytes", ct.c_uint64), ("sectors_read", ct.c_uint64),
        ("sectors_written", ct.c_uint64), ("read_failures", ct.c_uint64),
        ("write_failures", ct.c_uint64), ("dldi_read_calls", ct.c_uint64),
        ("dldi_write_calls", ct.c_uint64), ("dldi_read_failures", ct.c_uint64),
        ("dldi_write_failures", ct.c_uint64),
        ("read_only", ct.c_uint32), ("folder_sync", ct.c_uint32),
        ("dirty", ct.c_uint32), ("flush_attempted", ct.c_uint32),
        ("flush_succeeded", ct.c_uint32), ("close_attempted", ct.c_uint32),
        ("close_succeeded", ct.c_uint32),
    ]


def read_dldi_status(core):
    function = getattr(core, "melondsds_dldi_status_v1", None)
    if function is None:
        return None
    function.argtypes = [ct.POINTER(DldiStatusV1)]
    function.restype = ct.c_bool
    status = DldiStatusV1()
    if not function(ct.byref(status)):
        raise AssertionError("Pinned core's DLDI status API rejected the snapshot")
    return {name: getattr(status, name) for name, _ in status._fields_}


Environment = ct.CFUNCTYPE(ct.c_bool, ct.c_uint, ct.c_void_p)
Video = ct.CFUNCTYPE(None, ct.c_void_p, ct.c_uint, ct.c_uint, ct.c_size_t)
Audio = ct.CFUNCTYPE(None, ct.c_int16, ct.c_int16)
AudioBatch = ct.CFUNCTYPE(ct.c_size_t, ct.POINTER(ct.c_int16), ct.c_size_t)
Poll = ct.CFUNCTYPE(None)
Input = ct.CFUNCTYPE(ct.c_int16, ct.c_uint, ct.c_uint, ct.c_uint, ct.c_uint)


def run_once(run_root, game_key, frames, readonly=False, hard_stop=False):
    root = run_root.resolve(strict=True)
    allowed = (ROOT / "app/build").resolve(strict=True)
    if not root.is_relative_to(allowed) or root == allowed:
        raise ValueError("E1 experiments must use a dedicated ignored app/build directory")
    if root.is_symlink():
        raise ValueError("E1 experiment root must not be a symlink")
    if not 1 <= frames <= 360:
        raise ValueError("Unbounded or empty frame count")
    rom_path, expected_rom_sha = GAMES[game_key]
    if game_key in WRITER_GAMES:
        writer_root = (ROOT / "app/build/p4-e1-b").resolve(strict=True)
        actual_rom = rom_path.resolve(strict=True)
        if (not actual_rom.is_relative_to(writer_root) or
                rom_path.is_symlink() or actual_rom.suffix != ".nds"):
            raise ValueError("E1-B diagnostic ROM must be a nonsymlink ignored fixture")
    if not rom_path.is_file() or digest(rom_path) != expected_rom_sha:
        raise ValueError("Licensed disposable ROM has unknown bytes")
    if digest(HOST_CORE) != CORE_SHA:
        raise ValueError("Pinned melonDS host core digest mismatch")

    # No 4 GiB default; the caller must provide an exact bounded FAT volume.
    save = root / "save"
    system = root / "system"
    target = save / "melonDS DS" / "dldi_sd_card.bin"
    index = target.with_suffix(target.suffix + ".idx")
    allow_missing_image = os.environ.get("PLUMP_E2_ALLOW_MISSING_IMAGE") == "1"
    if not save.is_dir() or not system.is_dir() or target.is_symlink() or index.is_symlink():
        raise ValueError("Missing disposable test directories or a symlinked image/sidecar")
    if target.is_file():
        if target.stat().st_size != IMAGE_LEN:
            raise ValueError("DLDI volume must be exactly 128 MiB")
        saved_image_digest = digest(target)
    elif allow_missing_image:
        saved_image_digest = None
    else:
        raise ValueError("Missing preformatted test image")

    # Keep each core-generated file bounded even if its fallback formatting path
    # unexpectedly expands a corrupted image. Never allocate default 4 GiB.
    soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    resource.setrlimit(resource.RLIMIT_FSIZE, (min(soft, MAX_PER_FILE) if soft > 0 else MAX_PER_FILE,
                                             hard))

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
        b"melonds_homebrew_readonly": b"enabled" if readonly else b"disabled",
        b"melonds_homebrew_sync_sdcard_to_host": b"disabled",
        b"melonds_dsi_sdcard": b"disabled",
        b"melonds_dsi_sdcard_sync_sdcard_to_host": b"disabled",
    }
    paths = {9: os.fsencode(system), 30: os.fsencode(system), 31: os.fsencode(save)}
    seen = set()
    rendered = [0]
    last_frame = [None]

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
        if data and width == 256 and height == 384:
            rendered[0] += 1
            if game_key in WRITER_GAMES:
                last_frame[0] = (width, height, pitch,
                                 ct.string_at(data, pitch * height))

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

    callbacks = [environment, video, audio, audio_batch, poll, input_state]
    core = ct.CDLL(str(HOST_CORE))
    for symbol, fn in zip(("retro_set_environment", "retro_set_video_refresh",
                           "retro_set_audio_sample", "retro_set_audio_sample_batch",
                           "retro_set_input_poll", "retro_set_input_state"), callbacks):
        setter = getattr(core, symbol)
        setter.argtypes = [type(fn)]
        setter(fn)
    core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
    core.retro_load_game.restype = ct.c_bool
    core.retro_run.argtypes = []
    rom_data = ct.create_string_buffer(rom_path.read_bytes())
    game = GameInfo(os.fsencode(rom_path), ct.cast(rom_data, ct.c_void_p),
                    len(rom_data) - 1, None)
    core.retro_init()
    loaded = False
    dldi_status_before_close = None
    dldi_close_returned = None
    dldi_status_after_close = None
    try:
        loaded = bool(core.retro_load_game(ct.byref(game)))
        if not loaded:
            raise AssertionError("Pinned melonDS refused the verified original DS homebrew")
        for _ in range(frames):
            core.retro_run()
        if hard_stop:
            print("P4_E1_HARD_KILL_87_AFTER_CORE_FRAMES_BEFORE_UNLOAD", rendered[0], flush=True)
            os._exit(87)  # Deliberate host-only process cut, not an in-sector interruption.
    finally:
        if loaded:
            dldi_status_before_close = read_dldi_status(core)
            close_function = getattr(core, "melondsds_dldi_close_v1", None)
            if close_function is not None:
                close_function.argtypes = []
                close_function.restype = ct.c_bool
                dldi_close_returned = bool(close_function())
                dldi_status_after_close = read_dldi_status(core)
            elif os.environ.get("PLUMP_E2_REQUIRE_STATUS") == "1":
                raise AssertionError("E2 core status/close API is missing")
            core.retro_unload_game()
        core.retro_deinit()

    image_exists_after = target.is_file() and not target.is_symlink()
    if saved_image_digest is None:
        if image_exists_after:
            raise AssertionError("Core unexpectedly created a missing production-owned image")
        after_size = 0
    else:
        if not image_exists_after:
            raise AssertionError("Core removed the pre-existing image")
        after_size = target.stat().st_size
        if after_size != IMAGE_LEN:
            raise AssertionError("Image size grew or shrank unexpectedly: " + str(after_size))
    if not {"melonds_homebrew_sdcard", "melonds_homebrew_sync_sdcard_to_host",
            "melonds_homebrew_readonly"}.issubset(seen):
        raise AssertionError("Pinned core never requested E1 experimental DLDI options")
    if game_key in WRITER_GAMES and rendered[0] == 0:
        raise AssertionError("Plump DLDI diagnostic did not render a DS frame")
    if last_frame[0] is not None:
        width, height, pitch, pixels = last_frame[0]
        (root / "last-frame.xrgb8888").write_bytes(pixels)
        (root / "last-frame.json").write_text(json.dumps({
            "pixel_format": "XRGB8888",
            "width": width,
            "height": height,
            "pitch": pitch,
            "bytes": len(pixels),
        }, indent=2, sort_keys=True) + "\n")
    result = {
        "case": game_key,
        "core_sha256": CORE_SHA,
        "rom_sha256": expected_rom_sha,
        "frames_rendered": rendered[0],
        "dldi_enabled_requested": True,
        "host_folder_sync_disabled_requested": True,
        "experiment_readonly_requested": readonly,
        "image_logical_bytes": after_size,
        "image_allocated_bytes": target.stat().st_blocks * 512 if image_exists_after else 0,
        "image_sha256_before": saved_image_digest,
        "image_sha256_after": digest(target) if image_exists_after else None,
        "index_present": index.is_file(),
        "index_bytes": index.stat().st_size if index.is_file() else None,
        "folder_sync_directory_present": (target.parent / "dldi_sd_card").exists(),
        "requested_options": sorted(seen),
        "proof_type": ("pinned-core-homebrew-execution-only; independently inspect "
                       "PLUMP record/report files for genuine writer proof"),
        "diagnostic_frame_captured": last_frame[0] is not None,
        "dldi_status_before_close": dldi_status_before_close,
        "dldi_close_returned": dldi_close_returned,
        "dldi_status_after_close": dldi_status_after_close,
    }
    (root / "result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("P4_E1_HOST_BOOT", json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5) or sys.argv[2] not in GAMES or (
        len(sys.argv) == 5 and sys.argv[4] not in ("readonly", "hard-stop")
    ):
        raise SystemExit("usage: verify-p4-e1-virtual-sd.py IGNORED_RUN_DIR upstream-memory|cc0-input|plump-alpha|plump-beta FRAMES [readonly|hard-stop]")
    run_once(Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]),
             len(sys.argv) == 5 and sys.argv[4] == "readonly",
             len(sys.argv) == 5 and sys.argv[4] == "hard-stop")
