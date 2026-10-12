import hashlib,json,os,pathlib,subprocess
out=pathlib.Path('/work/out')
def run(command,name,timeout=1200):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout,check=True)
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
status('BUILDING_TRACE')
for v in ['auto','sequential','sequential-aligned']:
    run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{v}/Cargo.toml'],f'build-{v}')
libraries=[f'/work/rust/{v}/target/release/libjmodels_kernels.so' for v in ['auto','sequential','sequential-aligned']]
model=json.loads(pathlib.Path('model.json').read_text())
run(['curl','-fL','--retry','3',model['downloadUri'],'-o','model.gguf'],'model-download')
digest=hashlib.sha256()
with open('model.gguf','rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
assert digest.hexdigest()==model['sha256']
tasks=json.loads(pathlib.Path('data/tasks.json').read_text())
end=next(n for n,r in enumerate(tasks) if r['id']=='easy-extraction-06')+1
pathlib.Path('trace-tasks.json').write_text(json.dumps(tasks[:end]))
status('TRACING_PREFIX_AND_FAILED_CASE')
run(['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
    '-XX:ActiveProcessorCount=4','-Xms2g','-Xmx10g','-Dmodels.native.kernels.threads=4',
    '-Dmodels.purejava.maxContextLength=2048','-Dgdn.correctnessOnly=true',
    '-Dgdn.traceCase=easy-extraction-06',
    '-Dgdn.arms=java,java-head,java-fused,java-parallel,java-chunk,native-auto,native-sequential,native-aligned',
    '-cp','lib/*:gdn.jar','com.integrallis.models.backend.purejava.qwen35.GdnCampaign',
    *libraries,'cohort','model.gguf','trace-tasks.json'],'trace')
status('COMPLETE')
