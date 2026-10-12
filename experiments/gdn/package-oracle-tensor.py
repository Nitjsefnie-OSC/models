"""Freeze an independent tensor diagnostic from immutable artifact inputs."""
import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
version='oracle-tensor-v3';stage=pathlib.Path('/tmp')/version;stage.mkdir(exist_ok=False)
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
source=root/'experiments/gdn/generated/payload-production-correctness-v4'
shutil.copytree(source/'lib/native',stage/'lib');shutil.copytree(source/'rust/native',stage/'rust')
shutil.copy(source/'oracle-source.tar',stage/'oracle-source.tar')
for name in ['oracle-tensor-capture.cpp','ORACLE-TENSOR-PROTOCOL.md']:
    shutil.copy(root/'experiments/gdn'/name,stage/name)
shutil.copy(root/'experiments/gdn/run-oracle-tensor-diagnostic.py',stage/'run-host.py')
java=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin')
classes=stage/'classes';classes.mkdir()
subprocess.run([str(java/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(stage/'lib/*'),'-d',str(classes),
    str(root/'experiments/gdn/ProductionRouteProof.java'),str(root/'experiments/gdn/OracleTensorCapture.java')],check=True)
subprocess.run([str(java/'jar'),'cf',str(stage/'capture.jar'),'-C',str(classes),'.'],check=True)
shutil.rmtree(classes)
tasks=json.loads((root/'experiments/gdn/data/tasks.json').read_text())
selected=[t for dataset in ['jevbench','snips'] for t in [r for r in tasks if r['dataset']==dataset][:4]]
selected.append(next(t for t in tasks if t['id']=='original-routing-06-0'));assert len(selected)==9
(stage/'tasks.json').write_text(json.dumps(selected,indent=2)+'\n')
base=json.loads((source/'confirmation.json').read_text())
# v4 had only the large model; retrieve the same frozen two-model v3 manifest.
base['models']=json.loads((source.parent/'payload-production-timing-v3/confirmation.json').read_text())['models']
base['scope']='Independent tensor diagnostic; old exact token gate remains unchanged and failed'
(stage/'diagnostic.json').write_text(json.dumps(base,indent=2)+'\n')
old=json.loads((out/'payload-production-correctness-v4.json').read_text())
meta={k:old[k] for k in ['jdk','rust']};meta.update(runName='gdn-proof-'+version,
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    maximumCombinedUsd=2,maximumSecondsPerHost=3600,
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
