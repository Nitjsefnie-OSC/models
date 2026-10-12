"""Freeze narrow all-layer capture against the corrected production arithmetic."""
import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
version='oracle-layers-v1';stage=pathlib.Path('/tmp')/version;stage.mkdir(exist_ok=False)
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
meta=json.loads((out/'payload-oracle-tensor-v3.json').read_text())
for name in meta['files']:
 dest=stage/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy(pathlib.Path('/tmp/oracle-tensor-v3')/name,dest)
shutil.copy(root/'experiments/gdn/oracle-layer-capture.cpp',stage/'oracle-tensor-capture.cpp')
worker=(root/'experiments/gdn/run-oracle-tensor-diagnostic.py').read_text()
worker=worker.replace('qwen35.OracleTensorCapture','qwen35.OracleLayerCapture')
old="for arm,batch in [('baseline',512),('baseline',32),('additive',32)]:"
new="for arm,batch,repack in [('baseline',32,1),('baseline',32,0),('additive',32,1),('additive',32,0)]:"
assert worker.count(old)==1;worker=worker.replace(old,new)
worker=worker.replace("label=f'{key}-{arm}-{batch}'", "label=f'{key}-{arm}-{batch}-repack{repack}'")
worker=worker.replace("str(dest),str(batch)])", "str(dest),str(batch),str(repack)])")
assert 'str(batch),str(repack)' in worker
(stage/'run-host.py').write_text(worker)
java=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin')
classes=stage/'classes';classes.mkdir()
subprocess.run([str(java/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(stage/'lib/*'),'-d',str(classes),
 str(root/'experiments/gdn/ProductionRouteProof.java'),str(root/'experiments/gdn/OracleLayerCapture.java')],check=True)
subprocess.run([str(java/'jar'),'cf',str(stage/'capture.jar'),'-C',str(classes),'.'],check=True);shutil.rmtree(classes)
for name in ['payloadSha256','payloadBytes','payloadUri']:meta.pop(name)
meta.update(runName='gdn-proof-'+version,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
 maximumCombinedUsd=1,maximumSecondsPerHost=1800,scope='Narrow all-layer graph diagnostic; existing exact-greedy failures remain unchanged',
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
