"""Bounded screening with shipping-source activations and corrected native recurrence."""
import hashlib,json,os,pathlib,subprocess,time
out=pathlib.Path('/work/out')

def run(command,name,timeout=6600,required=True):
    start=time.monotonic()
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
    (out/(name+'.execution.json')).write_text(json.dumps({'command':command,'exitCode':result.returncode,'wallSeconds':time.monotonic()-start},indent=2)+'\n')
    if required:assert result.returncode==0,(name,result.returncode)
    return result.returncode

def status(value):
    (out/'STATUS').write_text(value+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)

config=json.loads(pathlib.Path('production.json').read_text())
assert all(1 <= value <= 60000 for value in config['nativePollMillis']), 'invalid native poll budget'
status('BUILDING')
variant='sequential-aligned-parallel-exclusive'
run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{variant}/Cargo.toml'],'build-native')
library=f'/work/rust/{variant}/target/release/libjmodels_kernels.so'
(out/'native-hash.txt').write_text(hashlib.sha256(pathlib.Path(library).read_bytes()).hexdigest()+'\n')
base=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms2g','-Xmx10g']
classpath='lib/*:gdn.jar:activation-probe.jar'
probe='com.integrallis.vectors.core.ActivationColdHotProbe'
status('ACTIVATION_CONTRACT')
run([*base,'-XX:ActiveProcessorCount=4','-cp','baseline/vectors-core.jar:'+classpath,probe],'activation-published',300,False)
run([*base,'-XX:ActiveProcessorCount=4','-cp',classpath,probe],'activation-source',300)
run([*base,'-XX:ActiveProcessorCount=4','-cp',classpath,'com.integrallis.models.backend.purejava.qwen35.GateStabilityCheck'],'gate-source',300)
assert 'FINAL firstChange=-1' in (out/'gate-source.stdout').read_text()
for check in ['ExclusiveProof','GroupProof']:
    run([*base,'-XX:ActiveProcessorCount=4','-Dmodels.native.kernels.threads=4','-cp',classpath,
        'com.integrallis.models.backend.purejava.qwen35.'+check,library],check,300)
model=json.loads(pathlib.Path('model.json').read_text())
run(['curl','-fL','--retry','3',model['downloadUri'],'-o','model.gguf'],'model-download')
digest=hashlib.sha256()
with open('model.gguf','rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
assert digest.hexdigest()==model['sha256']
status('MODEL_STABILITY')
run([*base,'-XX:ActiveProcessorCount=4','-Dmodels.native.kernels.threads=4',
    '-Dmodels.purejava.maxContextLength=2048','-cp',classpath,
    'com.integrallis.models.backend.purejava.qwen35.GdnStabilityCheck',library,'model.gguf','data/tasks.json','/work/out/source-stability-tensors'],
    'source-stability',1200)
assert 'first-input-change' not in (out/'source-stability.stdout').read_text()
rows=[json.loads(line) for line in (out/'source-stability.stdout').read_text().splitlines() if line.startswith('{')]
assert len(rows)==24
assert all(r['againstFirst'].startswith('0:') for r in rows),rows[-1]
for workers in config['workers']:
    for poll in config['nativePollMillis']:
        name=f'w{workers}-poll{poll}'
        status('SCREENING_'+name)
        command=[*base,f'-XX:ActiveProcessorCount={workers}',f'-Dmodels.native.kernels.threads={workers}',
            f'-Dvectors.gguf.threads={workers}',f'-Dmodels.native.kernels.pollMillis={poll}',
            '-Dmodels.purejava.maxContextLength=2048','-Dgdn.sharedNativeOnly=true',
            '-Dgdn.timeAllCases=true',f'-Dgdn.timingRepeats={config["timingRepeats"]}',
            '-Dgdn.arms='+','.join(config['arms']),'-cp',classpath,
            'com.integrallis.models.backend.purejava.qwen35.GdnCampaign',library,library,library,
            'cohort','model.gguf','screening-tasks.json']
        run(['/usr/bin/time','-v',*command],name)
        rows=[json.loads(line) for line in (out/(name+'.stdout')).read_text().splitlines() if line.startswith('{')]
        for arm in config['arms']:
            for repeat in range(config['timingRepeats']+1):
                selected=[r for r in rows if r.get('arm')==arm and r.get('repeat')==repeat]
                assert len(selected)==8,(name,arm,repeat,len(selected))
                assert all(r['calls']>0 and r['difference'].startswith('0:') for r in selected)
        assert 'GDN_SHARED_CONTEXT providers=1 separateRecurrenceContexts=0' in (out/(name+'.stderr')).read_text()
status('COMPLETE')
