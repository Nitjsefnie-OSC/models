import hashlib,json,pathlib,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof';v='quality-factorial-v1'
p=root/'experiments/gdn/generated'/('payload-'+v);p.mkdir(exist_ok=False)
old=root/'experiments/gdn/generated/payload-production-timing-v5';final=root/'experiments/gdn/generated/payload-production-final-v9'
for arm,folder,source in [('published',old,'published'),('activation',old,'published'),('projection',old,'baseline'),('combined',final,'baseline')]:
 shutil.copytree(folder/'lib'/source,p/'lib'/arm)
 if arm in ('activation','projection'):
  for jar in (p/'lib'/arm).glob('vectors-core-*.jar'):jar.unlink()
  vector=next((final/'lib/native' if arm=='activation' else old/'lib/published').glob('vectors-core-*.jar'));shutil.copy(vector,p/'lib'/arm/vector.name)
for arm,folder,source in [('published',old,'published'),('projection',old,'baseline'),('combined',final,'native')]:shutil.copytree(folder/'rust'/source,p/'rust'/arm)
shutil.copytree(final/'data',p/'data');shutil.copy(final/'route-proof.jar',p/'route-proof.jar')
shutil.copy(root/'experiments/gdn/QualityFactorial.java',p/'QualityFactorial.java');shutil.copy(root/'experiments/gdn/run-quality-factorial-host.py',p/'run-host.py')
jdk=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin');classes=p/'classes';classes.mkdir()
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(p/'lib/combined/*')+':'+str(p/'route-proof.jar'),'-d',str(classes),str(p/'QualityFactorial.java')],check=True)
subprocess.run([str(jdk/'jar'),'--create','--file',str(p/'factorial.jar'),'-C',str(classes),'.'],check=True);shutil.rmtree(classes)
config=json.loads((final/'confirmation.json').read_text());(p/'factorial.json').write_text(json.dumps({'arms':['published','activation','projection','combined'],'model':config['models'][0],'cases':148,'threads':'shipping defaults on4CPUhost','interpretation':'activation-only replacesVectors core; projection-only usescorrectedJava/Q4artifact; combinedusesfinalJavaablation; no modelperformanceclaim'},indent=2)+'\n')
meta={k:json.loads((final/'provenance.json').read_text())[k] for k in ['jdk','rust']};meta.update(runName='gdn-proof-'+v,maximumSecondsPerHost=1800,maximumCombinedUsd=.25,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip())
meta['files']={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(p.rglob('*')) if f.is_file()};(p/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n');(p/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
a=out/f'gdn-proof-{v}.tar.gz'
with tarfile.open(a,'w:gz',compresslevel=1) as t:
 for f in sorted(p.iterdir()):t.add(f,arcname=f.name)
meta['payloadSha256']=hashlib.sha256(a.read_bytes()).hexdigest();meta['payloadUri']=f's3://models-qual-077051030817/payload/{a.name}';(out/f'payload-{v}.json').write_text(json.dumps(meta,indent=2)+'\n');print(a,meta['payloadSha256'])
