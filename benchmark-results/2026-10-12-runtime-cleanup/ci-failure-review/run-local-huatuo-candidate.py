from pathlib import Path
import subprocess,json,time,hashlib
root=Path('/Users/briansam-bodden/Code/java-ai');repo=root/'projects/models-final-integration';out=repo/'benchmark-results/2026-10-12-runtime-cleanup/ci-failure-review/huatuo-candidate';out.mkdir(exist_ok=True)
lib=repo/'models-bench/build/install/models-bench/lib';core=root/'projects/vectors-executor-overflow/vectors-core/build/libs/vectors-core-0.1.30.jar';jars=[core]+sorted(p for p in lib.glob('*.jar') if not p.name.startswith('vectors-core-'))
cmd=[str(Path.home()/'.sdkman/candidates/java/25.0.3-tem/bin/java'),'--add-modules=jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx6g','-cp',':'.join(map(str,jars)),'com.integrallis.models.bench.InferenceBenchmarkCli','determinism','--model',str(Path.home()/'.jvllm/models/HuatuoGPT-o1-7B-Q4_K_M.gguf'),'--model-id','huatuogpt-o1-7b-q4_k_m','--prompt','Question: Which organ pumps blood through the human body?\nAnswer:','--tokens','4','--iterations','3','--context','128','--prefill','sequential','--output',str(out/'panama-default.json')]
manifest={'command':cmd,'jarSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},'scope':'same scheduled CI prompt and exact-logit assertion; different host and JDK patch from failed CI; final runtime and candidate core'};(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');start=time.time()
with (out/'run.log').open('w') as f:
 try:code=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=1200).returncode
 except subprocess.TimeoutExpired:code=124
(out/'exit.json').write_text(json.dumps({'exitCode':code,'elapsedSeconds':time.time()-start})+'\n')
print(code)
