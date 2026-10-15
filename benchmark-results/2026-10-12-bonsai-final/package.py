"""Freeze final candidate artifacts without changing the public-QA protocol or publisher."""
import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2]
out=pathlib.Path(__file__).resolve().parent
stage=pathlib.Path('/tmp/bonsai-public-qa-final/stage')
qa=stage/'qa'
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
assert not subprocess.check_output(['git','diff','--name-only','--','backend-java','backend-native','models-api','models-runtime'],cwd=root,text=True).strip()
old=json.loads((qa/'payload-provenance.json').read_text())
for name,expected in old['files'].items():
 if name.startswith('prism/'):
  assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==expected,name
for module in ['models-api','models-runtime','backend-java','backend-native']:
 jars=[p for p in (root/module/'build/libs').glob('*.jar') if not any(t in p.name for t in ['sources','javadoc'])]
 assert len(jars)==1,(module,jars)
 for p in (qa/'lib').glob(module+'-*.jar'):p.unlink()
 shutil.copy(jars[0],qa/'lib'/jars[0].name)
vector=root.parent/'vectors-bf16-production/vectors-core/build/libs/vectors-core-0.1.30.jar'
assert hashlib.sha256(vector.read_bytes()).hexdigest()=='3b037e5e405b526e67cd4078c58e191e60589b7b3dbccb9992f8d98d258f5202'
for p in (qa/'lib').glob('vectors-core-*.jar'):p.unlink()
shutil.copy(vector,qa/'lib'/vector.name)
shutil.copytree(root/'backend-native/src/main/rust/model-kernels',stage/'rust',ignore=shutil.ignore_patterns('target'))
(qa/'source-revision.txt').write_text(revision+'\n')
shutil.copy(out/'PROTOCOL.md',qa/'final-protocol.md')
meta={'sourceRevision':revision,'vectorsRevision':'d92aceb','referenceRevision':old['referenceRevision'],
 'previousPayloadSha256':'1b4fcc0c5287721fa06ed4ed7598826ec880a98f0ed209ef908a53958224aa24',
 'protocol':'bonsai-production-public-qa-v1','tokenizationMode':'trusted-frozen-chatml-v2',
 'maximumSecondsPerHost':5400,'maximumCombinedUsd':10,'files':{}}
for p in sorted(stage.rglob('*')):
 if p.is_file() and p not in [stage/'inputs.sha256',qa/'payload-provenance.json']:
  meta['files'][str(p.relative_to(stage))]=hashlib.sha256(p.read_bytes()).hexdigest()
(qa/'payload-provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
meta['files']['qa/payload-provenance.json']=hashlib.sha256((qa/'payload-provenance.json').read_bytes()).hexdigest()
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
archive=out/f'bonsai-final-qa-{revision[:8]}.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz',compresslevel=1) as tar:
 for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta.update(payloadUri=f's3://models-qual-077051030817/payload/{archive.name}',payloadSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),runName=f'bonsai-final-qa-{revision[:8]}')
(out/'payload.json').write_text(json.dumps(meta,indent=2)+'\n')
print(archive,meta['payloadSha256'])
