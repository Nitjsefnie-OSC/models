"""Freeze the ARM repack diagnostic from the previously successful immutable payload."""
import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
version='oracle-repack-v1';stage=pathlib.Path('/tmp')/version
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
shutil.copytree('/tmp/oracle-tensor-v3',stage)
for name in ['inputs.sha256','provenance.json']: (stage/name).unlink()
shutil.copy(root/'experiments/gdn/oracle-tensor-capture.cpp',stage/'oracle-tensor-capture.cpp')
worker=(root/'experiments/gdn/run-oracle-tensor-diagnostic.py').read_text()
old="for arm,batch in [('baseline',512),('baseline',32),('additive',32)]:"
new="for arm,batch,repack in [('baseline',32,1),('baseline',32,0),('additive',32,1),('additive',32,0)]:"
assert worker.count(old)==1;worker=worker.replace(old,new)
worker=worker.replace("label=f'{key}-{arm}-{batch}'", "label=f'{key}-{arm}-{batch}-repack{repack}'")
worker=worker.replace("str(dest),str(batch)])", "str(dest),str(batch),str(repack)])")
assert 'str(batch),str(repack)' in worker
(stage/'run-host.py').write_text(worker)
meta=json.loads((out/'payload-oracle-tensor-v3.json').read_text())
for name in ['payloadSha256','payloadBytes','payloadUri']:meta.pop(name)
meta.update(runName='gdn-proof-'+version,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
 maximumCombinedUsd=1,maximumSecondsPerHost=1800,scope='ARM repack factorial diagnostic; existing exact-greedy failures remain unchanged',
 files={str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()})
(stage/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
archive=out/f'gdn-proof-{version}.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz',compresslevel=1) as tar:
 for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest();meta['payloadBytes']=archive.stat().st_size
meta['payloadUri']='s3://models-qual-077051030817/payload/'+archive.name
(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n')
subprocess.run(['aws','s3','cp',str(archive),meta['payloadUri'],'--only-show-errors'],check=True)
print(json.dumps({k:meta[k] for k in ['payloadUri','payloadSha256','payloadBytes','revision']},indent=2))
