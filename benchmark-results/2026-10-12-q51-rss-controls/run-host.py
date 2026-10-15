"""Frozen Q5 production paths, real BERT and derived Q5_1 causal fixture."""
import json,os,pathlib,subprocess,time,urllib.request,hashlib,platform
out=pathlib.Path('/work/out');commands=[]
def run(name,cmd,timeout):
 (out/'STATUS').write_text(name+'\n');start=time.monotonic()
 with (out/(name+'.log')).open('w') as log:r=subprocess.run(['/usr/bin/time','-v',*cmd],stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
 commands.append(dict(name=name,command=cmd,exitCode=r.returncode,wallSeconds=time.monotonic()-start));(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
 subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
 assert r.returncode==0,name
def download(url,path,sha):
 urllib.request.urlretrieve(url,path);assert hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()==sha
base=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g','-Dmodels.native.kernels.threads=16','-Dvectors.gguf.threads=16']
def embedding(arm,phase,data,reps):return base+['-cp',arm+'.jar:probe.jar:lib/*','experiments.q5.EmbeddingRouteProof','java','bert.gguf','unused',data,phase,str(reps)]
def rows(name):return [json.loads(s) for s in (out/(name+'.log')).read_text().splitlines() if s.startswith('{')]
download('https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/544f204f2eaa2d71361ffc74d6df7170285b286a/all-MiniLM-L6-v2-Q5_K_S.gguf','bert.gguf','0de5c3d1343904debb305a2f6c55f57ccdc68786968b5d4726dd404f6c4c4244')
arms=['baseline','candidate','scalar-parallel','simd-serial','scalar-serial']
reference=None
for arm in arms:
 run('bert-correctness-'+arm,embedding(arm,'correctness','data/cases.json',1),180)
 result={r['id']:r['values'] for r in rows('bert-correctness-'+arm) if r['kind']=='embedding'}
 assert len(result)==82
 if reference is None:reference=result
 else:assert reference==result,arm
for pair in range(5):
 order=arms[pair:]+arms[:pair]
 if pair%2:order=list(reversed(order))
 for arm in order:run('bert-timing-'+str(pair)+'-'+arm,embedding(arm,'timing','data/timing.json',3),150)
(out/'STATUS').write_text('COMPLETE\n')
