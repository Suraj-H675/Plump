"""Two *separate processes* exercise actual DS EEPROM writes and reload.

Genuine melonDS reads/writes Nintendo DS AUX SPI during execution of our own
synthetic retail-classified CC0 ARM9 diagnostic. Forcing this classification
is a bounded save-adapter test, NOT evidence of retail game compatibility.

Usage: python3 verify-p3-native-save-diagnostic.py first|cold|state-rewind CORE ROM WORKDIR
"""

import ctypes as ct
import hashlib
import json
from pathlib import Path
import sys
import zlib


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


if len(sys.argv) != 5 or sys.argv[1] not in ('first', 'cold', 'state-rewind'):
    raise SystemExit('first|cold|state-rewind CORE_SO SYNTHETIC_NDS WORKDIR arguments required')
stage = sys.argv[1]
rom_file = Path(sys.argv[3]).resolve()
rom = rom_file.read_bytes()
assert rom[12:16] == b'P3SV' and rom[0x4000:0x4004] == b'\xff\xde\xff\xe7'
work = Path(sys.argv[4]).resolve()
assert work.is_dir()
system = work / 'system'
system.mkdir(exist_ok=True)
save_dir = work / 'libretro-saves'
save_dir.mkdir(exist_ok=True)
sram_file = work / 'saved-sram.bin'
receipt_file = work / 'receipt.json'
expected_magic = b'PLMP-DS3'
game = ct.create_string_buffer(rom)
core = ct.CDLL(sys.argv[2])
frame_no = [0]
frames = [0]
input_polls = [0]
av_frame_crc = {}
network_options = set()

