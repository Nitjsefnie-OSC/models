import hashlib,json,os,platform,subprocess,sys,time
from pathlib import Path
HERE=Path(__file__).resolve().parent
P=json.loads((HERE/os.environ.get('PINNED_WIDTH_PROTOCOL','protocol.json')).read_text())
ROOT=HERE.parent.parent

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
def run(command,prefix):
 start=time.monotonic()
 with prefix.with_suffix('.stdout').open('w') as stdout,prefix.with_suffix('.stderr').open('w') as stderr:
  result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=600)
 save(prefix.with_suffix('.execution.json'),{'command':command,'exitCode':result.returncode,'wallSeconds':time.monotonic()-start})
 assert result.returncode==0

def prepare(out):
 assert P['decoderRevision'] and len(P['decoderRevision'])==40
 files={'dot':'ops/PinnedReduction.java','decoder':'quant/Q5_1Dequantizer.java'}
 common=['quant/Dequantizer.java','quant/DequantizationChecks.java','quant/Float16.java']
 sourcebase='backend-java/src/main/java/com/integrallis/models/backend/purejava/'
 manifest={}
 for kernel in files:
  for arm in ('baseline','candidate'):
   where=out/kernel/arm;where.mkdir(parents=True,exist_ok=True)
   sources=[]
   for relative in [*files.values(),*common]:
    revision=P[kernel+'Revision'] if arm=='candidate' and relative==files[kernel] else P['baselineRevision']
    path=where/Path(relative).name
    path.write_bytes(subprocess.check_output(['git','show',revision+':'+sourcebase+relative],cwd=ROOT));sources.append(path)
   run(['javac','--release','25','-g:none','--add-modules','jdk.incubator.vector','-d',str(where),*[str(s) for s in sources],str(HERE/'WidthBench.java')],out/f'compile-{kernel}-{arm}')
   manifest[kernel+'-'+arm]={'sources':{s.name:sha(s) for s in sources},'classes':{str(p.relative_to(where)):sha(p) for p in where.rglob('*.class')}}
  baseline=manifest[kernel+'-baseline']['classes'];candidate=manifest[kernel+'-candidate']['classes']
  changed=[k for k in set(baseline)|set(candidate) if baseline.get(k)!=candidate.get(k)]
  assert changed and all(Path(name).name.startswith('PinnedReduction' if kernel=='dot' else 'Q5_1Dequantizer') for name in changed)
 save(out/'provenance.json',{'protocol':P,'compiled':manifest,'host':subprocess.check_output(['lscpu'],text=True),'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),'vmFlags':subprocess.check_output(['java','-XX:+PrintFlagsFinal','-version'],stderr=subprocess.STDOUT,text=True),'affinity':sorted(os.sched_getaffinity(0))})

def pair(out,kernel,index):
 signatures=[]
 for arm in (('baseline','candidate') if index%2==0 else ('candidate','baseline')):
  prefix=out/f'{kernel}-{index}-{arm}'
  run(['/usr/bin/time','-v','java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms256m','-Xmx2g','-cp',str(out/kernel/arm),'WidthBench',kernel,*([','.join(str(P['measuredCallsByKernel'][kernel][str(size)]) for size in P['shapes'])] if 'measuredCallsByKernel' in P else [])],prefix)
  records=[json.loads(l) for l in prefix.with_suffix('.stdout').read_text().splitlines() if l.startswith('{')]
  assert len([r for r in records if r['kind']=='measure'])==20
  signatures.append({'outputs':[r for r in records if r['kind']=='output'],'blocks':[(r['size'],r['block'],r['checksum']) for r in records if r['kind']=='measure']})
 assert signatures[0]==signatures[1],'public kernel numerical output changed'
 save(out/f'{kernel}-{index}.complete.json',{'pair':index,'kernel':kernel,'numericalExact':True})

out=Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=True)
if sys.argv[1]=='prepare':prepare(out)
else:pair(out,sys.argv[3],int(sys.argv[4]))
