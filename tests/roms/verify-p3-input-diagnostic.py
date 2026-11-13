"""Run original CC0 Nintendo DS input/audio homebrew against REAL melonDS.

Proves accurate touchscreen coordinates, stylus release, A+B during stylus
contact and non-silent hardware-generated sound. This checks a DEVELOPMENT
HOST, not Android touchscreen, speaker, or thermal performance.

Usage: python3 tests/roms/verify-p3-input-diagnostic.py CORE_SO ROM TEST_DIR
"""

import array
import ctypes as ct
import hashlib
from pathlib import Path
import sys
import zlib


ROM_SHA256 = '109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d'


class GameInfo(ct.Structure):
    _fields_ = [('path', ct.c_char_p), ('data', ct.c_void_p),
                ('size', ct.c_size_t), ('meta', ct.c_char_p)]


class Geometry(ct.Structure):
    _fields_ = [('width', ct.c_uint), ('height', ct.c_uint),
                ('max_width', ct.c_uint), ('max_height', ct.c_uint),
                ('aspect', ct.c_float)]


class Timing(ct.Structure):
    _fields_ = [('fps', ct.c_double), ('sample_rate', ct.c_double)]


class AvInfo(ct.Structure):
    _fields_ = [('geometry', Geometry), ('timing', Timing)]


class Variable(ct.Structure):
    _fields_ = [('key', ct.c_char_p), ('value', ct.c_char_p)]


if len(sys.argv) != 4:
    raise SystemExit('CORE_SO ROM TEST_DIR arguments required')

rom_file = Path(sys.argv[2]).resolve()
rom = rom_file.read_bytes()
assert hashlib.sha256(rom).hexdigest() == ROM_SHA256, 'Unrecognized diagnostic ROM'
rom_memory = ct.create_string_buffer(rom)
test_dir = Path(sys.argv[3]).resolve()
system_dir = test_dir / 'system'
save_dir = test_dir / 'save'
system_dir.mkdir(parents=True, exist_ok=True)
save_dir.mkdir(parents=True, exist_ok=True)
sys_bytes = str(system_dir).encode()
save_bytes = str(save_dir).encode()
core = ct.CDLL(sys.argv[1])

frame_index = [0]
captures = {}
frame_callbacks = [0]
input_polls = [0]
audio_peaks = {'idle': 0, 'a_b_stylus': 0, 'released': 0}
audio_frames = [0]
requested_options = set()

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

Environment = ct.CFUNCTYPE(ct.c_bool, ct.c_uint, ct.c_void_p)
Video = ct.CFUNCTYPE(None, ct.c_void_p, ct.c_uint, ct.c_uint, ct.c_size_t)
Audio = ct.CFUNCTYPE(None, ct.c_int16, ct.c_int16)
AudioBatch = ct.CFUNCTYPE(ct.c_size_t, ct.POINTER(ct.c_int16), ct.c_size_t)
Poll = ct.CFUNCTYPE(None)
Input = ct.CFUNCTYPE(ct.c_int16, ct.c_uint, ct.c_uint, ct.c_uint, ct.c_uint)


def phase(n):
    if 80 <= n < 140:
        return (32, 32)
    if 140 <= n < 200 or 320 <= n < 380:
        return (128, 96)
    if 200 <= n < 260:
        return (224, 160)
    return None


