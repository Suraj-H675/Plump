"""Independent, ignored D1 mGBA with original button-driven SRAM counter and MIT header."""
import ctypes as ct
import os
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from importlib.machinery import SourceFileLoader
REPO=Path(__file__).resolve().parents[2]
WORK=Path(os.environ['PLUMP_P4_D_WORK']).resolve()/'gba'
CORE=REPO/'app/build/p4-a-work/mgba-host/mgba_libretro.so'
ROM=Path(os.environ['PLUMP_P4_D_GBA_FIXTURE']).resolve()
SHA=hashlib.sha256(ROM.read_bytes()).hexdigest()
M=SourceFileLoader('p4c_harness',str(REPO/'tests/roms/verify-p4-c-checkpoint.py')).load_module()
assert hashlib.sha256(CORE.read_bytes()).hexdigest()==M.PINS['gba']['core_sha256']
M.PINS['gba']['rom_sha256']=SHA # controlled original Plump generated fixture, pinned core unchanged
CLASSES=Path(os.environ['PLUMP_P4_D_JAVA_CLASSES']).resolve()
def digest(data):return hashlib.sha256(data).hexdigest()
def allcanonical():
 return {p.name:digest(p.read_bytes()) for p in sorted((WORK/'canonical-plsv').glob(SHA+'*'))}
def journal(operation,timeline,source):
 subprocess.run(['java','-cp',str(CLASSES),'dev.plump.emulator.JournalCli',operation,str(WORK/(timeline+'-plsv')),SHA,str(source)],check=True)
def active_timeline(operation,*extra):
 subprocess.run(['java','-cp',str(CLASSES),'dev.plump.emulator.ActiveTimelineHostCli',
  operation,str(WORK),'gba',SHA,digest(CORE.read_bytes()),*[str(item) for item in extra]],check=True)
def begin(name,windows):
 folder=WORK/('core-'+name);folder.mkdir(parents=True,exist_ok=False)
 core,rb,rom,frames,audio,clock,refs=M.load_core('gba',str(CORE),str(ROM),folder)
 @M.Input
 def inputs(port,device,index,ident):return int(port==0 and device==1 and ident==8 and any(lo<=clock[0]<hi for lo,hi in windows))
 core.retro_set_input_state.argtypes=[M.Input];core.retro_set_input_state(inputs);refs.append(('real-key-A',inputs))
 return core,rb,rom,frames,audio,clock,refs
def native(core):
 size=core.retro_get_memory_size(0);ptr=core.retro_get_memory_data(0)
 assert ptr and size in (32768,131072);return ct.string_at(ptr,size)
def run(core,clock,frames):
 for n in frames:clock[0]=n;core.retro_run()
def close(core):core.retro_unload_game();core.retro_deinit()
def check_byte(data,counter):
 assert len(data)==32768 and data[0x180:0x182]==bytes([counter,counter^255]),(len(data),data[0x180:0x182].hex(),counter)
def first():
 assert not WORK.exists();WORK.mkdir(parents=True)
 core,rb,rom,frames,audio,clock,refs=begin('canonical',[(115,128)])
 try:
  run(core,clock,range(90));old=native(core);assert len(old)==32768 and old[0x180:0x182]==bytes([255,255])
  state_len=core.retro_serialize_size();assert 0<state_len<=64*1024*1024
  state=ct.create_string_buffer(state_len);assert core.retro_serialize(state,state_len)
  assert native(core)==old
  (WORK/'s_old.bin').write_bytes(state.raw);(WORK/'n_state.bin').write_bytes(old)
  run(core,clock,range(90,165));latest=native(core);check_byte(latest,1)
  (WORK/'n_latest.bin').write_bytes(latest)
  journal('write','canonical',WORK/'n_latest.bin');journal('write','canonical',WORK/'n_latest.bin')
  frozen=allcanonical();assert len(frozen)==3
  (WORK/'frozen.json').write_text(json.dumps(frozen,indent=2))
  print('PASS GBA D1 FIRST state',digest(state.raw),'N_state',digest(old),'N_latest',digest(latest),'PLSV files',json.dumps(frozen),flush=True)
 finally:close(core)
def branch():
 frozen=json.loads((WORK/'frozen.json').read_text());assert allcanonical()==frozen
 core,rb,rom,frames,audio,clock,refs=begin('historical',[(115,128),(175,188)])
 try:
  run(core,clock,range(8))
  old=(WORK/'n_state.bin').read_bytes();assert len(native(core))==len(old)
  ct.memmove(core.retro_get_memory_data(0),old,len(old))
  state=(WORK/'s_old.bin').read_bytes();buf=ct.create_string_buffer(state)
  assert core.retro_unserialize(buf,len(state)), 'mGBA rejected actual old machine state'
  assert native(core)==old, 'mGBA state/native pair mismatch'
  journal('write','branch',WORK/'n_state.bin')
  run(core,clock,range(90,150));progress1=native(core);check_byte(progress1,1)
  (WORK/'n_branch1.bin').write_bytes(progress1);journal('write','branch',WORK/'n_branch1.bin')
  run(core,clock,range(150,240));progress2=native(core);check_byte(progress2,2)
  (WORK/'n_branch.bin').write_bytes(progress2)
  journal('write','branch',WORK/'n_branch.bin');journal('write','branch',WORK/'n_branch.bin')
  assert allcanonical()==frozen, 'CANONICAL PLSV changed after branch real GBA SRAM writes'
  print('PASS GBA D1 BRANCH KEY_A hardware SRAM counter1->counter2 4 branch writes branchSHA',digest(progress2),'canonical slots unchanged',flush=True)
 finally:close(core)
