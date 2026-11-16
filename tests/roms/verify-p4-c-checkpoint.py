"""Pinned real-core serializer audit. The only unserialize call runs in a disposable child process."""

import ctypes as ct
import gc
import hashlib
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import sys
import time


class GameInfo(ct.Structure):
    _fields_ = [('path', ct.c_char_p), ('data', ct.c_void_p),
                ('size', ct.c_size_t), ('meta', ct.c_char_p)]


class Variable(ct.Structure):
    _fields_ = [('key', ct.c_char_p), ('value', ct.c_char_p)]


Environment = ct.CFUNCTYPE(ct.c_bool, ct.c_uint, ct.c_void_p)
Video = ct.CFUNCTYPE(None, ct.c_void_p, ct.c_uint, ct.c_uint, ct.c_size_t)
Audio = ct.CFUNCTYPE(None, ct.c_int16, ct.c_int16)
AudioBatch = ct.CFUNCTYPE(ct.c_size_t, ct.POINTER(ct.c_int16), ct.c_size_t)
Poll = ct.CFUNCTYPE(None)
Input = ct.CFUNCTYPE(ct.c_int16, ct.c_uint, ct.c_uint, ct.c_uint, ct.c_uint)

PINS = {
    'gba': {
        'core_sha256': 'af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c',
        'core_revision': 'd58ea8a86f30598fbf3958585de3d33028535072',
        'frontend_revision': 'none',
        'rom_sha256': 'a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c',
        'sram_size': 32768,
    },
    'ds': {
        'core_sha256': 'e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616',
        'core_revision': '16f127dbb587a73371827ce79689c5d237c4b59b',
        'frontend_revision': 'f394adbacb5722ee97c1b37c8064da9a25818310',
        'rom_sha256': '6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70',
        'sram_size': 8192,
    },
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def rss_bytes():
    for line in Path('/proc/self/status').read_text().splitlines():
        if line.startswith('VmRSS:'):
            return int(line.split()[1]) * 1024
    return 0


def load_core(console, core_path, rom_path, work):
    rom_bytes = Path(rom_path).read_bytes()
    pin = PINS[console]
    assert sha(rom_bytes) == pin['rom_sha256'], 'Fixture hash does not match the licensed synthetic test pin'
    assert sha(Path(core_path).read_bytes()) == pin['core_sha256'], 'Host core binary hash differs from audited pin'
    rom = ct.create_string_buffer(rom_bytes)
    core = ct.CDLL(core_path)
    system = work / 'system'
    save_dir = work / 'save'
    system.mkdir(parents=True, exist_ok=True)
    save_dir.mkdir(parents=True, exist_ok=True)
    current_frame = [0]
    frames = [0]
    audio = [0]
    if console == 'ds':
        options = {
            b'melonds_network_mode': b'disabled', b'melonds_console_mode': b'ds',
            b'melonds_boot_mode': b'direct', b'melonds_sysfile_mode': b'builtin',
            b'melonds_screen_layout1': b'top-bottom', b'melonds_number_of_screen_layouts': b'1',
            b'melonds_screen_gap': b'0', b'melonds_touch_mode': b'pointer',
            b'melonds_render_mode': b'software',
        }

    @Environment
    def environment(command, pointer):
        if not pointer:
            return False
        if command == 3:
            ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
            return True
        if command in (9, 30, 31):
            selected = save_dir if command == 31 else system
            ct.cast(pointer, ct.POINTER(ct.c_char_p))[0] = str(selected).encode()
            return True
        if command == 10:
            requested = ct.cast(pointer, ct.POINTER(ct.c_int))[0]
            return requested in ((1,) if console == 'ds' else (0, 1, 2))
        if command == 15 and console == 'ds':
            option = ct.cast(pointer, ct.POINTER(Variable)).contents
            option.value = options.get(option.key)
            return option.value is not None
        if command == 17:
            ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
            return True
        if command == 52:
            ct.cast(pointer, ct.POINTER(ct.c_uint))[0] = 2
            return True
        return command in (11, 16, 67)

    @Video
    def video(data, width, height, pitch):
        if data and ((console == 'gba' and (width, height) == (240, 160)) or
                     (console == 'ds' and (width, height) == (256, 384))):
            frames[0] += 1

    @Audio
    def audio_sample(left, right):
        audio[0] += int(left != 0 or right != 0)

    @AudioBatch
    def audio_batch(data, count):
        if data and count:
            audio[0] += count
        return count

    @Poll
    def poll():
        pass

    @Input
    def input_state(port, device, index, key):
        if console == 'ds':
            return int(port == 0 and device == 1 and index == 0 and key == 8 and
                       115 <= current_frame[0] < 128)
        return 0

    callbacks = [
        ('retro_set_environment', environment), ('retro_set_video_refresh', video),
        ('retro_set_audio_sample', audio_sample), ('retro_set_audio_sample_batch', audio_batch),
        ('retro_set_input_poll', poll), ('retro_set_input_state', input_state),
    ]
    for name, callback in callbacks:
        fn = getattr(core, name)
        fn.argtypes = [type(callback)]
        fn(callback)
    core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
    core.retro_load_game.restype = ct.c_bool
    core.retro_get_memory_data.argtypes = [ct.c_uint]
    core.retro_get_memory_data.restype = ct.c_void_p
    core.retro_get_memory_size.argtypes = [ct.c_uint]
    core.retro_get_memory_size.restype = ct.c_size_t
    core.retro_api_version.restype = ct.c_uint
    core.retro_serialize_size.restype = ct.c_size_t
    core.retro_serialize.argtypes = [ct.c_void_p, ct.c_size_t]
    core.retro_serialize.restype = ct.c_bool
    core.retro_unserialize.argtypes = [ct.c_void_p, ct.c_size_t]
    core.retro_unserialize.restype = ct.c_bool
    core.retro_run.argtypes = []
    core.retro_init()
    info = GameInfo(str(Path(rom_path).resolve()).encode(), ct.cast(rom, ct.c_void_p), len(rom_bytes), None)
    assert core.retro_load_game(ct.byref(info)), 'Pinned core rejected the approved fixture'
    return core, rom, rom_bytes, frames, audio, current_frame, callbacks


def restore_child(console, core_path, rom_path, work, machine_path, native_path):
    core, rom, rom_bytes, frames, audio, current_frame, callbacks = load_core(console, core_path, rom_path, work)
    pin = PINS[console]
    try:
        assert core.retro_api_version() == 1
        state = Path(machine_path).read_bytes()
        native = Path(native_path).read_bytes()
        for frame in range(220 if console == 'ds' else 200):
            current_frame[0] = frame
            core.retro_run()
        memory_size = core.retro_get_memory_size(0)
        memory = core.retro_get_memory_data(0)
        assert memory and memory_size == len(native) == pin['sram_size']
        state_buffer = ct.create_string_buffer(state)
        assert core.retro_unserialize(state_buffer, len(state)), 'Disposable fixture rejected its own state'
        assert ct.string_at(memory, memory_size) == native, 'State/native payload pair came from different boundaries'
        current_frame[0] = 221
        core.retro_run()
        assert frames[0] >= 1
        print(f'PASS disposable {console} restore: {len(state)} state bytes and {len(native)} native bytes agree')
    finally:
        core.retro_unload_game()
        core.retro_deinit()


def capture_main(console, core_path, rom_path, work):
    pin = PINS[console]
    core, rom, rom_bytes, frames, audio, current_frame, callbacks = load_core(console, core_path, rom_path, work)
    machine_path = work / 'machine.bin'
    native_path = work / 'native.bin'
    closed = False
    try:
        assert core.retro_api_version() == 1
        memory_size = core.retro_get_memory_size(0)
        if console == 'gba':
            assert memory_size in (32768, 131072), f'Unsupported GBA save region: {memory_size}'
        else:
            assert memory_size == pin['sram_size'], f'Unsupported or missing cartridge SRAM region: {memory_size}'
        memory = core.retro_get_memory_data(0)
        assert memory
        for frame in range(220 if console == 'ds' else 200):
            current_frame[0] = frame
            core.retro_run()
        assert frames[0] >= (210 if console == 'ds' else 190)
        memory_size = core.retro_get_memory_size(0)
        memory = core.retro_get_memory_data(0)
        assert memory and memory_size == pin['sram_size']
        if console == 'ds':
            assert ct.string_at(memory + 0x180, 8) == b'PLMP-DS3', 'DS synthetic fixture did not write its EEPROM'
        native = ct.string_at(memory, memory_size)
        assert len(native) == pin['sram_size']
        rss_before = rss_bytes()
        peak_before = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024

        t0 = time.perf_counter_ns()
        state_size = core.retro_serialize_size()
        size_query_ns = time.perf_counter_ns() - t0
        assert 0 < state_size <= 64 * 1024 * 1024
        state_bytes = None
        serial_times = []
        serial_hashes = []
        for _ in range(5):
            output = ct.create_string_buffer(state_size)
            start = time.perf_counter_ns()
            assert core.retro_serialize(output, state_size), 'Pinned serializer rejected exact advertised size'
            serial_times.append(time.perf_counter_ns() - start)
            current = output.raw
            serial_hashes.append(sha(current))
            if state_bytes is None:
                state_bytes = current
            else:
                assert current == state_bytes, 'Same-boundary serialized bytes changed across captures'
            del current
            del output
        assert ct.string_at(memory, memory_size) == native, 'Serializer mutated cartridge SRAM/EEPROM'
        native_after = ct.string_at(memory, memory_size)
        assert native_after == native
        state_after_rss = rss_bytes()
        peak_after = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024

        machine_path.write_bytes(state_bytes)
        native_path.write_bytes(native)
        assert machine_path.read_bytes() == state_bytes
        assert native_path.read_bytes() == native
        metadata = {
            'console': console,
            'core_revision': pin['core_revision'],
            'frontend_revision': pin['frontend_revision'],
            'host_core_binary_sha256': sha(Path(core_path).read_bytes()),
            'rom_sha256': sha(rom_bytes),
            'libretro_api': core.retro_api_version(),
            'machine_length': state_size,
            'machine_sha256': sha(state_bytes),
            'native_length': memory_size,
            'native_sha256': sha(native),
            'stable_five_identical_captures': len(set(serial_hashes)) == 1,
            'size_query_ms': round(size_query_ns / 1_000_000, 3),
            'serialize_ms': [round(value / 1_000_000, 3) for value in serial_times],
            'serialize_median_ms': round(statistics.median(serial_times) / 1_000_000, 3),
            'process_rss_before_capture_bytes': rss_before,
            'process_rss_after_capture_bytes': state_after_rss,
            'process_peak_rss_before_capture_bytes': peak_before,
            'process_peak_rss_after_capture_bytes': peak_after,
            'process_peak_rss_delta_bytes': max(0, peak_after - peak_before),
            'state_plus_native_bytes': state_size + memory_size,
            'frames': frames[0],
            'nonzero_audio_samples_or_frames': audio[0],
        }
        (work / 'capture-metrics.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(json.dumps(metadata, sort_keys=True), flush=True)
        core.retro_unload_game()
        core.retro_deinit()
        closed = True
    finally:
        if not closed:
            try:
                core.retro_unload_game()
                core.retro_deinit()
            except Exception:
                pass
    child = work / 'isolated-restore'
    child.mkdir(exist_ok=True)
    subprocess.run([sys.executable, str(Path(__file__).resolve()), 'restore', console,
                    core_path, rom_path, str(child), str(machine_path), str(native_path)], check=True)
    print(f'PASS {console} actual core serialize and native snapshot; API v1, {state_size} bytes, unchanged save RAM')


def main():
    if len(sys.argv) == 8 and sys.argv[1] == 'restore':
        _, _, console, core_path, rom_path, work, machine, native = sys.argv
        restore_child(console, core_path, rom_path, Path(work), machine, native)
        return
    if len(sys.argv) != 5 or sys.argv[1] not in PINS:
        raise SystemExit('Usage: verify-p4-c-checkpoint.py gba|ds PINNED_HOST_CORE ROM IGNORED_APP_BUILD_DIR')
    console, core_path, rom_path, work = sys.argv[1:]
    work = Path(work).resolve()
    if 'app' not in work.parts or 'build' not in work.parts:
        raise SystemExit('Refusing checkpoint test writes outside app/build')
    work.mkdir(parents=True, exist_ok=True)
    capture_main(console, core_path, rom_path, work)


if __name__ == '__main__':
    main()
