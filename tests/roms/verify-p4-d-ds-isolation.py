"""Independent, ignored D1 two-timeline DS core and real PLSV journal proof."""
import ctypes as ct
import os
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from importlib.machinery import SourceFileLoader

REPO = Path(__file__).resolve().parents[2]
WORK = Path(os.environ['PLUMP_P4_D_WORK']).resolve() / 'ds'
CORE = REPO / 'app/build/p3-melonds-host/src/libretro/melondsds_libretro.so'
ROM = REPO / 'app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds'
SHA = hashlib.sha256(ROM.read_bytes()).hexdigest()
JAVACLASSES = Path(os.environ['PLUMP_P4_D_JAVA_CLASSES']).resolve()
HARNESS = SourceFileLoader('p4c_harness',str(REPO/'tests/roms/verify-p4-c-checkpoint.py')).load_module()
assert SHA == HARNESS.PINS['ds']['rom_sha256']
assert hashlib.sha256(CORE.read_bytes()).hexdigest()==HARNESS.PINS['ds']['core_sha256']


def digest(data): return hashlib.sha256(data).hexdigest()

def files():
  result={}
  folder=WORK/'canonical-plsv'
  for p in sorted(folder.glob(SHA+'*')):
    assert p.is_file() and not p.is_symlink()
    result[p.name]=digest(p.read_bytes())
  return result

def journal(cmd, kind, datafile):
  folder=WORK / (kind+'-plsv')
  subprocess.run(['java','-cp',str(JAVACLASSES),'dev.plump.emulator.JournalCli',cmd,str(folder),SHA,str(datafile)],check=True)

def active_timeline(operation,*extra):
  subprocess.run(['java','-cp',str(JAVACLASSES),'dev.plump.emulator.ActiveTimelineHostCli',
    operation,str(WORK),'ds',SHA,digest(CORE.read_bytes()),*[str(item) for item in extra]],check=True)

def begin(label):
  root=WORK / ('host-'+label)
  root.mkdir(parents=True,exist_ok=False)
  return HARNESS.load_core('ds',str(CORE),str(ROM),root)

def sram(core):
  size=core.retro_get_memory_size(0)
  mem=core.retro_get_memory_data(0)
  assert size==8192 and mem
  return ct.string_at(mem,size)

def frame(core,clock,n):clock[0]=n;core.retro_run()

def close(core):core.retro_unload_game();core.retro_deinit()

def first():
  assert not WORK.exists()
  WORK.mkdir(parents=True)
  core,rom_buffer,rom,render,audio,clock,refs=begin('canonical')
  try:
    for n in range(90):frame(core,clock,n)
    native_state=sram(core)
    assert native_state[0x180:0x190]==bytes(16)
    machine_len=core.retro_serialize_size();assert 0<machine_len<=64*1024*1024
    state=ct.create_string_buffer(machine_len)
    assert core.retro_serialize(state,machine_len)
    assert sram(core)==native_state
    (WORK/'s_old.bin').write_bytes(state.raw)
    (WORK/'n_state.bin').write_bytes(native_state)
    for n in range(90,220):frame(core,clock,n)
    latest=sram(core)
    assert latest[0x180:0x190].hex()=='504c4d502d44533301feac5301020304',latest[0x180:0x190].hex()
    (WORK/'n_latest.bin').write_bytes(latest)
    assert render[0]>=210
    journal('write','canonical',WORK/'n_latest.bin')
    journal('write','canonical',WORK/'n_latest.bin')
    original=files();assert len(original)==3,original
    (WORK/'canonical-files.json').write_text(json.dumps(original,indent=2))
    print('PASS DS D1 FIRST old-state',digest(state.raw),'old-native',digest(native_state),'canonical-latest',digest(latest),'canonical-plsv',json.dumps(original),flush=True)
  finally:close(core)

def branch():
  original=json.loads((WORK/'canonical-files.json').read_text())
  assert files()==original
  core,rom_buffer,rom,render,audio,clock,refs=begin('historical')
  try:
    s_old=(WORK/'s_old.bin').read_bytes();n_old=(WORK/'n_state.bin').read_bytes()
    mem=core.retro_get_memory_data(0);assert mem and len(n_old)==core.retro_get_memory_size(0)
    ct.memmove(mem,n_old,len(n_old))
    state=ct.create_string_buffer(s_old)
    assert core.retro_unserialize(state,len(s_old)), 'old machine state rejected'
    assert sram(core)==n_old, 'selected native snapshot mismatched after real core state load'
    journal('write','branch',WORK/'n_state.bin')
    for n in range(90,221):frame(core,clock,n)
    progress1=sram(core)
    assert progress1[0x188]==1 and progress1[0x189]==0xfe,progress1[0x180:0x190].hex()
    (WORK/'n_branch1.bin').write_bytes(progress1)
    journal('write','branch',WORK/'n_branch1.bin')
    # The real synthetic game receives a second actual KEY_A edge in a later frame window.
    for n in range(100,141):frame(core,clock,n)
    progress2=sram(core)
    assert progress2[0x188]==2 and progress2[0x189]==0xfd,progress2[0x180:0x190].hex()
    (WORK/'n_branch.bin').write_bytes(progress2)
    journal('write','branch',WORK/'n_branch.bin')
    journal('write','branch',WORK/'n_branch.bin')
    assert files()==original, 'CANONICAL JOURNAL CHANGED BY BRANCH EXECUTION'
    print('PASS DS D1 BRANCH real in-game EEPROM write 1->2, 4 branch journal flushes, N_BRANCH',digest(progress2),'CANONICAL_UNCHANGED',len(original),flush=True)
  finally:close(core)

