from pathlib import Path
import json,subprocess,time,hashlib
repo=Path('/Users/briansam-bodden/Code/java-ai/projects/models-final-integration');root=repo/'benchmark-results/2026-10-12-runtime-cleanup/ci-failure-review';m=json.loads((root/'huatuo-candidate/manifest.json').read_text());base=m['command'];lib=repo/'models-bench/build/install/models-bench/lib';results=[]
for name,opts,published in [('published-core-control',[],True),('panama-serial',['-Dvectors.gguf.parallel=false'],False),('panama-no-fma',['-Dvectors.gguf.parallel=false','-Dvectors.useVectorFMA=false','-Dvectors.useScalarFMA=false'],False),('panama-128',['-Dvectors.gguf.parallel=false','-Dvectors.maxBits=128'],False),('scalar',['-Dvectors.forceScalar=true','-Dvectors.gguf.parallel=false'],False)]:
 out=root/('huatuo-'+name);out.mkdir(exist_ok=True);cmd=base.copy();cmd[-1]=str(out/'report.json');cmd[1:1]=opts
 if published:
  ci=cmd.index('-cp')+1;cp=cmd[ci].split(':');cp[0]=str(lib/'vectors-core-0.1.29.jar');cmd[ci]=':'.join(cp)
 (out/'command.json').write_text(json.dumps(cmd,indent=2)+'\n');start=time.time()
 with (out/'run.log').open('w') as f:
  try:code=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=600).returncode
  except subprocess.TimeoutExpired:code=124
 v={'configuration':name,'exitCode':code,'elapsedSeconds':time.time()-start};results.append(v);(out/'exit.json').write_text(json.dumps(v)+'\n');(root/'huatuo-matrix-results.json').write_text(json.dumps(results,indent=2)+'\n');print(v,flush=True)
