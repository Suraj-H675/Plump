"""Pinned actual Libretro SRAM/EEPROM D4 promotion and rollback cold executions.

Only previously accepted disposable D1 test fixtures under app/build are permitted.
No personal saves, ROMs, APKs, devices, network, or machine-state replay.
"""
import ctypes as ct
import hashlib
import os
from importlib.machinery import SourceFileLoader
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ROM = {
    'gba': 'c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5',
    'ds': '6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70',
}
CORE_FILE = {
    'gba': ROOT/'app/build/p4-a-work/mgba-host/mgba_libretro.so',
    'ds': Path(os.environ.get('PLUMP_P4_LEGACY_DS_CORE',
                           str(ROOT/'app/build/p3-melonds-host/src/libretro/melondsds_libretro.so'))),
}
HARNESS = SourceFileLoader('p4d4_pinned_harness', str(ROOT/'tests/roms/verify-p4-c-checkpoint.py')).load_module()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def real_game(console, stage, proof, source_root):
    expected = {'gba': 32768, 'ds': 8192}[console]
    source = source_root/console
    core_path = CORE_FILE[console]
    rom = (source_root/'gba-button-sram.gba') if console == 'gba' else ROOT/'app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds'
    assert rom.is_file() and digest(rom.read_bytes()) == FIXTURE_ROM[console], 'Incorrect disposable pinned ROM'
    assert digest(core_path.read_bytes()) == HARNESS.PINS[console]['core_sha256'], 'Pinned engine mismatch'
    if console == 'gba':
        HARNESS.PINS['gba']['rom_sha256'] = FIXTURE_ROM['gba']
    native_file = proof / {'cold2': 'canonical2.bin', 'cold3': 'canonical3.bin',
                            'anchor1': 'anchor1.bin', 'branch2': 'branch2.bin',
                            'rollback1': 'rollback1.bin'}[stage]
    raw = native_file.read_bytes()
    assert len(raw) == expected, 'Native cartridge save size mismatch'
    correct = source/'n_latest.bin' if stage in ('anchor1', 'rollback1') else source/'n_branch.bin'
    if stage != 'cold3':
        assert raw == correct.read_bytes(), 'Selected timeline or original canonical anchor is wrong'
    core_dir = proof/(console+'-'+stage+'-core')
    core, rom_buffer, game, rendered, audio, clock, refs = HARNESS.load_core(
        console, str(core_path), str(rom), core_dir)
    if console == 'gba':
        @HARNESS.Input
        def buttons(port, device, index, ident):
            return int(port == 0 and device == 1 and ident == 8 and stage == 'cold2' and
                       115 <= clock[0] < 128)
        core.retro_set_input_state.argtypes = [HARNESS.Input]
        core.retro_set_input_state(buttons)
        refs.append(('physical-A-button', buttons))
    try:
        memory = core.retro_get_memory_data(0)
        capacity = core.retro_get_memory_size(0)
        assert memory and (capacity == expected or (console == 'gba' and capacity == 131072)), capacity
        if capacity != expected:
            ct.memset(memory, 255, capacity)  # mGBA provisional 128K before first SRAM frame
        ct.memmove(memory, raw, expected)  # Always before any frame: no obsolete S_state replay
        assert ct.string_at(core.retro_get_memory_data(0), expected) == raw
        for frame in range(80):
            clock[0] = frame
            core.retro_run()
        memory = core.retro_get_memory_data(0)
        assert memory and core.retro_get_memory_size(0) == expected
        obtained = ct.string_at(memory, expected)
        assert obtained == raw, 'Cold core mutated selected saved progress'
        actual_counter = obtained[0x180] if console == 'gba' else obtained[0x188]
        intended_counter = {'cold2': 2, 'cold3': 3, 'anchor1': 1,
                            'rollback1': 1, 'branch2': 2}[stage]
        assert actual_counter == intended_counter, (stage, console, actual_counter, intended_counter)
        if console == 'gba':
            assert obtained[0x181] == (intended_counter ^ 255)
        else:
            assert obtained[0x189] == (intended_counter ^ 255)
        assert rendered[0] >= 70
        print('PASS ACTUAL PINNED REAL D4', console, stage,
              'cold counter', actual_counter, 'native SHA-256', digest(obtained), flush=True)
        if stage == 'cold2':
            for frame in range(80, 150):
                clock[0] = frame
                core.retro_run()
            advanced = ct.string_at(core.retro_get_memory_data(0), expected)
            assert advanced[0x180 if console == 'gba' else 0x188] == 3
            assert advanced[0x181 if console == 'gba' else 0x189] == 252
            (proof/'canonical3.bin').write_bytes(advanced)
            print('PASS ACTUAL PINNED REAL D4', console,
                  'native canonical after real KEY_A counter 2->3', digest(advanced), flush=True)
    finally:
        core.retro_unload_game()
        core.retro_deinit()


if __name__ == '__main__':
    if len(sys.argv) != 5 or sys.argv[1] not in ('gba','ds') or sys.argv[2] not in ('cold2','cold3','anchor1','branch2','rollback1'):
        raise SystemExit('usage: verify-p4-d4-real-core-promotion.py gba|ds cold2|cold3|anchor1|branch2|rollback1 PROOF_DIR D1_DIR')
    console, stage = sys.argv[1:3]
    proof = Path(sys.argv[3]).resolve()
    source_root = Path(sys.argv[4]).resolve()
    allowed = (ROOT/'app/build').resolve()
    assert proof.is_relative_to(allowed) and source_root.is_relative_to(allowed), 'Disposable app/build paths required'
    real_game(console, stage, proof, source_root)
