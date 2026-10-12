import hashlib,json,pathlib,shutil,subprocess,tarfile,urllib.request,sys

root=pathlib.Path(__file__).resolve().parents[2]
version=sys.argv[1] if len(sys.argv)>1 else 'v1'
assert version in ('v1','v2','v3','jmh-v1','jmh-v2','jmh-v3','jmh-v4','trace-v1','stability-v1','stability-v2','cohort-v4','cohort-arm-timing-v5','cohort-correctness-v6','cohort-timing-v6','production-screen-v1','production-screen-v2')
out=root/'benchmark-results/2026-10-11-gdn-proof'
stage=root/f'experiments/gdn/generated/payload-{version}'
stage.mkdir(exist_ok=False)
shutil.copytree(root/'models-bench/build/install/models-bench/lib',stage/'lib',dirs_exist_ok=True)
for jar in (root/'backend-native/build/libs').glob('*.jar'):shutil.copy(jar,stage/'lib'/jar.name)
is_jmh=version.startswith('jmh-')
is_trace=version.startswith(('trace-','stability-'))
is_cohort=version.startswith('cohort-')
is_production=version.startswith('production-')
shutil.copy(root/('experiments/gdn/generated/gdn-jmh.jar' if is_jmh or is_trace or is_cohort or is_production else 'experiments/gdn/classes/gdn.jar'),stage/'gdn.jar')
worker='run-jmh-host.py' if is_jmh else 'run-trace-host.py' if is_trace else 'run-host.py'
if version.startswith('stability-'):worker='run-stability-host.py'
if is_cohort:worker='run-cohort-host.py'
if version=='jmh-v4':worker='run-exclusive-host.py'
if is_production:worker='run-production-host.py'
shutil.copy(root/'experiments/gdn'/worker,stage/'run-host.py')
if is_jmh:
    for jar in (root/'experiments/gdn/generated/jmh-lib').glob('*.jar'):shutil.copy(jar,stage/'lib'/jar.name)
shutil.copytree(root/'experiments/gdn/data',stage/'data',dirs_exist_ok=True)
shutil.copytree(root/'experiments/gdn',stage/'experiment-source',ignore=shutil.ignore_patterns('generated','classes','data','target','__pycache__'),dirs_exist_ok=True)
generated_source=stage/'experiment-source/generated-java';generated_source.mkdir()
for source in (root/'experiments/gdn/generated').glob('*.java'):shutil.copy(source,generated_source/source.name)
variants=['auto','sequential','sequential-aligned']
if version in ('jmh-v2','jmh-v3') or is_cohort:variants.append('sequential-aligned-parallel')
if version=='jmh-v4' or is_production:variants=['sequential-aligned-parallel-exclusive']
for name in variants:
    source=root/('backend-native/src/main/rust/model-kernels' if name=='auto' else f'experiments/gdn/generated/{name}')
    shutil.copytree(source,stage/'rust'/name,ignore=shutil.ignore_patterns('target'),dirs_exist_ok=True)
if is_jmh:
    arms=['java','java-polling','java-register','java-register-parallel','native-aligned','native-aligned-parallel']
    if version=='jmh-v3':arms=['java','java-register','java-register-parallel','java-tiled','java-tiled-parallel','native-aligned-parallel']
    if version=='jmh-v4':arms=['java','java-tiled-stage','native-aligned-parallel']
    (stage/'measurement.json').write_text(json.dumps({'arms':arms,'nativeVariants':variants,
        'nativeForArm':({'native-aligned-parallel':'sequential-aligned-parallel-exclusive'} if version=='jmh-v4'
            else {'native-aligned':'sequential-aligned','native-aligned-parallel':'sequential-aligned-parallel'})},indent=2))
if version.startswith('stability-'):(stage/'diagnostic.json').write_text(json.dumps({'controls':version=='stability-v2'}))
if is_cohort:
    phase='timing-subset' if version in ('cohort-arm-timing-v5','cohort-timing-v6') else 'correctness' if version=='cohort-correctness-v6' else 'full'
    (stage/'cohort.json').write_text(json.dumps({'phase':phase,'shared':version.endswith('-v6')}))
