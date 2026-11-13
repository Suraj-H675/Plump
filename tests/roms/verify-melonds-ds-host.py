"""Run the pinned real melonDS DS Libretro core against its GPLv3 test fixture.

Host-only: doesn't claim to verify an Android APK or owner device. Run with:
  python3 tests/roms/verify-melonds-ds-host.py LIBRETRO_SO GPL_TEST_ROM TEST_DIR

The supplied test ROM is tracked upstream in JesseTG/melonds-ds at the pinned
commit and never copied into Plump's public Git tree or standard APK.
"""
import ctypes as C
import hashlib
from pathlib import Path
import sys
import time
import zlib


class GameInfo(C.Structure):
    _fields_ = [('path', C.c_char_p), ('data', C.c_void_p),
                ('size', C.c_size_t), ('meta', C.c_char_p)]


class Geometry(C.Structure):
    _fields_ = [('base_width', C.c_uint), ('base_height', C.c_uint),
                ('max_width', C.c_uint), ('max_height', C.c_uint),
                ('aspect_ratio', C.c_float)]


class Timing(C.Structure):
    _fields_ = [('fps', C.c_double), ('sample_rate', C.c_double)]


class AVInfo(C.Structure):
    _fields_ = [('geometry', Geometry), ('timing', Timing)]


class Variable(C.Structure):
    _fields_ = [('key', C.c_char_p), ('value', C.c_char_p)]


if len(sys.argv) != 4:
    raise SystemExit('LIBRETRO_SO GPL_TEST_ROM TEST_DIR arguments required')

core = C.CDLL(sys.argv[1])
rom_path = Path(sys.argv[2]).resolve()
test_dir = Path(sys.argv[3]).resolve()
sys_dir = test_dir / 'nds-system'
save_dir = test_dir / 'nds-core-data'
sys_dir.mkdir(parents=True, exist_ok=True)
save_dir.mkdir(parents=True, exist_ok=True)
system_dir_bytes = str(sys_dir).encode()
save_dir_bytes = str(save_dir).encode()
rom = rom_path.read_bytes()
owned_rom = C.create_string_buffer(rom)
frame_count = 0
frame_checksums = []
panel_checksums = []
audio_count = 0
input_polls = 0
forced_options = {
    b'melonds_network_mode': b'disabled',
    b'melonds_console_mode': b'ds',
    b'melonds_boot_mode': b'direct',
    b'melonds_sysfile_mode': b'builtin',
    b'melonds_screen_layout1': b'top-bottom',
    b'melonds_number_of_screen_layouts': b'1',
    b'melonds_screen_gap': b'0',
    b'melonds_touch_mode': b'pointer',
    b'melonds_render_mode': b'software',
}
requested_options = set()

ENV = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
VIDEO = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
AUDIO = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
BATCH = C.CFUNCTYPE(C.c_size_t, C.POINTER(C.c_int16), C.c_size_t)
POLL = C.CFUNCTYPE(None)
INPUT = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)


@ENV
def env(command, ptr):
    if not ptr:
        return False
    if command == 3:
        C.cast(ptr, C.POINTER(C.c_bool))[0] = False
        return True
    if command in (9, 30, 31):
        C.cast(ptr, C.POINTER(C.c_char_p))[0] = (
            system_dir_bytes if command != 31 else save_dir_bytes
        )
        return True
    if command == 10:
        return C.cast(ptr, C.POINTER(C.c_int))[0] == 1
    if command == 15:
        variable = C.cast(ptr, C.POINTER(Variable)).contents
        requested_options.add(variable.key)
        variable.value = forced_options.get(variable.key)
        return variable.value is not None
    if command == 17:
        C.cast(ptr, C.POINTER(C.c_bool))[0] = False
        return True
    if command == 52:
        C.cast(ptr, C.POINTER(C.c_uint))[0] = 2
        return True
    return command in (11, 16, 67)


@VIDEO
def video(ptr, width, height, pitch):
    global frame_count
    if not ptr:
        return
    frame_count += 1
    if frame_count <= 60 or frame_count % 20 == 0:
        data = C.string_at(ptr, pitch * height)
        frame_checksums.append((width, height, pitch, zlib.crc32(data)))
        if width == 256 and height == 384 and pitch >= width * 4:
            top = data[:pitch * 192]
            lower = data[pitch * 192:pitch * 384]
            panel_checksums.append((zlib.crc32(top), zlib.crc32(lower),
                                    any(top), any(lower)))


