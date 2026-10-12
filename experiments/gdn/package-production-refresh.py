"""Freeze corrected Java control, combined native candidate and published artifacts."""
import difflib,hashlib,json,pathlib,re,shutil,subprocess,sys,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
version='production-final-v11';old=root/'experiments/gdn/generated/payload-production-timing-v5'
vector=pathlib.Path(sys.argv[1]).resolve();expected=sys.argv[2]
assert hashlib.sha256(vector.read_bytes()).hexdigest()==expected
assert not subprocess.check_output(['git','status','--porcelain','--','experiments/gdn'],cwd=root,text=True).strip()
candidate=root.parent/'models-final-integration';revision='0bc5de31'
assert not subprocess.check_output(['git','diff',revision,'--','backend-java/src','backend-native/src','models-api/src','models-runtime/src'],cwd=candidate,text=True).strip()
stage=root/'experiments/gdn/generated'/('payload-'+version);stage.mkdir(exist_ok=False)
for arm in ['baseline','native','published']:
    shutil.copytree(old/'lib'/('published' if arm=='published' else 'native'),stage/'lib'/arm)
    if arm!='published':
        frozen=candidate
        for module in ['models-api','models-runtime','backend-java','backend-native']:
            found=[j for j in (frozen/module/'build/libs').glob(module+'-*.jar') if not any(x in j.name for x in ['-sources','-javadoc','-linux-','-macos-','-windows-'])];assert len(found)==1
            for previous in (stage/'lib'/arm).glob(module+'-*.jar'):previous.unlink()
            shutil.copy(found[0],stage/'lib'/arm/found[0].name)
        jars=list((stage/'lib'/arm).glob('vectors-core-*.jar'));assert len(jars)==1
        jars[0].unlink();shutil.copy(vector,stage/'lib'/arm/vector.name)
shutil.copytree(candidate/'backend-native/src/main/rust/model-kernels',stage/'rust/native',ignore=shutil.ignore_patterns('target'))
shutil.copytree(old/'rust/published',stage/'rust/published')
shutil.copytree(old/'data',stage/'data')
shutil.copy(root/'experiments/gdn/run-production-refresh-host.py',stage/'run-host.py')
shutil.copy(root/'experiments/gdn/PRODUCTION-FINAL-V6-PROTOCOL.md',stage/'protocol.md')
source=stage/'source';source.mkdir()
provider='backend-native/src/main/java/com/integrallis/models/backend/nativekernel/RustGgufBatchedMatrixKernel.java'
original=subprocess.check_output(['git','show',revision+':'+provider],cwd=root.parent/'models-final-integration',text=True)
modified,count=re.subn(r'public boolean supportsGatedDeltaNet\(\) \{.*?\n  \}', 'public boolean supportsGatedDeltaNet() {\n    return false;\n  }',original,count=1,flags=re.S)
assert count==1
(source/'RustGgufBatchedMatrixKernel.java').write_text(modified)
(source/'baseline.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),modified.splitlines(True),fromfile=provider,tofile=provider)))
(source/'candidate.patch').write_bytes(subprocess.check_output(['git','diff','12796ebf',revision],cwd=candidate))
shutil.copy(root/'experiments/gdn/ProductionRouteProof.java',source/'ProductionRouteProof.java')
jdk=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin')
classes=stage/'baseline-classes';classes.mkdir()
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(stage/'lib/baseline/*'),'-d',str(classes),str(source/'RustGgufBatchedMatrixKernel.java')],check=True)
jar=list((stage/'lib/baseline').glob('backend-native-*.jar'));assert len(jar)==1
subprocess.run([str(jdk/'jar'),'--update','--file',str(jar[0]),'-C',str(classes),'.'],check=True)
shutil.rmtree(classes)
classes=stage/'proof-classes';classes.mkdir()
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(stage/'lib/native/*'),'-d',str(classes),str(source/'ProductionRouteProof.java')],check=True)
subprocess.run([str(jdk/'jar'),'--create','--file',str(stage/'route-proof.jar'),'-C',str(classes),'.'],check=True);shutil.rmtree(classes)
settings=json.loads((old/'confirmation.json').read_text())
settings.update(phase='timing',arms=['baseline','native','published'],timingArms=['native','published'],candidateRevision=revision,vectorsSha256=expected,vectorsRevision='ae337eeb',eligibilityCache=True,
    warmupRequestsPerProcess=32,measuredRequestsPerProcess=3,logitSnapshots='clone each vector before buffer reuse',
    baselineAblation='Only supportsGatedDeltaNet() returns false; candidate Rust shared unchanged',
    shards={'x86-large-a':{'arch':'x86','phase':'timing','model':'qwen-4b','pairs':list(range(5)),'correctness':True},
    'x86-large-b':{'arch':'x86','phase':'timing','model':'qwen-4b','pairs':list(range(5,10))},
    'arm-small':{'arch':'arm','phase':'timing','model':'qwen-0.8b','pairs':list(range(10)),'correctness':True},
    'arm-large-a':{'arch':'arm','phase':'timing','model':'qwen-4b','pairs':list(range(5)),'correctness':True},
    'arm-large-b':{'arch':'arm','phase':'timing','model':'qwen-4b','pairs':list(range(5,10))}})
for stale in ['candidateRevisions','baselineRevision','baselineProjectionPatchSha256','activationControl','warmRequestsPerArm']:
    settings.pop(stale,None)
(stage/'confirmation.json').write_text(json.dumps(settings,indent=2)+'\n')
previous=json.loads((old/'provenance.json').read_text());meta={k:previous[k] for k in ['jdk','rust']}
meta.update(runName='gdn-proof-'+version,revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),maximumCombinedUsd=8,maximumSecondsPerHost=4200)
meta['files']={str(p.relative_to(stage)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(stage.rglob('*')) if p.is_file()}
(stage/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n')
(stage/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
archive=out/f'gdn-proof-{version}.tar.gz';assert not archive.exists()
with tarfile.open(archive,'w:gz',compresslevel=1) as tar:
    for p in sorted(stage.iterdir()):tar.add(p,arcname=p.name)
meta['payloadSha256']=hashlib.sha256(archive.read_bytes()).hexdigest();meta['payloadUri']=f's3://models-qual-077051030817/payload/{archive.name}'
(out/f'payload-{version}.json').write_text(json.dumps(meta,indent=2)+'\n')
print(archive,archive.stat().st_size,meta['payloadSha256'])
