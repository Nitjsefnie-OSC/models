import pathlib,subprocess,os,json,hashlib
out=pathlib.Path('/work/out')
def status(s):
 (out/'STATUS').write_text(s+'\n');subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
def run(cmd,n):
 with (out/(n+'.stdout')).open('w') as a,(out/(n+'.stderr')).open('w') as b:r=subprocess.run(cmd,stdout=a,stderr=b,timeout=900)
 (out/(n+'.execution.json')).write_text(json.dumps({'command':cmd,'exitCode':r.returncode},indent=2)+'\n');assert r.returncode==0,n
status('BUILD');run(['/root/.cargo/bin/cargo','build','--locked','--release','--manifest-path','rust/Cargo.toml'],'build')
f=pathlib.Path('rust/target/release/libjmodels_kernels.so');(out/'native.sha256').write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'\n')
records={}
for arm in ['baseline','adapter']:
 status('PROBE_'+arm);run(['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-cp',f'lib/{arm}/*:probe.jar','com.integrallis.models.backend.nativekernel.FfmArrayAllocationProbe','/work/rust/target/release/libjmodels_kernels.so'],arm)
 records[arm]=[json.loads(l) for l in (out/(arm+'.stdout')).read_text().splitlines() if l.startswith('{')];assert len(records[arm])==10
for b,a in zip(records['baseline'],records['adapter']):
 assert (b['tokens'],b['sample'],b['calls'],b['stateSha256'],b['outputSha256'])==(a['tokens'],a['sample'],a['calls'],a['stateSha256'],a['outputSha256'])
(out/'comparison.json').write_text(json.dumps(records,indent=2)+'\n');status('COMPLETE')
