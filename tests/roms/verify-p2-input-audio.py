"""Host-only mGBA test of Plump's ORIGINAL, real ARM7TDMI keypad/PSG test ROM.

Proves that the fixture itself changes actual GBA pixels for individual and
simultaneous keys and emits non-silent hardware-generated PCM for A. This does
not prove that Android touchscreen mapping or loudspeaker output works.
Usage: python3 tests/roms/verify-p2-input-audio.py CORE_SO DIAGNOSTIC_ROM
"""
import ctypes as ct
from pathlib import Path
import sys


class GameInfo(ct.Structure):
    _fields_ = [('path', ct.c_char_p), ('data', ct.c_void_p),
                ('size', ct.c_size_t), ('meta', ct.c_char_p)]


if len(sys.argv) != 3:
    raise SystemExit('usage: verify-p2-input-audio.py CORE_SO ROM')
core = ct.CDLL(sys.argv[1])
rom = Path(sys.argv[2]).read_bytes()
rom_buffer = ct.create_string_buffer(rom)
frame_index = [0]
frame_pixels = {}
audio_peaks = {}

ENV = ct.CFUNCTYPE(ct.c_bool, ct.c_uint, ct.c_void_p)
VIDEO = ct.CFUNCTYPE(None, ct.c_void_p, ct.c_uint, ct.c_uint, ct.c_size_t)
AUDIO = ct.CFUNCTYPE(None, ct.c_int16, ct.c_int16)
BATCH = ct.CFUNCTYPE(ct.c_size_t, ct.POINTER(ct.c_int16), ct.c_size_t)
POLL = ct.CFUNCTYPE(None)
INPUT = ct.CFUNCTYPE(ct.c_int16, ct.c_uint, ct.c_uint, ct.c_uint, ct.c_uint)


def mask_for_frame(n):
    if n < 30:
        return 0
    if n < 55:
        return 1 << 8               # A
    if n < 80:
        return 1 << 0               # B
    if n < 105:
        return (1 << 8) | (1 << 0)  # A+B together
    if n < 130:
        return 1 << 10              # L
    if n < 155:
        return 1 << 11              # R
    return 1 << 4                   # UP


@ENV
def environment(command, ptr):
    if command == 10:
        return ct.cast(ptr, ct.POINTER(ct.c_int)).contents.value == 2  # RGB565
    return False


@VIDEO
def video(ptr, width, height, pitch):
    assert (width, height) == (240, 160), (width, height)
    if ptr:
        sample_at = 80 * pitch + 120 * 2
        frame_pixels[frame_index[0]] = int.from_bytes(ct.string_at(ptr + sample_at, 2), 'little')


@AUDIO
def audio(left, right):
    n = frame_index[0]
    audio_peaks[n] = max(audio_peaks.get(n, 0), abs(left), abs(right))


@BATCH
def audio_batch(ptr, frames):
    n = frame_index[0]
    if frames:
        values = ct.cast(ptr, ct.POINTER(ct.c_int16 * (frames * 2))).contents
        audio_peaks[n] = max(audio_peaks.get(n, 0), max(abs(v) for v in values))
    return frames


@POLL
def poll():
    pass


@INPUT
def input_state(port, device, index, key):
    return int(port == 0 and device == 1 and index == 0 and
               bool(mask_for_frame(frame_index[0]) & (1 << key)))


for setter, callback in [
    ('retro_set_environment', environment),
    ('retro_set_video_refresh', video),
    ('retro_set_audio_sample', audio),
    ('retro_set_audio_sample_batch', audio_batch),
    ('retro_set_input_poll', poll),
    ('retro_set_input_state', input_state),
]:
    getattr(core, setter).argtypes = [type(callback)]
    getattr(core, setter)(callback)

core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
core.retro_load_game.restype = ct.c_bool
core.retro_init()
info = GameInfo(None, ct.cast(rom_buffer, ct.c_void_p), len(rom), None)
assert core.retro_load_game(ct.byref(info)), 'ROM rejected by real mGBA engine'
try:
    for n in range(185):
        frame_index[0] = n
        core.retro_run()

    labels = [('NONE', 20), ('A', 45), ('B', 70), ('A+B', 95),
              ('L', 120), ('R', 145), ('UP', 170)]
    sampled = {label: frame_pixels[n] for label, n in labels}
    print('mGBA real-frame center RGB565 colors:',
          {label: hex(pixel) for label, pixel in sampled.items()})
    assert len({sampled[name] for name in ['NONE', 'A', 'B', 'A+B', 'R', 'UP']}) == 6
    assert sampled['A+B'] == sampled['L'], 'A+B and L should both be yellow'
    pressed_audio_peak = max(audio_peaks.get(n, 0) for n in range(37, 55))
    quiet_audio_peak = max(audio_peaks.get(n, 0) for n in range(12, 30))
    print('PSG peak samples: no buttons=', quiet_audio_peak,
          'A-held=', pressed_audio_peak)
    assert pressed_audio_peak > 0, 'Hardware tone was silent even during A'
    assert quiet_audio_peak == 0, 'Unexpected tone before A pressed'
    print('PASS: original ARM7TDMI GBA fixture has functioning keys,',
          'simultaneous A+B detection, and real non-silent PSG audio in mGBA')
finally:
    core.retro_unload_game()
    core.retro_deinit()
