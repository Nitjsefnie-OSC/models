"""Bounded diagnostic: fixed scalar activation versus final vector activation."""
import hashlib,json,os,pathlib,subprocess,time
out=pathlib.Path('/work/out');config=json.loads(pathlib.Path('confirmation.json').read_text())
def status(value):
 (out/'STATUS').write_text(value+'\n');subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
def run(command,name,timeout=600):
 start=time.monotonic()
 with(out/(name+'.stdout')).open('w')as stdout,(out/(name+'.stderr')).open('w')as stderr:r=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
 (out/(name+'.execution.json')).write_text(json.dumps({'command':command,'exitCode':r.returncode,'wallSeconds':time.monotonic()-start},indent=2)+'\n');assert r.returncode==0,(name,r.returncode)
 return [json.loads(l)for l in(out/(name+'.stdout')).read_text().splitlines()if l.startswith('{')]
status('BUILDING');run(['/root/.cargo/bin/cargo','build','--locked','--release','--manifest-path','rust/native/Cargo.toml'],'build-native')
model=next(m for m in config['models']if m['key']=='qwen-0.8b');file='qwen-0.8b.gguf';run(['curl','-fL','--retry','3',model['downloadUri'],'-o',file],'download');assert hashlib.sha256(pathlib.Path(file).read_bytes()).hexdigest()==model['sha256']
base=['/usr/bin/time','-v','/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms2g','-Xmx10g'];klass='com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof'
def execute(arm,phase,name,extra=[]):return run([*base,'-cp',f'lib/{arm}/*:route-proof.jar',klass,phase,file,'/work/rust/native/target/release/libjmodels_kernels.so','data/tasks.json',*extra],name)
cohorts={}
for arm in ['native','scalar']:
 status('COHORT_'+arm);rows=execute(arm,'cohort',arm+'-cohort');cohorts[arm]={r['id']:r for r in rows if r.get('kind')=='request'};assert len(cohorts[arm])==148
changes=[]
for id,r in cohorts['native'].items():
 for k in ['fullLogitSha256','inputTokens','greedyTokens','prediction']:
  if r[k]!=cohorts['scalar'][id][k]:changes.append({'id':id,'field':k})
(out/'correctness.json').write_text(json.dumps({'requests':148,'vectors':1332,'changes':changes},indent=2)+'\n');assert not changes
for pair in range(5):
 order=['native','scalar']if pair%2==0 else['scalar','native'];records={}
 for arm in order:
  status(f'TIMING_{pair}_{arm}');rows=execute(arm,'timing',f'pair{pair}-{arm}',[str(pair)]);records[arm]=[r for r in rows if r.get('kind')=='request'];assert len(records[arm])==35
 for a,b in zip(records['native'],records['scalar']):assert all(a[k]==b[k]for k in ['id','repeat','inputTokens','fullLogitSha256','greedyTokens','prediction'])
status('COMPLETE')