def native_to_pointer(value, maximum):
    # Exactly mirrors Plump's ndsPointerCoordinate() JNI helper.
    return int(-32768 + value * 65535 // (maximum - 1))


@Environment
def environment(command, pointer):
    if not pointer:
        return False
    if command == 3:
        ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
        return True
    if command in (9, 30, 31):
        ct.cast(pointer, ct.POINTER(ct.c_char_p))[0] = (
            save_bytes if command == 31 else sys_bytes)
        return True
    if command == 10:
        return ct.cast(pointer, ct.POINTER(ct.c_int))[0] == 1
    if command == 15:
        var = ct.cast(pointer, ct.POINTER(Variable)).contents
        requested_options.add(var.key)
        var.value = forced_options.get(var.key)
        return var.value is not None
    if command == 17:
        ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
        return True
    if command == 52:
        ct.cast(pointer, ct.POINTER(ct.c_uint))[0] = 2
        return True
    return command in (11, 16, 67)


@Video
def video(pointer, width, height, pitch):
    assert width == 256 and height == 384 and pitch >= width * 4, (width, height, pitch)
    if pointer:
        frame_callbacks[0] += 1
        if frame_index[0] in (70, 120, 180, 240, 300, 350, 400):
            captures[frame_index[0]] = ct.string_at(pointer, pitch * height), pitch


def audio_phase(n):
    return 'a_b_stylus' if 320 <= n < 380 else 'released' if n >= 385 else 'idle'


def add_audio_peak(values, frames):
    audio_frames[0] += frames
    if values:
        current = audio_phase(frame_index[0])
        audio_peaks[current] = max(audio_peaks[current], max(abs(v) for v in values))


@Audio
def audio(left, right):
    add_audio_peak((left, right), 1)


@AudioBatch
def audio_batch(pointer, frames):
    if frames:
        samples = array.array('h')
        samples.frombytes(ct.string_at(pointer, frames * 4))
        add_audio_peak(samples, frames)
    return frames


@Poll
def poll():
    input_polls[0] += 1


@Input
def input_state(port, device, index, key):
    if port != 0 or index != 0:
        return 0
    n = frame_index[0]
    if device == 1:
        return int(320 <= n < 380 and key in (0, 8))  # A+B while stylus held
    if device != 6:
        return 0
    target = phase(n)
    if key == 2:
        return int(target is not None)
    if target is None:
        return 0
    if key == 0:
        return native_to_pointer(target[0], 256)
    if key == 1:
        return native_to_pointer(target[1] + 192, 384)
    return 0


for method, callback in [
    ('retro_set_environment', environment),
    ('retro_set_video_refresh', video),
    ('retro_set_audio_sample', audio),
    ('retro_set_audio_sample_batch', audio_batch),
    ('retro_set_input_poll', poll),
    ('retro_set_input_state', input_state),
]:
    function = getattr(core, method)
    function.argtypes = [type(callback)]
    function(callback)

core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
core.retro_load_game.restype = ct.c_bool
core.retro_get_system_av_info.argtypes = [ct.POINTER(AvInfo)]
core.retro_get_memory_size.argtypes = [ct.c_uint]
core.retro_get_memory_size.restype = ct.c_size_t
core.retro_init()
info = GameInfo(str(rom_file).encode(), ct.cast(rom_memory, ct.c_void_p), len(rom), None)
assert core.retro_load_game(ct.byref(info)), 'Pinned melonDS rejected DS test cartridge'
try:
    av = AvInfo()
    core.retro_get_system_av_info(ct.byref(av))
    assert (av.geometry.width, av.geometry.height) == (256, 384)

    for index in range(430):
        frame_index[0] = index
        core.retro_run()

    assert frame_callbacks[0] >= 420
    assert input_polls[0] >= 420
    assert audio_frames[0] > 0
    assert len(captures) == 7, sorted(captures)

    def panel(when, top):
        pixels, pitch = captures[when]
        return pixels[0:pitch * 192] if top else pixels[pitch * 192:pitch * 384]

    def marker_bounds(when):
        pixels, pitch = captures[when]
        active = []
        for y in range(192):
            line = pixels[(y + 192) * pitch:(y + 193) * pitch]
            for x in range(256):
                if line[4*x:4*x+3] != b'\0\0\0':
                    active.append((x, y))
        if not active:
            return None, 0
        return (min(p[0] for p in active), min(p[1] for p in active),
                max(p[0] for p in active), max(p[1] for p in active)), len(active)

    for when, expected in [(70, None), (120, (32, 32)), (180, (128, 96)),
                           (240, (224, 160)), (300, None), (350, (128, 96)),
                           (400, None)]:
        bounds, count = marker_bounds(when)
        print(f'frame={when} stylus={expected} marker_bounds={bounds} nonblack_pixels={count}', flush=True)
        if expected is None:
            assert bounds is None, f'Touch marker stuck after release at frame {when}'
        else:
            x, y = expected
            assert bounds == (x - 4, y - 4, x + 4, y + 4), (
                f'Wrong touchscreen transform at frame {when}', bounds, expected)
            assert 20 <= count <= 100, 'Missing or malformed marker'

    # Top-screen console data shows the held buttons while the lower screen
    # maintains its touch marker. The two input devices must coexist.
    solo_top = zlib.crc32(panel(180, True))
    chord_top = zlib.crc32(panel(350, True))
    assert solo_top != chord_top, 'A+B was not reflected by the program'

    print('top_console_crc32: stylus-only=', solo_top, 'stylus+A+B=', chord_top)
    print('PSG_pcm_peaks:', audio_peaks, 'audio_frames=', audio_frames[0])
    assert audio_peaks['a_b_stylus'] > 0, 'A+B did not produce hardware PSG tone'
    assert {b'melonds_network_mode', b'melonds_touch_mode',
            b'melonds_screen_layout1'}.issubset(requested_options)
    assert core.retro_get_memory_size(0) == 0, (
        'This homebrew has no native cartridge SRAM: do not claim DS save proof')
    print('PASS: genuine DS program tracks 3 precise stylus locations, release, '
          'simultaneous A+B and audible-signal PSG callbacks in host melonDS', flush=True)
finally:
    core.retro_unload_game()
    core.retro_deinit()
