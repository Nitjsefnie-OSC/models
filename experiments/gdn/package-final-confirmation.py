"""Freeze five actual production artifacts to isolate activation cost and serial recurrence."""
import hashlib,json,pathlib,shutil,subprocess,sys,tarfile
root=pathlib.Path(__file__).resolve().parents[2]
phase=sys.argv[1];assert phase in ('correctness','timing')
vector_revision=sys.argv[2]
version='production-'+phase+'-v5'
out=root/'benchmark-results/2026-10-11-gdn-proof'
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
stage=root/'experiments/gdn/generated'/('payload-'+version);stage.mkdir(exist_ok=False)
old=root/'experiments/gdn/generated/payload-production-timing-v3'
vector=root.parent/'vectors-production'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=vector,text=True).strip()==vector_revision
assert not subprocess.check_output(['git','status','--porcelain','--','vectors-core'],cwd=vector,text=True).strip()
fast=vector/'vectors-core/build/libs/vectors-core-0.1.29.jar'
shutil.copytree(old/'lib',stage/'lib');shutil.copytree(old/'rust',stage/'rust')
shutil.copytree(old/'lib/native',stage/'lib/strictnative');shutil.copytree(old/'rust/native',stage/'rust/strictnative')
java=root.parent/'models-gdn-java-production'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=java,text=True).strip()
shutil.rmtree(stage/'lib/java');shutil.copytree(java/'models-bench/build/install/models-bench/lib',stage/'lib/java')
shutil.copy(java/'backend-native/build/libs/backend-native-0.3.59.jar',stage/'lib/java/backend-native-0.3.59.jar')
shutil.rmtree(stage/'rust/java');shutil.copytree(java/'backend-native/src/main/rust/model-kernels',stage/'rust/java',ignore=shutil.ignore_patterns('target'))
for arm in ['baseline','java','native']:
    jars=list((stage/'lib'/arm).glob('vectors-core-*.jar'));assert len(jars)==1
    shutil.copy(fast,jars[0])
shutil.copy(root/'experiments/gdn/generated/gdn-jmh.jar',stage/'route-proof.jar')
shutil.copy(root/'experiments/gdn/run-final-confirmation-host.py',stage/'run-host.py')
shutil.copy(root/'experiments/gdn/run-artifact-oracle.py',stage/'run-oracle.py')
shutil.copytree(old/'data',stage/'data')
shutil.copy(old/'oracle-source.tar',stage/'oracle-source.tar')
shutil.copytree(root/'experiments/gdn',stage/'experiment-source',ignore=shutil.ignore_patterns('generated','classes','data','target','__pycache__'))
(stage/'candidate-source').mkdir()
for label,path,base in [('vectors',vector,'7e77a63'),('java',java,'4337ee45'),('native',root.parent/'models-gdn-production','4337ee45')]:
    (stage/'candidate-source'/(label+'.patch')).write_bytes(subprocess.check_output(['git','diff',base,'HEAD','--','vectors-core' if label=='vectors' else '.'],cwd=path))
for target in ['x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu']:
    with (out/f'{version}-preflight-java-{target}.log').open('w') as log:
        subprocess.run(['cargo','+1.96.0','check','--locked','--release','--target',target,'--target-dir',str(root/'experiments/gdn/generated/preflight-final/java'),'--manifest-path',str(stage/'rust/java/Cargo.toml')],stdout=log,stderr=subprocess.STDOUT,check=True)
settings=json.loads((old/'confirmation.json').read_text())
settings.update(phase=phase,arms=['baseline','java','native','published','strictnative'],vectorsRevision=vector_revision,
    candidateRevisions={'java':subprocess.check_output(['git','rev-parse','HEAD'],cwd=java,text=True).strip(),'native':'12796ebf'},
    activationControl='strictnative uses native recurrence with previous StrictMath activations; native uses the exact SIMD activation candidate',
    oracleFailureHandling='Record failure and continue outstanding model coverage; any oracle failure still fails the phase')
(stage/'confirmation.json').write_text(json.dumps(settings,indent=2)+'\n')
base=json.loads((out/'payload-production-timing-v3.json').read_text())
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
