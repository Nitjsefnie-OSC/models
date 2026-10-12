"""Freeze production-source candidates and their actual artifact classpaths."""
import hashlib,json,pathlib,shutil,subprocess,sys,tarfile
root=pathlib.Path(__file__).resolve().parents[2]
phase=sys.argv[1];assert phase in ('correctness','timing')
edition=sys.argv[2] if len(sys.argv)>2 else 'v1'
assert edition in ('v1','v2','v3','v4')
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip(), 'freeze experiment sources before packaging'
version='production-'+phase+'-'+edition
out=root/'benchmark-results/2026-10-11-gdn-proof'
stage=root/'experiments/gdn/generated'/('payload-'+version);stage.mkdir(exist_ok=False)
old=root/'experiments/gdn/generated/payload-production-screen-v2'
projects={'java':root.parent/'models-gdn-java-production','native':root.parent/'models-gdn-production'}
revisions={}
for arm,path in projects.items():
    assert not subprocess.check_output(['git','status','--porcelain'],cwd=path,text=True).strip(),arm
    revisions[arm]=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip()
    (stage/'candidate-source').mkdir(exist_ok=True)
    evidence=arm+'-production-candidate'+('-v3' if edition in ('v3','v4') else '')
    shutil.copy(out/(evidence+'.patch'),stage/'candidate-source'/(arm+'.patch'))
    shutil.copy(out/(evidence+'.json'),stage/'candidate-source'/(arm+'.json'))
    shutil.copytree(path/'models-bench/build/install/models-bench/lib',stage/'lib'/arm)
    jars=list((path/'backend-native/build/libs').glob('backend-native-*.jar'))
    jars=[p for p in jars if not any(x in p.name for x in ('sources','javadoc','standalone'))]
    assert len(jars)==1,(arm,jars)
    shutil.copy(jars[0],stage/'lib'/arm/jars[0].name)
    shutil.copytree(path/'backend-native/src/main/rust/model-kernels',stage/'rust'/arm,
        ignore=shutil.ignore_patterns('target'))
for arm in ['baseline','published']:
    shutil.copytree(old/'lib',stage/'lib'/arm)
    if arm=='published':
        cores=list((stage/'lib'/arm).glob('vectors-core-*.jar'));assert len(cores)==1
        shutil.copy(old/'baseline/vectors-core.jar',cores[0])
# The old Java default does not call either native recurrence. Its attention ownership is corrected.
baseline=root.parent/'models-gdn-production'
prefix='backend-native/src/main/rust/model-kernels/'
files=subprocess.check_output(['git','ls-tree','-r','--name-only','4337ee45',prefix],cwd=baseline,text=True).splitlines()
assert files and any(f.endswith('/src/ptq.rs') for f in files) and any(f.endswith('/src/neon.rs') for f in files)
for name in files:
    relative=name.removeprefix(prefix)
    dest=stage/'rust/baseline'/relative;dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_bytes(subprocess.check_output(['git','show','4337ee45:'+name],cwd=baseline))
shutil.copytree(stage/'rust/baseline',stage/'rust/published')
# Isolate recurrence choices from the independently reproduced projection rounding defect.
# The published arm keeps its original projection and activation arithmetic.
subprocess.run(['patch','-p1','-d',str(stage/'rust/baseline'),'-i',str(root/'experiments/gdn/q4-k-batch-rounding.patch')],check=True)
# Check the packaged source for both runtime architectures before spending on a fleet launch.
for arm in ['baseline','java','native','published']:
    for target in ['x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu']:
        name=f'{version}-preflight-{arm}-{target}'
        command=['cargo','+1.96.0','check','--locked','--release','--target',target,
            '--target-dir',str(root/'experiments/gdn/generated'/('preflight-'+version)/arm),
            '--manifest-path',str(stage/'rust'/arm/'Cargo.toml')]
        with (out/(name+'.log')).open('w') as log:
            result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        assert result.returncode==0,(name,result.returncode)
for arm in ['java','native']:
    cores=list((stage/'lib'/arm).glob('vectors-core-*.jar'));assert len(cores)==1
    stable=list((old/'lib').glob('vectors-core-*.jar'));assert len(stable)==1
    shutil.copy(stable[0],cores[0])
shutil.copy(root/'experiments/gdn/generated/gdn-jmh.jar',stage/'route-proof.jar')
shutil.copy(root/'experiments/gdn/run-confirmation-host.py',stage/'run-host.py')
shutil.copytree(root/'experiments/gdn/data',stage/'data')
shutil.copytree(root/'experiments/gdn',stage/'experiment-source',ignore=shutil.ignore_patterns('generated','classes','data','target','__pycache__'))
oracle_revision='a935fbffe1a3d31509c325c116454ab5d56b2eb8'
with (stage/'oracle-source.tar').open('wb') as archive:
    subprocess.run(['git','archive',oracle_revision],cwd='/Users/briansam-bodden/Code/llama.cpp',stdout=archive,check=True)
shutil.copy(root/'experiments/gdn/run-artifact-oracle.py',stage/'run-oracle.py')
catalog=json.loads((root.parent/'model-jars/catalog/models.json').read_text())
entries=catalog if isinstance(catalog,list) else catalog['models']
models=[]
for size,sha in [('0.8b','bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517'),
                 ('4b','00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4')]:
    matches=[m for m in entries if m.get('sha256')==sha];assert len(matches)==1,(size,len(matches))
    models.append(dict(matches[0],key='qwen-'+size))
# The v3 small-model oracle failed after the full candidate cohort passed. Preserve that
# failed gate and collect the outstanding 4B cohort independently, with the same strict gates.
# This is a completion of missing coverage, not a replacement for the failed v3 evidence.
if edition=='v4':
    assert phase=='correctness'
    models=[model for model in models if model['key']=='qwen-4b']
settings={'phase':phase,'models':models,'candidateRevisions':revisions,'baselineRevision':'4337ee45',
    'baselineProjectionPatchSha256':hashlib.sha256((root/'experiments/gdn/q4-k-batch-rounding.patch').read_bytes()).hexdigest(),
    'oracleRevision':oracle_revision,
    'vectorsRevision':'b0b3e60','activationAgent':False,'routeOverrides':False,
    'defaultRouteWorkerAndPollingSettings':True,'contextBudget':2048,'fullCohortCases':148,'continuationTokens':8,
    'timingProcessPairs':10,'warmRequestsPerArm':30}
if edition=='v4':
    settings['scope']='Outstanding 4B coverage only; v3 0.8B oracle failure remains unresolved'
(stage/'confirmation.json').write_text(json.dumps(settings,indent=2)+'\n')
base=json.loads((out/'payload-production-screen-v2.json').read_text())
meta={k:base[k] for k in ['jdk','rust']}
meta.update(runName='gdn-proof-'+version,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    maximumCombinedUsd=3.5,maximumSecondsPerHost=7200,
    files={str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()})
(stage/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
archive=out/f'gdn-proof-{version}.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz') as tar:
    for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
meta['payloadUri']=f's3://models-qual-077051030817/payload/gdn-proof-{version}.tar.gz'
(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n')
print(archive,archive.stat().st_size,meta['payloadSha256'])
