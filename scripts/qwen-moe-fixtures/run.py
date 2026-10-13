"""Bounded real-weight consistency. Deliberately no performance/quality claim."""
import argparse, hashlib, json, os, shutil, subprocess, sys, time, urllib.request, zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
P=json.loads((HERE/'protocol.json').read_text())
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for block in iter(lambda:f.read(8*1024*1024),b''):h.update(block)
 return h.hexdigest()
def save(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
def process_snapshot(parent):
 # Read only this invocation's descendants. No timing gate uses these diagnostics.
 processes={}
 for directory in Path('/proc').iterdir():
  if not directory.name.isdigit():continue
  try:
   text=(directory/'stat').read_text();fields=text[text.rfind(')')+2:].split()
   processes[int(directory.name)]={'pid':int(directory.name),'ppid':int(fields[1]),'state':fields[0],
     'minorFaults':int(fields[7]),'majorFaults':int(fields[9]),'userTicks':int(fields[11]),'systemTicks':int(fields[12]),
     'status':(directory/'status').read_text(),'io':(directory/'io').read_text()}
  except (OSError,ValueError,IndexError):continue
 selected={parent};changed=True
 while changed:
  children={pid for pid,r in processes.items() if r['ppid'] in selected};changed=bool(children-selected);selected|=children
 return [processes[pid] for pid in sorted(selected) if pid in processes]
def run(out,name,command,seconds):
 start=time.time()
 with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
  process=subprocess.Popen(['timeout','--signal=TERM','--kill-after=15',str(seconds),*map(str,command)],stdout=stdout,stderr=stderr)
  while True:
   try:code=process.wait(timeout=30);break
   except subprocess.TimeoutExpired:
    record={'elapsedSeconds':time.time()-start,'processes':process_snapshot(process.pid),'memory':Path('/proc/meminfo').read_text()}
    with (out/(name+'.resources.jsonl')).open('a') as f:f.write(json.dumps(record)+'\n')
    print(json.dumps({'phase':name,'elapsedSeconds':round(record['elapsedSeconds']),
      'processes':[{k:r[k] for k in ['pid','ppid','state','majorFaults','userTicks','systemTicks']} for r in record['processes']],
      'stdoutBytes':(out/(name+'.stdout')).stat().st_size}),flush=True)
 save(out/(name+'.execution.json'),{'exitCode':code,'elapsedSeconds':time.time()-start,'secondsLimit':seconds,'command':list(map(str,command))})
 if code:raise RuntimeError(f'{name} failed: {code}')
def verify_records(path):
 rows=[json.loads(l) for l in path.read_text().splitlines() if l.startswith('{')]
 fresh=[r for r in rows if r.get('kind')=='fresh-session']
 assert len(fresh)==6 and all(len(r['hashes'])==3 for r in fresh)
 assert len({r['id'] for r in fresh})==2
 for name,count in [('implicit-reset',2),('batch-parity',2),('complete',1)]:assert sum(r.get('kind')==name for r in rows)==count
 return rows

def main():
 a=argparse.ArgumentParser();a.add_argument('phase',choices=['prepare','java','native','analyze']);a.add_argument('--model',required=True);a.add_argument('--models',type=Path,required=True);a.add_argument('--core',type=Path,required=True);a.add_argument('--out',type=Path,required=True);args=a.parse_args()
 out=args.out.resolve();out.mkdir(parents=True,exist_ok=True);model=next(x for x in P['models'] if x['architecture']==args.model)
 runtime=out/'runtime'; weights=out/'model.gguf'
 native=args.models.resolve()/'backend-native/build/rust-target/release/libjmodels_kernels.so'
 if args.phase=='prepare':
  assert subprocess.check_output(['git','-C',str(args.models),'rev-parse','HEAD'],text=True).strip()==P['modelsRevision']
  assert sha(args.core)==P['vectorsSha256']
  source=args.models/'backend-native/build/paired-runtime';runtime.mkdir()
  for p in source.glob('*.jar'):
   if not p.name.startswith('vectors-core-'):shutil.copyfile(p,runtime/p.name)
  shutil.copyfile(args.core,runtime/'vectors-core-0.1.30.jar')
  assert sha(runtime/'backend-java-0.3.59.jar')==P['backendSha256']
  for p in runtime.glob('*.jar'):
   with zipfile.ZipFile(p) as z:assert z.testzip() is None
  save(out/'provenance.json',{'protocol':P,'model':model,'jars':{p.name:sha(p) for p in runtime.glob('*.jar')},'nativeSha256':sha(native),'host':subprocess.check_output(['uname','-a'],text=True),'memory':Path('/proc/meminfo').read_text(),'cpu':subprocess.check_output(['lscpu'],text=True),'scope':'Demand-paged model consistency, no timing or resource qualification'})
  assert shutil.disk_usage(out).free>=model['sizeBytes']+6*1024**3,'Insufficient disk before model download'
  run(out,'download',['curl','--fail','--location','--retry','2','--connect-timeout','30','--max-time','1800',model['downloadUri'],'-o',weights],1900)
  assert weights.stat().st_size==model['sizeBytes'] and sha(weights)==model['sha256'],'Pinned model mismatch'
  save(out/'weight-receipt.json',{'bytes':weights.stat().st_size,'sha256':sha(weights),'architecture':model['architecture']})
  (out/'classes').mkdir()
  run(out,'compile',['javac','--release','25','--add-modules','jdk.incubator.vector','-cp',str(runtime/'*'),'-d',out/'classes',HERE/'RealMoeFixture.java'],120)
 elif args.phase in ('java','native'):
  assert (out/'weight-receipt.json').exists()
  run(out,args.phase,['java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g','-Dmodels.native.kernels.threads=4','-Dvectors.gguf.threads=4','-cp',str(runtime/'*')+':'+str(out/'classes'),'experiments.moe.RealMoeFixture',weights,native,HERE.parent/'bonsai-paired/data/tasks.json',model['architecture'],args.phase,out/(args.phase+'-snapshots')],2700)
  verify_records(out/(args.phase+'.stdout'))
 else:
  for arm in ['java','native']:verify_records(out/(arm+'.stdout'))
  save(out/'summary.json',{'complete':True,'modelsRevision':P['modelsRevision'],'vectorsSha256':P['vectorsSha256'],'architecture':model['architecture'],'perBackend':{'freshSessions':6,'fullLogitSnapshots':18,'implicitResetChecks':2,'serialVersusChunkedChecks':2},'scope':'Each backend exact across repeat/reset/chunk. Cross-backend numeric equality and model task quality are not asserted by this fixture.'})
if __name__=='__main__':main()
