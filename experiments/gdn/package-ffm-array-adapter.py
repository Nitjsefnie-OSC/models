import pathlib,hashlib,json,shutil,subprocess,tarfile
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof';v='ffm-array-adapter-v1';p=root/'experiments/gdn/generated'/('payload-'+v);p.mkdir(exist_ok=False);final=root/'experiments/gdn/generated/payload-production-final-v9';candidate=root.parent/'models-gdn-production'
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=candidate,text=True).strip();assert revision.startswith('d52bf310')
for arm in ['baseline','adapter']:shutil.copytree(final/'lib/native',p/'lib'/arm)
shutil.copytree(final/'rust/native',p/'rust');shutil.copy(root/'experiments/gdn/run-ffm-array-adapter-host.py',p/'run-host.py')
shutil.copy(root/'experiments/gdn/FfmArrayAllocationProbe.java',p/'FfmArrayAllocationProbe.java')
source=candidate/'backend-native/src/main/java/com/integrallis/models/backend/nativekernel/NativeKernelLibrary.java';shutil.copy(source,p/'NativeKernelLibrary.java')
(p/'candidate.patch').write_bytes(subprocess.check_output(['git','diff','fbae54da','HEAD','--',str(source.relative_to(candidate))],cwd=candidate))
jdk=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin');classes=p/'classes';classes.mkdir()
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(p/'lib/adapter/*'),'-d',str(classes),str(p/'NativeKernelLibrary.java')],check=True)
jar=next((p/'lib/adapter').glob('backend-native-*.jar'));subprocess.run([str(jdk/'jar'),'--update','--file',str(jar),'-C',str(classes),'.'],check=True);shutil.rmtree(classes);classes.mkdir()
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',str(p/'lib/baseline/*'),'-d',str(classes),str(p/'FfmArrayAllocationProbe.java')],check=True)
subprocess.run([str(jdk/'jar'),'--create','--file',str(p/'probe.jar'),'-C',str(classes),'.'],check=True);shutil.rmtree(classes)
(p/'protocol.txt').write_text('Same actual native library, baseline vs array-filter Java bridge. Model dimensions16heads128keys128values; token1 and32; explicit one nativeworker for component isolation. Fixed20000/2000warm calls andfive2000/200call measurements respectively. Exactfullstate/outputSHA eachsample; Java allocation primarydiagnostic. No wholemodelperformance or productionadoption claim. No buffer caches.\n')
base=json.loads((final/'provenance.json').read_text());meta={k:base[k] for k in ['jdk','rust']};meta.update(runName='gdn-proof-'+v,maximumSecondsPerHost=1800,maximumCombinedUsd=.25,revision=revision)
meta['files']={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(p.rglob('*')) if f.is_file()};(p/'provenance.json').write_text(json.dumps(meta,indent=2)+'\n');(p/'inputs.sha256').write_text(''.join(f'{sha}  {name}\n' for name,sha in meta['files'].items()))
a=out/f'gdn-proof-{v}.tar.gz'
with tarfile.open(a,'w:gz',compresslevel=1) as t:
 for f in sorted(p.iterdir()):t.add(f,arcname=f.name)
meta['payloadSha256']=hashlib.sha256(a.read_bytes()).hexdigest();meta['payloadUri']=f's3://models-qual-077051030817/payload/{a.name}';(out/f'payload-{v}.json').write_text(json.dumps(meta,indent=2)+'\n');print(a,meta['payloadSha256'])
