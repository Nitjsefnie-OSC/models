import concurrent.futures,hashlib,json,pathlib,shutil,subprocess,tarfile,urllib.request
root=pathlib.Path('/Users/briansam-bodden/Code/java-ai/projects/models')
out=pathlib.Path('/tmp/models-rag-bench-0.3.58-bonsai-catalog-baseline');out.mkdir(exist_ok=False);(out/'lib').mkdir()
revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
def fetch(source):
 name=source.name;row={}
 if name.startswith(('models-','backend-','vectors-')) and not name.startswith('models-rag-bench-'):
  artifact=name[:-4].rsplit('-',1)[0];version='0.1.29' if artifact.startswith('vectors-') else '0.3.58';name=f'{artifact}-{version}.jar';url=f'https://repo.maven.apache.org/maven2/com/integrallis/{artifact}/{version}/{name}'
  raw=urllib.request.urlopen(url,timeout=120).read();sha1=urllib.request.urlopen(url+'.sha1',timeout=30).read().decode().strip().split()[0];assert hashlib.sha1(raw).hexdigest()==sha1
  row={'origin':'Maven Central','url':url,'centralSha1':sha1}
 else:raw=source.read_bytes();row={'origin':'source-built measurement harness' if name.startswith('models-rag-bench-') else 'resolved external dependency'}
 (out/'lib'/name).write_bytes(raw);return {**row,'file':name,'sha256':hashlib.sha256(raw).hexdigest(),'sizeBytes':len(raw)}
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:records=list(pool.map(fetch,sorted((root/'models-rag-bench/build/install/models-rag-bench/lib').glob('*.jar'))))
(out/'classpath.json').write_text(json.dumps(records,indent=2)+'\n')
(out/'provenance.json').write_text(json.dumps({'modelsVersion':'0.3.58','vectorsVersion':'0.1.29','harnessRevision':revision,'purpose':'Same default worker and frozen manifest; reproduce six previous smoke failures before the next release','productSource':'Downloaded Maven Central bytes, hashes verified'},indent=2)+'\n')
shutil.copyfile(__file__,out/'prepare-payload.py')
archive=pathlib.Path(str(out)+'.tar')
with tarfile.open(archive,'w') as t:t.add(out,arcname=out.name)
sha=hashlib.sha256(archive.read_bytes()).hexdigest();uri='s3://models-qual-077051030817/payload/'+archive.name
subprocess.run(['aws','s3','cp',str(archive),uri,'--only-show-errors'],check=True)
print(json.dumps({'payload':archive.name,'sha256':sha,'revision':revision,'uri':uri}))