if is_production:
    vectors=root.parent/'vectors-production'
    assert not subprocess.check_output(['git','-C',str(vectors),'status','--porcelain'],text=True).strip()
    (stage/'baseline').mkdir()
    cores=list((stage/'lib').glob('vectors-core-*.jar'));assert len(cores)==1
    shutil.copy(cores[0],stage/'baseline/vectors-core.jar')
    shutil.copy(vectors/'vectors-core/build/libs'/cores[0].name,cores[0])
    probe='com/integrallis/vectors/core/ActivationColdHotProbe.class'
    subprocess.run(['/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin/jar','cf',str(stage/'activation-probe.jar'),
        '-C',str(vectors/'vectors-core/build/classes/java/test'),probe],check=True)
    tasks=json.loads((stage/'data/tasks.json').read_text())
    selected=['easy-intent-00','easy-intent-01','easy-extraction-06','original-routing-06-0',
        'snips-AddToPlaylist-0','snips-BookRestaurant-0','snips-GetWeather-0','snips-PlayMusic-0']
    rows=[t for t in tasks if t['id'] in selected];assert len(rows)==8
    (stage/'screening-tasks.json').write_text(json.dumps(rows,indent=2)+'\n')
    settings={'workers':[4,16],'nativePollMillis':([0,5] if version=='production-screen-v1' else [1,5]),'timingRepeats':2,
        'arms':['java','java-tiled','java-tiled-stage','native-shared'],
        'vectorsRevision':subprocess.check_output(['git','-C',str(vectors),'rev-parse','HEAD'],text=True).strip(),
        'vectorsBaseline':'published dependency copied from the original benchmark classpath',
        'activationAgent':False,'nativeComparator':'ownership-corrected exact sequential'}
    (stage/'production.json').write_text(json.dumps(settings,indent=2)+'\n')
    for name in ['PanamaVectorUtilSupport.java','ScalarVectorUtilSupport.java']:
        dest=stage/'vectors-source'/name;dest.parent.mkdir(exist_ok=True)
        shutil.copy(vectors/'vectors-core/src/main/java/com/integrallis/vectors/core'/name,dest)
    for name in ['ActivationColdHotProbe.java','ActivationColdHotTest.java']:
        shutil.copy(vectors/'vectors-core/src/test/java/com/integrallis/vectors/core'/name,stage/'vectors-source'/name)
catalog=json.loads((root.parent/'model-jars/catalog/models.json').read_text())
entries=catalog if isinstance(catalog,list) else catalog['models']
model=next(m for m in entries if m['id']=='unsloth_qwen3_5_4b_gguf_q4_k_m')
(stage/'model.json').write_text(json.dumps(model,indent=2)+'\n')
jdk={}
for arch in ['x64','aarch64']:
    url=f'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_{arch}_linux_hotspot_25.0.3_9.tar.gz'
    checksum=urllib.request.urlopen(url+'.sha256.txt').read().decode().split()[0]
    assert len(checksum)==64
    jdk[arch]={'url':url,'sha256':checksum}
meta={'runName':'gdn-proof-'+version,'revision':subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
      'jdk':jdk,'rust':'1.96.0','maximumCombinedUsd':1.6 if version=='cohort-arm-timing-v5' else 1.25 if is_jmh else .5 if is_trace else 3.5,
      'maximumSecondsPerHost':2700 if is_jmh else 1500 if is_trace else 7200,
      'files':{str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()}}
(stage/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
for name,want in meta['files'].items():
    assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==want,name
archive=out/f'gdn-proof-{version}.tar.gz'
assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
    for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
meta['payloadUri']=f's3://models-qual-077051030817/payload/gdn-proof-{version}.tar.gz'
(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n')
print(archive,archive.stat().st_size,meta['payloadSha256'])
