"""Package exact-integer Q4_K optimization without changing any other production artifact."""
import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
version='q4-minimum-simd-v1'
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
candidate=root.parent/'models-q4-proof'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=candidate,text=True).strip()
old=root/'experiments/gdn/generated/payload-production-timing-v5'
stage=root/'experiments/gdn/generated'/('payload-'+version);stage.mkdir(exist_ok=False)
for arm in ['native','simd']:
    shutil.copytree(old/'lib/native',stage/'lib'/arm)
shutil.copytree(old/'rust/native',stage/'rust/native')
shutil.copytree(candidate/'backend-native/src/main/rust/model-kernels',stage/'rust/simd',ignore=shutil.ignore_patterns('target'))
for name in ['route-proof.jar','provenance.json']:shutil.copy(old/name,stage/name)
shutil.copytree(old/'data',stage/'data')
shutil.copy(root/'experiments/gdn/run-q4-confirmation-host.py',stage/'run-host.py')
shutil.copy(root/'experiments/gdn/Q4-MINIMUM-SIMD-PROTOCOL.md',stage/'protocol.md')
(stage/'candidate.patch').write_bytes(subprocess.check_output(['git','diff','12796ebf','HEAD'],cwd=candidate))
settings=json.loads((old/'confirmation.json').read_text());settings.update(phase='both',arms=['native','simd'],
    candidateRevision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=candidate,text=True).strip())
(stage/'confirmation.json').write_text(json.dumps(settings,indent=2)+'\n')
meta=json.loads((old/'provenance.json').read_text())
meta.update(runName='gdn-proof-'+version,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    maximumCombinedUsd=1,maximumSecondsPerHost=3600)
(stage/'provenance.json').unlink()
meta['files']={str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()}
(stage/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
archive=out/f'gdn-proof-{version}.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz',compresslevel=1) as tar:
    for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest();meta['payloadUri']=f's3://models-qual-077051030817/payload/{archive.name}'
(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n')
print(archive,archive.stat().st_size,meta['payloadSha256'])