@AUDIO
def audio(left, right):
    global audio_count
    audio_count += 1


@BATCH
def audio_batch(ptr, count):
    global audio_count
    audio_count += count
    return count


@POLL
def poll():
    global input_polls
    input_polls += 1


@INPUT
def input_state(port, device, index, key):
    if port != 0 or index != 0:
        return 0
    if device == 1 and key == 8:
        return 1 if 20 <= frame_count < 45 else 0
    if device == 6 and key == 2:
        return 1 if 40 <= frame_count < 60 else 0
    if device == 6 and key == 0:
        return 0
    if device == 6 and key == 1:
        return 16384  # normalized point inside the bottom half
    return 0


for method, callback in [
    ('retro_set_environment', env),
    ('retro_set_video_refresh', video),
    ('retro_set_audio_sample', audio),
    ('retro_set_audio_sample_batch', audio_batch),
    ('retro_set_input_poll', poll),
    ('retro_set_input_state', input_state),
]:
    getattr(core, method).argtypes = [type(callback)]
    getattr(core, method)(callback)

core.retro_load_game.argtypes = [C.POINTER(GameInfo)]
core.retro_load_game.restype = C.c_bool
core.retro_get_system_av_info.argtypes = [C.POINTER(AVInfo)]
core.retro_get_memory_size.argtypes = [C.c_uint]
core.retro_get_memory_size.restype = C.c_size_t
core.retro_init()
content = GameInfo(str(rom_path).encode(), C.cast(owned_rom, C.c_void_p), len(rom), None)
assert core.retro_load_game(C.byref(content)), 'A real licensed NDS test ROM failed to load'
try:
    av = AVInfo()
    core.retro_get_system_av_info(C.byref(av))
    print('DS native software AV:', av.geometry.base_width, av.geometry.base_height,
          'fps:', av.timing.fps, 'audio Hz:', av.timing.sample_rate, flush=True)
    started = time.monotonic()
    for _ in range(100):
        core.retro_run()
    elapsed = time.monotonic() - started
    distinct = len({entry[3] for entry in frame_checksums})
    print('frames=', frame_count, 'distinct_frame_crc32=', distinct,
          'distinct_top_crc32=', len({entry[0] for entry in panel_checksums}),
          'distinct_lower_crc32=', len({entry[1] for entry in panel_checksums}),
          'input_polls=', input_polls, 'audio_frames=', audio_count,
          'seconds_for_100_frames=', round(elapsed, 3),
          'cart_sram_bytes=', core.retro_get_memory_size(0),
          'rom_sha256=', hashlib.sha256(rom).hexdigest(), flush=True)
    assert av.geometry.base_width == 256 and av.geometry.base_height == 384
    assert frame_count >= 90
    assert all(entry[:2] == (256, 384) for entry in frame_checksums)
    assert distinct >= 1
    assert panel_checksums and all(top and bottom for _, _, top, bottom in panel_checksums), (
        'Both screen buffers must contain actual pixel data')
    assert any(top != bottom for top, bottom, _, _ in panel_checksums), (
        'DS upper and lower screens must not just be the same image')
    assert input_polls >= 90
    assert audio_count > 0
    # At this pinned revision, a no-OpenGL build does not expose or request
    # melonds_render_mode; software rendering is unconditional in that build.
    required_options = {b'melonds_network_mode', b'melonds_console_mode',
                        b'melonds_boot_mode', b'melonds_sysfile_mode',
                        b'melonds_touch_mode', b'melonds_screen_layout1'}
    print('requested_core_option_count=', len(requested_options),
          'missing_required=', sorted(required_options - requested_options), flush=True)
    assert required_options.issubset(requested_options), (
        'DS core did not request all required offline/software/touch options')
    assert forced_options[b'melonds_network_mode'] == b'disabled'
    print('PASS: genuine melonDS DS game execution, two native screens in one',
          'software frame, input polling and audio callbacks on host', flush=True)
finally:
    core.retro_unload_game()
    core.retro_deinit()