options = {
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


@Environment
def environment(command, pointer):
    if not pointer:
        return False
    if command == 3:
        ct.cast(pointer, ct.POINTER(ct.c_bool))[0] = False
        return True
    if command in (9, 30, 31):
        ct.cast(pointer, ct.POINTER(ct.c_char_p))[0] = (
            str(save_dir if command == 31 else system).encode())
        return True
    if command == 10:
        return ct.cast(pointer, ct.POINTER(ct.c_int))[0] == 1
    if command == 15:
        var = ct.cast(pointer, ct.POINTER(Variable)).contents
        network_options.add(var.key)
        var.value = options.get(var.key)
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
    if pointer and width == 256 and height == 384:
        frames[0] += 1
        if frame_no[0] in (80, 190):
            buffer = ct.string_at(pointer, pitch * height)
            av_frame_crc[frame_no[0]] = zlib.crc32(buffer[:pitch * 192])


@Audio
def audio(left, right):
    pass


@AudioBatch
def audio_batch(pointer, count):
    return count


@Poll
def poll():
    input_polls[0] += 1


@Input
def input_state(port, device, index, key):
    return int(stage != 'cold' and port == 0 and device == 1 and index == 0 and
               key == 8 and 115 <= frame_no[0] < 128)


for name, callback in [
    ('retro_set_environment', environment),
    ('retro_set_video_refresh', video),
    ('retro_set_audio_sample', audio),
    ('retro_set_audio_sample_batch', audio_batch),
    ('retro_set_input_poll', poll),
    ('retro_set_input_state', input_state),
]:
    fn = getattr(core, name)
    fn.argtypes = [type(callback)]
    fn(callback)

core.retro_load_game.argtypes = [ct.POINTER(GameInfo)]
core.retro_load_game.restype = ct.c_bool
core.retro_get_memory_data.argtypes = [ct.c_uint]
core.retro_get_memory_data.restype = ct.c_void_p
core.retro_get_memory_size.argtypes = [ct.c_uint]
core.retro_get_memory_size.restype = ct.c_size_t
core.retro_get_system_av_info.argtypes = [ct.POINTER(AvInfo)]
core.retro_init()
info = GameInfo(str(rom_file).encode(), ct.cast(game, ct.c_void_p), len(rom), None)
assert core.retro_load_game(ct.byref(info)), 'Cannot load synthetic SRAM test game'
try:
    length = core.retro_get_memory_size(0)
    memory = core.retro_get_memory_data(0)
    print(f'Stage={stage} synthetic cartridge SRAM bytes={length}', flush=True)
    assert length == 8192 and memory, 'Core did not expose true DS EEPROM buffer'
    if stage == 'cold':
        previous = sram_file.read_bytes()
        assert len(previous) == length
        assert previous[0x180:0x188] == expected_magic
        assert previous[0x188] == 1
        assert previous[0x189] == 0xFE
        ct.memmove(memory, previous, length)  # retro_get_memory load BEFORE first frame
        assert ct.string_at(memory, length) == previous
        print('Pre-first-frame saved cartridge SRAM restored byte-for-byte', flush=True)
    else:
        assert not sram_file.exists(), 'Use a fresh private ignored test folder'

    av = AvInfo()
    core.retro_get_system_av_info(ct.byref(av))
    assert (av.geometry.width, av.geometry.height) == (256, 384)

    if stage == 'state-rewind':
        core.retro_serialize_size.restype = ct.c_size_t
        core.retro_serialize.argtypes = [ct.c_void_p, ct.c_size_t]
        core.retro_serialize.restype = ct.c_bool
        core.retro_unserialize.argtypes = [ct.c_void_p, ct.c_size_t]
        core.retro_unserialize.restype = ct.c_bool

    for n in range(220):
        frame_no[0] = n
        core.retro_run()
        if stage == 'state-rewind' and n == 90:
            state_size = core.retro_serialize_size()
            assert 0 < state_size < 128 * 1024 * 1024
            saved_state = ct.create_string_buffer(state_size)
            assert core.retro_serialize(saved_state, state_size)
            print('DS state serialized before in-game EEPROM write:', state_size, 'bytes', flush=True)
        if stage == 'state-rewind' and n == 170:
            before = ct.string_at(memory, length)[0x180:0x190]
            assert before == bytes.fromhex('504c4d502d44533301feac5301020304')
            assert core.retro_unserialize(saved_state, state_size), 'Libretro rejected the older state'
            after = ct.string_at(memory, length)[0x180:0x190]
            print('Save-state rollback: EEPROM before=', before.hex(),
                  'EEPROM after=', after.hex(), flush=True)
            assert after == bytes(16), 'Pinned core state did not rewind cartridge EEPROM as expected'
            print('PASS STATE SAFETY: loading an older DS state erased newer in-memory EEPROM;',
                  'Plump MUST keep DS state loading disabled pending native-save protection.', flush=True)
            raise SystemExit(0)  # finally unloads the isolated host core

    assert frames[0] >= 210 and input_polls[0] >= 210
    current = ct.string_at(memory, length)
    print('SRAM data at 0x180', current[0x180:0x190].hex(),
          'frame-crc', av_frame_crc, 'frames', frames[0], flush=True)

    if stage == 'first':
        assert current[0x180:0x188] == expected_magic, 'Game failed to write via DS SPI'
        assert current[0x188] == 1 and current[0x189] == 0xFE
        assert current[0x18A:0x190] == bytes((0xAC, 0x53, 1, 2, 3, 4))
        sram_file.write_bytes(current)
        receipt_file.write_text(json.dumps({
            'rom_sha256': hashlib.sha256(rom).hexdigest(),
            'sram_sha256': hashlib.sha256(current).hexdigest(),
            'prewrite_top_crc': av_frame_crc[80],
            'postwrite_top_crc': av_frame_crc[190],
        }))
        assert av_frame_crc[80] != av_frame_crc[190], 'Game did not visually report write'
        print('PASS FIRST: in-game EEPROM write, immediate SPI readback, cartridge memory snapshot', flush=True)
    else:
        receipt = json.loads(receipt_file.read_text())
        assert receipt['rom_sha256'] == hashlib.sha256(rom).hexdigest()
        assert receipt['sram_sha256'] == hashlib.sha256(current).hexdigest()
        assert av_frame_crc[80] != receipt['prewrite_top_crc'], (
            'Game did not visibly distinguish fresh EEPROM from restored EEPROM')
        assert av_frame_crc[80] != receipt['postwrite_top_crc'], (
            'Restored game should show initial RESTORED state, not live write state')
        print('PASS COLD: separate-process SRAM restored into core and read in-game', flush=True)
finally:
    core.retro_unload_game()
    core.retro_deinit()