def cold(timeline):
 frozen=json.loads((WORK/'frozen.json').read_text());assert allcanonical()==frozen
 journal('read',timeline,WORK/(timeline+'-readback.bin'))
 data=(WORK/(timeline+'-readback.bin')).read_bytes()
 assert data==(WORK/('n_latest.bin' if timeline=='canonical' else 'n_branch.bin')).read_bytes()
 original=1 if timeline=='canonical' else 2
 check_byte(data,original)
 core,rb,rom,frames,audio,clock,refs=begin(timeline+'-cold',[(115,128)])
 try:
  memory=core.retro_get_memory_data(0);capacity=core.retro_get_memory_size(0)
  assert memory and capacity in (len(data),131072)
  if capacity!=len(data):ct.memset(memory,255,capacity)
  ct.memmove(memory,data,len(data))
  run(core,clock,range(80));check_byte(native(core),original)
  # New real KEY_A edge must increment the in-game counter from the cold-restored SRAM.
  run(core,clock,range(80,150));after=native(core);check_byte(after,original+1)
  assert allcanonical()==frozen,'Canonical file changed during GBA cold game execution'
  print('PASS GBA D1 COLD',timeline,'restored counter',original,'in-game KEY_A advanced',original+1,'PLSV untouched',flush=True)
 finally:close(core)

def timeline_setup():
 active_timeline('setup',WORK/'s_old.bin',WORK/'n_state.bin',WORK/'n_branch.bin')
 frozen=json.loads((WORK/'frozen.json').read_text())
 assert allcanonical()==frozen,'Selector publication changed canonical PLSV'
 print('PASS GBA persisted active timeline seeded from real mGBA SRAM checkpoint and progressed branch',flush=True)

def timeline_cold():
 frozen=json.loads((WORK/'frozen.json').read_text());assert allcanonical()==frozen
 active=WORK/'active-cold-native.bin'
 active_timeline('resolve',active)
 data=active.read_bytes();assert data==(WORK/'n_branch.bin').read_bytes()
 check_byte(data,2)
 # This new native-core process restores only the latest branch PLSV payload.
 # It never consumes S_old, so a later native generation cannot be paired with it.
 core,rb,rom,frames,audio,clock,refs=begin('timeline-cold',[(115,128)])
 try:
  memory=core.retro_get_memory_data(0);capacity=core.retro_get_memory_size(0)
  assert memory and capacity in (len(data),131072)
  if capacity!=len(data):ct.memset(memory,255,capacity)
  ct.memmove(memory,data,len(data))
  run(core,clock,range(80));check_byte(native(core),2)
  run(core,clock,range(80,150));advanced=native(core);check_byte(advanced,3)
  progressed=WORK/'n_branch_after_cold.bin';progressed.write_bytes(advanced)
  active_timeline('write',progressed)
  after=WORK/'active-after-write.bin';active_timeline('resolve',after)
  assert after.read_bytes()==advanced and digest(after.read_bytes())==digest(advanced)
  check_byte(after.read_bytes(),3)
  assert allcanonical()==frozen,'Canonical files changed after process-resumed branch write'
  print('PASS GBA FRESH-PROCESS POINTER -> latest N_branch counter=2 -> REAL mGBA KEY_A -> counter=3; canonical three-file SHA frozen',flush=True)
 finally:close(core)
if __name__=='__main__':
 if len(sys.argv)==2:
  dict(first=first,branch=branch,canonical_cold=lambda:cold('canonical'),branch_cold=lambda:cold('branch'),
       timeline_setup=timeline_setup,timeline_cold=timeline_cold)[sys.argv[1]]()
 elif len(sys.argv)==1:
  for stage in ('first','branch','canonical_cold','branch_cold','timeline_setup','timeline_cold'):
   log=WORK.parent/f'gba-{stage}.log'
   with log.open('w') as out:p=subprocess.run([sys.executable,str(Path(__file__).resolve()),stage],stdout=out,stderr=subprocess.STDOUT)
   if p.returncode:
    print(log.read_text()[-2500:]);raise SystemExit('GBA D1 failed at '+stage+' exit '+str(p.returncode))
   print('\n'.join(x for x in log.read_text().splitlines() if x.startswith('PASS GBA D1')))
  print('PASS D1 PINNED MGBA ORIGINAL CARTRIDGE SRAM BRANCH ISOLATION')
 else:raise SystemExit('unknown arg')
