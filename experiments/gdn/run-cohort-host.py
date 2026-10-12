import hashlib,json,os,pathlib,subprocess
out=pathlib.Path('/work/out')
def run(command,name,timeout=6600):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout,check=True)
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
config=json.loads(pathlib.Path('cohort.json').read_text())
shared=config.get('shared',False)
arms=['java','java-tiled','java-tiled-stage','native-shared'] if shared else ['java','java-tiled-stage','native-aligned-parallel']
variants=['sequential-aligned-parallel'] if shared else ['auto','sequential','sequential-aligned','sequential-aligned-parallel']
status('BUILDING')
for variant in variants:
    run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{variant}/Cargo.toml'],f'build-{variant}')
libraries=[f'/work/rust/{v}/target/release/libjmodels_kernels.so' for v in variants]
(out/'native-hashes.json').write_text(json.dumps({v:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() for v,p in zip(variants,libraries)},indent=2))
if shared:libraries=libraries*4
model=json.loads(pathlib.Path('model.json').read_text())
run(['curl','-fL','--retry','3',model['downloadUri'],'-o','model.gguf'],'model-download')
digest=hashlib.sha256()
with open('model.gguf','rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
assert digest.hexdigest()==model['sha256']
tasks=json.loads(pathlib.Path('data/tasks.json').read_text())
assert len(tasks)==148
phase=config['phase']
taskFile='data/tasks.json'
caseCount=148
if phase=='timing-subset':
    tasks=[t for n,t in enumerate(tasks) if n<6 or 114<=n<120
        or (t['id'].startswith('snips-') and (t['id'].endswith('-0') or t['id'].endswith('-1')))]
    assert len(tasks)==26
    taskFile='timing-tasks.json';caseCount=26
    pathlib.Path(taskFile).write_text(json.dumps(tasks,indent=2)+'\n')
    (out/'timing-task-ids.json').write_text(json.dumps([t['id'] for t in tasks],indent=2)+'\n')
java=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
    '-XX:ActiveProcessorCount=16','-Xms2g','-Xmx10g','-Dmodels.native.kernels.threads=16',
    '-Dmodels.purejava.maxContextLength=2048','-Dgdn.pollNanos=0','-javaagent:gdn.jar=all',
    '-Dgdn.timeAllCases='+str(phase=='timing-subset').lower(),
    '-Dgdn.correctnessOnly='+str(phase=='correctness').lower(),
    '-Dgdn.sharedNativeOnly='+str(shared).lower(),
    '-Dgdn.arms='+','.join(arms),
    '-Dgdn.nativeParallelLibrary='+libraries[3],'-cp','lib/*:gdn.jar']
status('FULL_PUBLIC_COHORT')
run([*java,'com.integrallis.models.backend.purejava.qwen35.GdnCampaign',*libraries[:3],
    'cohort','model.gguf',taskFile],'cohort')
rows=[json.loads(line) for line in (out/'cohort.stdout').read_text().splitlines() if line.startswith('{')]
for arm in arms:
    for repeat in range(1 if phase=='correctness' else 6):
        armRows=[r for r in rows if r.get('kind')=='decision' and r['arm']==arm and r['repeat']==repeat]
        assert len(armRows)==(caseCount if repeat==0 else 26),(arm,repeat,len(armRows))
        assert all(r['difference'].startswith('0:') and r['calls']>0 for r in armRows)
assert len(rows)==len(arms)*(3+caseCount+(0 if phase=='correctness' else 130)),len(rows)
if shared:assert 'GDN_SHARED_CONTEXT providers=1 separateRecurrenceContexts=0' in (out/'cohort.stderr').read_text()
status('COMPLETE')
