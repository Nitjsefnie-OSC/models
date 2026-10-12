import hashlib,json,os,pathlib,subprocess,time

root=pathlib.Path('/work');out=root/'out';out.mkdir(exist_ok=True)
def run(command,name,timeout=1800):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
    if result.returncode:raise RuntimeError(f'{name}: exit {result.returncode}')
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)

status('BUILDING_NATIVE')
for variant in ['auto','sequential','sequential-aligned']:
    run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{variant}/Cargo.toml'],f'build-{variant}')
libraries=[str(root/f'rust/{v}/target/release/libjmodels_kernels.so') for v in ['auto','sequential','sequential-aligned']]
(out/'native-hashes.json').write_text(json.dumps({p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for p in libraries},indent=2))
java=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
      '-XX:ActiveProcessorCount=4','-Xms2g','-Xmx10g','-Dmodels.purejava.maxContextLength=2048',
      '-cp','lib/*:gdn.jar']
for threads in [1,4]:
    status(f'SYNTHETIC_{threads}')
    run(java[:-2]+[f'-Dmodels.native.kernels.threads={threads}']+java[-2:]+[
        'com.integrallis.models.backend.purejava.qwen35.GdnProof',*libraries],f'correctness-{threads}')
    status(f'MICRO_{threads}')
    run(java[:-2]+[f'-Dmodels.native.kernels.threads={threads}']+java[-2:]+[
        'com.integrallis.models.backend.purejava.qwen35.GdnCampaign',*libraries,'micro'],f'micro-{threads}')
status('DOWNLOADING_PINNED_MODEL')
model=json.loads((root/'model.json').read_text())
run(['curl','-fL','--retry','3',model['downloadUri'],'-o','model.gguf'],'model-download')
digest=hashlib.sha256()
with (root/'model.gguf').open('rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
actual=digest.hexdigest()
assert actual==model['sha256'],(actual,model['sha256'])
(out/'model-sha256.txt').write_text(actual+'\n')
status('COHORT')
run(['/usr/bin/time','-v',*java[:-2],'-Dmodels.native.kernels.threads=4',*java[-2:],
    'com.integrallis.models.backend.purejava.qwen35.GdnCampaign',*libraries,'cohort','model.gguf','data/tasks.json'],
    'cohort',timeout=6000)
status('COMPLETE')
