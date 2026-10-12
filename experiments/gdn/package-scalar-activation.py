import hashlib,json,pathlib,shutil,subprocess,tarfile,zipfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof';version='scalar-activation-v1';p=root/'experiments/gdn/generated'/('payload-'+version);p.mkdir(exist_ok=False);final=root/'experiments/gdn/generated/payload-production-final-v11'
for arm in ['native','scalar']:shutil.copytree(final/'lib/native',p/'lib'/arm)
candidate=pathlib.Path('/tmp/q5-final-v4/bonsai-proof/scalar-lib/vectors-core-0.1.30.jar');assert hashlib.sha256(candidate.read_bytes()).hexdigest()=='c3897e0637cbd93ce3801cd9bc2c0f8abc991b93bf3d6a49ddba840eef5abe93';shutil.copy(candidate,p/'lib/scalar'/candidate.name)
shutil.copytree(final/'rust/native',p/'rust/native');shutil.copytree(final/'data',p/'data')
for name in ['route-proof.jar','confirmation.json']:shutil.copy(final/name,p/name)
shutil.copy(root/'experiments/gdn/run-scalar-activation-host.py',p/'run-host.py');shutil.copy('/tmp/q5-final-v4/source/scalar-activation/PanamaVectorUtilSupport.java',p/'PanamaVectorUtilSupport.java')
with zipfile.ZipFile(next((p/'lib/native').glob('vectors-core-*.jar')))as a,zipfile.ZipFile(candidate)as b:
 changed=[n for n in set(a.namelist())&set(b.namelist())if n.endswith('.class')and a.read(n)!=b.read(n)];assert all(n.startswith('com/integrallis/vectors/core/PanamaVectorUtilSupport')for n in changed)
(p/'protocol.txt').write_text('Diagnostic only. ARM m7g.4xlarge fixed final0bc+ae337 versus fixed scalar activation control; only Panama activation source differs, no counters/properties or caches.148case x9cloned fullvector correctness then5freshJVMpairs alternating order,32fixedwarm+3measured as finalharness. Actual0.8B weights. Recordload,cold/steadyalloc,latency,RSS; no discardedforks. This5pairdiagnostic doesnotreplace original10pair finalgate or4Bworkload.20minutehardcap,$0.40maximum.\n')
base=json.loads((final/'provenance.json').read_text());meta={k:base[k]for k in ['jdk','rust']};meta.update(runName='gdn-proof-'+version,maximumSecondsPerHost=1200,maximumCombinedUsd=.40,changedVectorClasses=changed,sourceBase='Models0bc5de31/Vectorsae337eeb',scalarJarSha256=hashlib.sha256(candidate.read_bytes()).hexdigest())
meta['files']={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest()for f in sorted(p.rglob('*'))if f.is_file()};(p/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n');(p/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n'for name,sha in meta['files'].items()))
a=out/f'gdn-proof-{version}.tar.gz'
with tarfile.open(a,'w:gz',compresslevel=1)as t:
 for f in sorted(p.iterdir()):t.add(f,arcname=f.name)
meta['payloadSha256']=hashlib.sha256(a.read_bytes()).hexdigest();meta['payloadUri']=f's3://models-qual-077051030817/payload/{a.name}';(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n');print(a,meta['payloadSha256'])
