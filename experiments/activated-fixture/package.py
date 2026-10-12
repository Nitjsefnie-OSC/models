"""Freeze actual candidate jars and unchanged framework fixture classes for an isolated run."""
from pathlib import Path
import json,hashlib,shutil,tarfile,subprocess
root=Path.cwd();primary=root.parent/'models';out=Path('/tmp/activated-fixture-d2d0');out.mkdir(exist_ok=False)
classpath=json.loads(Path('/tmp/activated-fixture-classpaths.json').read_text());frozen={};sources={}
for module,paths in classpath.items():
 key=module[1:];selected=[]
 for original in paths:
  source=Path(original)
  if str(primary) in original and '/build/classes/' in original or str(primary) in original and '/build/resources/' in original:
   source=Path(original.replace(str(primary),str(root)));dest=Path('classes')/key/source.relative_to(root)
  elif source.suffix=='.jar':
   if '/vectors-core/' in str(source):source=root.parent/'vectors-executor-overflow/vectors-core/build/libs/vectors-core-0.1.30.jar'
   elif str(primary) in str(source) and source.name.startswith('models-router-'):source=Path(str(source).replace(str(primary),str(root)))
   dest=Path('lib')/source.name
  else:continue
  if not source.exists():continue
  target=out/dest;target.parent.mkdir(parents=True,exist_ok=True)
  if source.is_dir():shutil.copytree(source,target,dirs_exist_ok=True)
  elif target.exists():assert hashlib.sha256(target.read_bytes()).digest()==hashlib.sha256(source.read_bytes()).digest()
  else:shutil.copy2(source,target)
  selected.append('/work/'+str(dest));sources[str(dest)]=str(source)
 selected+=['/work/lib/slf4j-simple-2.0.17.jar','/work/runner'];frozen[key]=selected
logger=next(Path('/Users/briansam-bodden/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-simple/2.0.17').rglob('*.jar'));shutil.copy2(logger,out/'lib'/logger.name)
shutil.copytree('/tmp/activated-uninstrumented',out/'runner',ignore=shutil.ignore_patterns('candidate-*'))
shutil.copytree(root.parent/'models-alora-sharing/benchmark-results/2026-09-12-alora-tool-hybrid/evidence/qwen3-17b-applicability-contract-v12/runtime-adapter',out/'adapter')
(out/'classpaths.json').write_text(json.dumps(frozen,indent=2)+'\n')
model=next(r for r in json.loads((root/'scripts/fleet/fixtures/candidate-catalog/models.json').read_text())['models'] if r['id']=='qwen3_1_7b_q8_0')
(out/'model.json').write_text(json.dumps({k:model[k] for k in ['id','downloadUri','sha256','sizeBytes']},indent=2)+'\n')
shutil.copy2(root/'experiments/activated-fixture/run.py',out/'run.py')
files={str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in out.rglob('*') if p.is_file()}
meta={'modelsSource':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'vectorsSource':'afc4ebd8e25dfe22ea64b85d552d59f356bccfd9','scope':'Literal unchanged framework integration tests with actual candidate jars; no coverage instrumentation, isolated host, diagnostic logging. Not a model qualification campaign or paired timing comparison.','files':files,'sourcePaths':sources}
(out/'manifest.json').write_text(json.dumps(meta,indent=2)+'\n')
archive=out.with_suffix('.tar.gz')
with tarfile.open(archive,'w:gz') as t:
 for p in out.iterdir():t.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest();meta['payloadUri']='s3://models-qual-077051030817/payload/activated-fixture-d2d0.tar.gz'
(root/'experiments/activated-fixture/payload.json').write_text(json.dumps(meta,indent=2)+'\n')
subprocess.run(['aws','s3','cp',str(archive),meta['payloadUri'],'--only-show-errors'],check=True);print(meta['payloadSha256'],archive.stat().st_size)
