"""Hash-bound actual composed Q51 proof; historical failures stay separate."""
import argparse,hashlib,importlib.util,itertools,json,os,pathlib,platform,shutil,subprocess,tarfile,urllib.request,zipfile
H=pathlib.Path(__file__).resolve().parent
C=json.loads((H/'protocol.json').read_text())
spec=importlib.util.spec_from_file_location('reference',pathlib.Path('harness/scripts/q51-scratch/run.py').resolve());ref=importlib.util.module_from_spec(spec);spec.loader.exec_module(ref)
sha=ref.sha;save=ref.save;CLASS='com/integrallis/models/backend/nativekernel/RustGgufBatchedMatrixKernel.class'
def entries(p):
 with zipfile.ZipFile(p) as z:
  assert z.testzip() is None
  return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')}
def prepare(a,o):
 assert C['dispatchReady'],'final freeze is pending'
 b=a.bundle.resolve();m=json.loads((b/'manifest.json').read_text());assert sha(b/'manifest.json')==C['runtimeManifestSha256']
 for n,h in m['files'].items():assert sha(b/n)==h,n
 assert m['modelsBaseRevision']==C['modelsRevision'] and m['sourcePatchSha256']==C['modelsSourcePatchSha256']
 assert sha(H/'Q51CausalProof.java')==C['harnessSha256']
 runtime,_=ref.prepare_reference(o)
 # Keep the exact derived fixture and original public task cohort.
 save(o/'fixture.json',{'sha256':sha(o/'causal-q51.gguf'),'tasksSha256':sha(runtime/'tasks.json')})
 for arm in ('candidate','hybrid'):
  shutil.copytree(b/'lib/candidate',o/'lib'/arm)
 assert sha(next((o/'lib/candidate').glob('vectors-core-*.jar')))==C['vectorsSha256']
 source=b/'source/RustGgufBatchedMatrixKernel.java';old='return supports(type) && (type != GgufTensorType.Q5_1 || (batchSize >= 1 && batchSize <= 32));';s=source.read_text();assert s.count(old)==1
 patched=o/source.name
 hybrid=s.replace(old,'return supports(type) && (type != GgufTensorType.Q5_1 || (Q51_CONTROL_X86 && batchSize == 1));')
 anchor='private static final int MAX_GROUPED_MATRICES = 16;';assert hybrid.count(anchor)==1
 hybrid=hybrid.replace(anchor,anchor+'\n  private static final boolean Q51_CONTROL_X86 = NativeKernelPlatform.current().id().endsWith("x86_64");')
 patched.write_text(hybrid)
 classes=o/'ablation';classes.mkdir();ref.run(o,'compile-hybrid',['javac','--release','25','--add-modules','jdk.incubator.vector','-cp',str(o/'lib/candidate/*'),'-d',str(classes),str(patched)])
 jar=next((o/'lib/hybrid').glob('backend-native-*.jar'));before=entries(jar);tmp=jar.with_suffix('.tmp')
 with zipfile.ZipFile(jar) as oldzip,zipfile.ZipFile(tmp,'w') as new:
  for e in oldzip.infolist():new.writestr(e,(classes/CLASS).read_bytes() if e.filename==CLASS else oldzip.read(e))
 tmp.replace(jar);after=entries(jar);assert {n for n in before.keys()|after.keys() if before.get(n)!=after.get(n)}=={CLASS}
 archive=o/'published.tar.gz';urllib.request.urlretrieve(os.environ['GDN_REFERENCE_URL'],archive);assert sha(archive)==C['historicalPublishedPayloadSha256']
 published=o/'published-source';published.mkdir()
 with tarfile.open(archive) as tar:
  metadata=[m.name for m in tar.getmembers() if any(p.startswith('._') or p=='__MACOSX' for p in pathlib.Path(m.name).parts)]
  tar.extractall(published,members=[m for m in tar.getmembers() if m.name not in metadata],filter='data')
 (o/'lib/published').mkdir()
 for candidate in (o/'lib/candidate').glob('*.jar'):
  if candidate.name.startswith('vectors-core-'):
   matches=list((published/'lib/published').glob('vectors-core-*.jar'));assert len(matches)==1;source_jar=matches[0]
  else:source_jar=published/'lib/published'/candidate.name
  assert source_jar.is_file(),candidate.name
  if not candidate.name.startswith(('vectors-core-','backend-java-','backend-native-','models-api-')):
   assert sha(candidate)==sha(source_jar),'external dependency mismatch'
  shutil.copy2(source_jar,o/'lib/published'/source_jar.name)
 natives={}
 for name,rust in [('candidate',b/'rust/candidate'),('published',published/'rust/published')]:
  ref.run(o,'compile-native-'+name,['cargo','+1.96.0','build','--locked','--release','--manifest-path',str(rust/'Cargo.toml')],900)
  natives[name]=str(rust/'target/release/libjmodels_kernels.so')
 natives['hybrid']=natives['candidate']
 probe=o/'classes';probe.mkdir();ref.run(o,'compile-probe',['javac','--release','25','--add-modules','jdk.incubator.vector','-cp',str(o/'lib/candidate/*'),'-d',str(probe),str(H/'Q51CausalProof.java')])
 expected=json.loads((H/f'expected-{a.arch}.json').read_text());actual={p.name:entries(p) for p in (o/'lib/candidate').glob('*.jar')};assert actual==expected['jarEntries']['candidate'];assert {p.name:sha(p) for p in (o/'lib/candidate').glob('*.jar')}==expected['jarSha256']
 save(o/'provenance.json',{'protocol':C,'sourceManifest':m,'artifacts':{arm:{p.name:{'sha256':sha(p),'entries':entries(p)} for p in (o/'lib'/arm).glob('*.jar')} for arm in C['variants']},'nativeSha256':{n:sha(pathlib.Path(v)) for n,v in natives.items()},'rustCompiler':subprocess.check_output(['rustc','+1.96.0','-vV'],text=True),'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),'cpu':subprocess.check_output(['lscpu'],text=True),'memory':pathlib.Path('/proc/meminfo').read_text(),'affinity':sorted(os.sched_getaffinity(0)),'excludedMetadata':metadata,'ablationClassOnly':CLASS})
 save(o/'prepared.json',{'native':natives,'model':str(o/'causal-q51.gguf'),'tasks':str(runtime/'tasks.json')})
def command(o,arm,phase,index):
 s=json.loads((o/'prepared.json').read_text())
 return ['/usr/bin/time','-v','java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g',f'-Dmodels.native.kernels.threads={C["threads"]}',f'-Dvectors.gguf.threads={C["threads"]}','-cp',f'{o}/lib/{arm}/*:{o}/classes','experiments.q5.Q51CausalProof',phase,s['model'],s['native'][arm],s['tasks'],str(index),'native']
def rows(p):return [json.loads(l) for l in p.read_text().splitlines() if l.startswith('{') and json.loads(l).get('kind')=='request']
def signature(r):
 assert len(r)==148 and len({x['id'] for x in r})==148
 assert all(len(x['fullLogitSha256'])==9 and len(x['greedyTokens'])==8 for x in r)
 return {x['id']:(x['inputTokens'],x['fullLogitSha256'],x['greedyTokens'],x['prediction']) for x in r}
def main():
 p=argparse.ArgumentParser();p.add_argument('phase',choices=['prepare','cohort','timing']);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--bundle',type=pathlib.Path);p.add_argument('--arch',choices=['x86','arm']);p.add_argument('--arm',choices=C['variants']);p.add_argument('--pair',type=int,choices=range(C['triplets']));a=p.parse_args();o=a.out.resolve();o.mkdir(exist_ok=True,parents=True)
 if a.phase=='prepare':prepare(a,o);return
 if a.phase=='cohort':
  assert not (o/f'cohort-{a.arm}.execution.json').exists()
  if a.arm!='published':
   ref.run(o,'routing-'+a.arm,command(o,a.arm,'routing-prior' if a.arm=='candidate' else 'routing',str(a.arm=='candidate' or platform.machine()=='x86_64').lower()))
  ref.run(o,'cohort-'+a.arm,command(o,a.arm,'cohort',0),2400);sig=signature(rows(o/f'cohort-{a.arm}.stdout'))
  other='hybrid' if a.arm=='candidate' else 'candidate'
  if a.arm!='published' and (o/f'cohort-{other}.stdout').exists():assert sig==signature(rows(o/f'cohort-{other}.stdout'))
  return
 for arm in C['variants']:assert json.loads((o/f'cohort-{arm}.execution.json').read_text())['exitCode']==0
 order=list(itertools.permutations(C['variants']))[a.pair%6]
 for arm in order:
  assert not (o/f'timing-{a.pair}-{arm}.execution.json').exists()
  ref.run(o,f'timing-{a.pair}-{arm}',command(o,arm,'timing',a.pair),C['perProcessSeconds'])
 save(o/f'triplet-{a.pair}.complete.json',{'pair':a.pair,'order':order})
if __name__=='__main__':main()
