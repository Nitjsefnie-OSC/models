import pathlib,json,subprocess,os,hashlib
out=pathlib.Path('/work/out');cfg=json.loads(pathlib.Path('factorial.json').read_text())
def status(s):
 (out/'STATUS').write_text(s+'\n');subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
def run(cmd,name):
 with (out/(name+'.stdout')).open('w') as a,(out/(name+'.stderr')).open('w') as b:r=subprocess.run(cmd,stdout=a,stderr=b,timeout=900)
 (out/(name+'.execution.json')).write_text(json.dumps({'command':cmd,'exitCode':r.returncode},indent=2)+'\n');assert r.returncode==0,name
for arm in ['published','projection','combined']:
 status('BUILD_'+arm);run(['/root/.cargo/bin/cargo','build','--locked','--release','--manifest-path',f'rust/{arm}/Cargo.toml'],'build-'+arm)
 f=pathlib.Path(f'rust/{arm}/target/release/libjmodels_kernels.so');(out/(arm+'-native.sha256')).write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'\n')
m=cfg['model'];status('DOWNLOAD');run(['curl','-fL','--retry','3',m['downloadUri'],'-o','model.gguf'],'download')
h=hashlib.sha256()
with open('model.gguf','rb') as f:
 for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
assert h.hexdigest()==m['sha256']
for arm in cfg['arms']:
 status('COHORT_'+arm);native='published' if arm=='activation' else arm
 run(['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms2g','-Xmx10g','-cp',f'lib/{arm}/*:route-proof.jar:factorial.jar','com.integrallis.models.backend.purejava.qwen35.QualityFactorial','model.gguf',f'/work/rust/{native}/target/release/libjmodels_kernels.so','data/tasks.json'],arm)
 rows=[json.loads(l) for l in (out/(arm+'.stdout')).read_text().splitlines() if l.startswith('{')];assert sum(r['kind']=='request' for r in rows)==148
status('COMPLETE')
