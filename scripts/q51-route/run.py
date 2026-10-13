"""Compare Q51 routes without changing the actual candidate artifact."""
import argparse,hashlib,importlib.util,itertools,json,os,platform,shutil,subprocess,zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
fixture_spec=importlib.util.spec_from_file_location('fixture_scope',HERE/'fixture_scope.py');fixture_module=importlib.util.module_from_spec(fixture_spec);fixture_spec.loader.exec_module(fixture_module);inspect_fixture=fixture_module.inspect
spec=importlib.util.spec_from_file_location('reference',HERE.parent/'q51-scratch/run.py');reference=importlib.util.module_from_spec(spec);spec.loader.exec_module(reference)
P=json.loads((HERE/'protocol.json').read_text())
sha=reference.sha;save=reference.save
CLASS='com/integrallis/models/backend/nativekernel/RustGgufBatchedMatrixKernel.class'
def ablate(source,arm):
 line='return supports(type) && (type != GgufTensorType.Q5_1 || (X86 && batchSize == 1));'
 assert source.count(line)==1,'eligibility source no longer matches reviewed selection'
 return source.replace(line,'return supports(type);' if arm=='native' else 'return supports(type) && type != GgufTensorType.Q5_1;')
def entries(jar):
 with zipfile.ZipFile(jar) as z:
  assert z.testzip() is None
  return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
def prepare(a,out):
 assert P['dispatchReady'],'await final composed source freeze'
 models=a.models.resolve();core=a.core.resolve()
 expected=json.loads((HERE/f'expected-{a.arch}.json').read_text())
 assert subprocess.check_output(['git','-C',str(models),'rev-parse','HEAD'],text=True).strip()==P['modelsRevision']
 assert sha(core)==P['vectorsSha256'] and sha(HERE/'Q51CausalProof.java')==P['harnessSha256']
 runtime,_=reference.prepare_reference(out)
 save(out/'fixture-scope.json',inspect_fixture(out/'causal-q51.gguf'))
 source=models/'backend-native/build/paired-runtime'
 assert source.is_dir()
 for arm in P['variants']:
  folder=out/'final'/arm;folder.mkdir(parents=True)
  for jar in source.glob('*.jar'):
   if jar.name.startswith('vectors-core-'):jar=core
   shutil.copy2(jar,folder/jar.name)
 provider=models/'backend-native/src/main/java/com/integrallis/models/backend/nativekernel/RustGgufBatchedMatrixKernel.java'
 for arm in ('native','java'):
  folder=out/'final'/arm;src=folder/provider.name;src.write_text(ablate(provider.read_text(),arm));classes=folder/'classes';classes.mkdir()
  reference.run(out,'compile-'+arm,['javac','--release','25','--add-modules','jdk.incubator.vector','-cp',str(folder/'*'),'-d',str(classes),str(src)])
  jar=next(folder.glob('backend-native-*.jar'));tmp=jar.with_suffix('.tmp');before=entries(jar)
  with zipfile.ZipFile(jar) as old,zipfile.ZipFile(tmp,'w') as new:
   for entry in old.infolist():new.writestr(entry,(classes/CLASS).read_bytes() if entry.filename==CLASS else old.read(entry))
  tmp.replace(jar);after=entries(jar)
  assert {n for n in before.keys()|after.keys() if before.get(n)!=after.get(n)}=={CLASS}
 probe=out/'probe';probe.mkdir();reference.run(out,'compile-probe',['javac','--release','25','--add-modules','jdk.incubator.vector','-cp',str(out/'final/hybrid/*'),'-d',str(probe),str(HERE/'Q51CausalProof.java')])
 native=models/'backend-native/build/rust-target/release/libjmodels_kernels.so';assert native.is_file()
 artifacts={a:{j.name:{'sha256':sha(j),'entries':entries(j)} for j in (out/'final'/a).glob('*.jar')} for a in P['variants']}
 assert {n:v['entries'] for n,v in artifacts['hybrid'].items()}==expected['jarEntries']['candidate']
 assert sha(native)==expected['nativeSha256']
 save(out/'route-provenance.json',{'protocol':P,'artifacts':artifacts,'nativeSha256':sha(native),'cpu':subprocess.check_output(['lscpu'],text=True),'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),'affinity':sorted(os.sched_getaffinity(0)),'memory':Path('/proc/meminfo').read_text(),'providerSourceSha256':sha(provider),'candidateActualJarUnmodified':True})
 save(out/'route-prepared.json',{'native':str(native),'model':str(out/'causal-q51.gguf'),'tasks':str(runtime/'tasks.json')})
def command(out,arm,phase,index):
 state=json.loads((out/'route-prepared.json').read_text())
 return ['/usr/bin/time','-v','java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g',f'-Dmodels.native.kernels.threads={P["threads"]}',f'-Dvectors.gguf.threads={P["threads"]}','-cp',f'{out}/final/{arm}/*:{out}/probe','experiments.q5.Q51CausalProof',phase,state['model'],state['native'],state['tasks'],str(index),'native']
def rows(path):return [json.loads(l) for l in path.read_text().splitlines() if l.startswith('{')]
def signature(records):
 values=[r for r in records if r['kind']=='request'];assert len(values)==P['requestsInNumericalCohort']
 assert len({r['id'] for r in values})==len(values)
 assert all(len(r['fullLogitSha256'])==P['snapshotsPerRequest'] for r in values)
 return {r['id']:(r['inputTokens'],r['fullLogitSha256'],r['greedyTokens'],r['prediction']) for r in values}
def main():
 p=argparse.ArgumentParser();p.add_argument('phase',choices=['prepare','correctness','timing']);p.add_argument('--out',required=True,type=Path);p.add_argument('--arch',choices=['x86','arm']);p.add_argument('--models',type=Path);p.add_argument('--core',type=Path);p.add_argument('--arm',choices=P['variants']);p.add_argument('--pair',type=int,choices=range(P['triplets']));a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
 if a.phase=='prepare':prepare(a,out);return
 if a.phase=='correctness':
  expected=a.arm=='native' or (a.arm=='hybrid' and platform.machine()=='x86_64')
  reference.run(out,'routing-'+a.arm,command(out,a.arm,'routing-prior' if a.arm=='native' else 'routing',str(expected).lower()))
  reference.run(out,'cohort-'+a.arm,command(out,a.arm,'cohort',0),1800)
  current=signature(rows(out/f'cohort-{a.arm}.stdout'))
  if a.arm!='hybrid':assert current==signature(rows(out/'cohort-hybrid.stdout')),'route numerical contract changed'
  return
 for arm in P['variants']:
  execution=json.loads((out/f'cohort-{arm}.execution.json').read_text());assert execution['exitCode']==0 and not execution['timedOut']
 order=list(itertools.permutations(P['variants']))[a.pair%6]
 for arm in order:
  assert not (out/f'timing-{a.pair}-{arm}.execution.json').exists(),'do not overwrite observations'
  reference.run(out,f'timing-{a.pair}-{arm}',command(out,arm,'timing',a.pair),P['perProcessSeconds'])
 save(out/f'triplet-{a.pair}.complete.json',{'pair':a.pair,'order':order})
if __name__=='__main__':main()
