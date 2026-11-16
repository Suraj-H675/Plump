"""Independent actual pinned Libretro core restore interruption probe, disposable files only."""
import ctypes as ct
import os
import sys
from pathlib import Path
from importlib.machinery import SourceFileLoader
repo=Path.cwd()
con=sys.argv[1];point=sys.argv[2];root=Path(sys.argv[3]);source=Path(sys.argv[4])
if con not in ('gba','ds'):raise AssertionError(con)
M=SourceFileLoader('p4c',str(repo/'tests/roms/verify-p4-c-checkpoint.py')).load_module()
core=repo/('app/build/p4-a-work/mgba-host/mgba_libretro.so' if con=='gba' else 'app/build/p3-melonds-host/src/libretro/melondsds_libretro.so')
rom=(source.parent/'gba-button-sram.gba') if con=='gba' else repo/'app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds'
if con=='gba':
 import hashlib
 M.PINS['gba']['rom_sha256']=hashlib.sha256(rom.read_bytes()).hexdigest()
output=root/('actual-libretro-'+point)
coreobj,rom_buffer,rom_ptr,render,audio,clock,refs=M.load_core(con,str(core),str(rom),output)
def halt():
 print('HARD EXIT actual real pinned',con,'Libretro',point,'rendered_frames',render[0],flush=True)
 os._exit(87)
if point=='AFTER_CORE_INIT':halt()
if con=='gba':
 clock[0]=0;coreobj.retro_run()
expected=source.joinpath('n_state.bin').read_bytes()
assert coreobj.retro_get_memory_size(0)==len(expected)
ptr=coreobj.retro_get_memory_data(0);assert ptr
ct.memmove(ptr,expected,len(expected));assert ct.string_at(coreobj.retro_get_memory_data(0),len(expected))==expected
if point=='AFTER_NATIVE_RESTORE':halt()
state=source.joinpath('s_old.bin').read_bytes()
assert coreobj.retro_serialize_size()==len(state),(coreobj.retro_serialize_size(),len(state))
buf=ct.create_string_buffer(state)
assert coreobj.retro_unserialize(buf,len(state)),'actual pinned real Libretro retro_unserialize rejected S_old'
if point=='AFTER_MACHINE_RESTORE':halt()
ptr=coreobj.retro_get_memory_data(0);assert ptr and coreobj.retro_get_memory_size(0)==len(expected)
ct.memmove(ptr,expected,len(expected));assert ct.string_at(coreobj.retro_get_memory_data(0),len(expected))==expected
if point=='AFTER_FINAL_NATIVE_READBACK':halt()
raise AssertionError('invalid point: '+point)