def cold(which):
  original=json.loads((WORK/'canonical-files.json').read_text())
  assert files()==original
  native_path=WORK / ('n_latest.bin' if which=='canonical' else 'n_branch.bin')
  journal('read',which,WORK/(which+'-readback.bin'))
  native=(WORK/(which+'-readback.bin')).read_bytes()
  assert native==native_path.read_bytes()
  core,rom_buffer,rom,render,audio,clock,refs=begin(which+'-cold')
  try:
    mem=core.retro_get_memory_data(0);assert mem
    ct.memmove(mem,native,len(native))
    assert sram(core)==native
    for n in range(80):frame(core,clock,n)
    assert sram(core)==native,'Cold game mutated its native EEPROM unexpectedly'
    assert render[0]>=70
    assert files()==original,'CANONICAL JOURNAL CHANGED DURING COLD RESTART'
    counter=native[0x188];assert counter==(1 if which=='canonical' else 2)
    print('PASS DS D1 COLD',which,'in-game EEPROM counter',counter,'native SHA',digest(native),'canonical journal byte-identical',flush=True)
  finally:close(core)

def timeline_setup():
  active_timeline('setup',WORK/'s_old.bin',WORK/'n_state.bin',WORK/'n_branch.bin')
  original=json.loads((WORK/'canonical-files.json').read_text())
  assert files()==original,'Selector publication changed canonical PLSV'
  print('PASS DS persisted active timeline seeded from real melonDS EEPROM checkpoint and progressed branch',flush=True)

def timeline_cold():
  original=json.loads((WORK/'canonical-files.json').read_text())
  assert files()==original
  active=WORK/'active-cold-native.bin'
  active_timeline('resolve',active)
  data=active.read_bytes()
  assert data==(WORK/'n_branch.bin').read_bytes()
  assert data[0x188]==2 and data[0x189]==0xfd
  # A cold process reads only the selected latest branch PLSV generation.
  core,rom_buffer,rom,render,audio,clock,refs=begin('timeline-cold')
  try:
    mem=core.retro_get_memory_data(0);assert mem and len(data)==core.retro_get_memory_size(0)
    ct.memmove(mem,data,len(data))
    for n in range(80):frame(core,clock,n)
    assert sram(core)==data and sram(core)[0x188]==2
    for n in range(80,150):frame(core,clock,n)
    advanced=sram(core)
    assert advanced[0x188]==3 and advanced[0x189]==0xfc,advanced[0x180:0x190].hex()
    progressed=WORK/'n_branch_after_cold.bin';progressed.write_bytes(advanced)
    active_timeline('write',progressed)
    after=WORK/'active-after-write.bin';active_timeline('resolve',after)
    assert after.read_bytes()==advanced
    assert after.read_bytes()[0x188]==3 and after.read_bytes()[0x189]==0xfc
    assert files()==original,'Canonical PLSV changed after resumed DS branch write'
    print('PASS DS FRESH-PROCESS POINTER -> latest N_branch EEPROM counter=2 -> REAL melonDS KEY_A -> counter=3; canonical three-file SHA frozen',flush=True)
  finally:close(core)

if __name__=='__main__':
  if len(sys.argv)==2:
    dict(first=first,branch=branch,canonical_cold=lambda:cold('canonical'),branch_cold=lambda:cold('branch'),
         timeline_setup=timeline_setup,timeline_cold=timeline_cold)[sys.argv[1]]()
  elif len(sys.argv)==1:
    for stage in ('first','branch','canonical_cold','branch_cold','timeline_setup','timeline_cold'):
      log=WORK.parent/f'ds-{stage}.log'
      with log.open('w') as out:
        r=subprocess.run([sys.executable,str(Path(__file__).resolve()),stage],stdout=out,stderr=subprocess.STDOUT)
      if r.returncode:
        print(log.read_text()[-3200:]);raise SystemExit('DS D1 FAILED at '+stage+' exit '+str(r.returncode))
      print('\n'.join(x for x in log.read_text().splitlines() if x.startswith('PASS DS D1')))
    print('PASS D1 PINNED MELONDS DISPOSABLE BRANCH ISOLATION')
  else:raise SystemExit('unknown command')
