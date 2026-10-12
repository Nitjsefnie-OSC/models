import json,pathlib,subprocess,os
root=pathlib.Path('/work/out');commands=[];java='/opt/jdk/bin/java'
def run(label,command,timeout=90,required=True):
 commands.append({'label':label,'command':command});(root/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
 with (root/(label+'.log')).open('w') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
 commands[-1]['exitCode']=r.returncode;(root/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
 subprocess.run(['aws','s3','cp','/work/out',os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
 if required and r.returncode:raise RuntimeError(label+' failed')
run('paired-jmh',['python3','run-paired-jmh.py','--java',java,'--baseline','baseline.jar','--fixed','fixed.jar','--benchmark','benchmark.jar','--profile-gc','--warmups','5','--iterations','5','--output','/work/out/paired'],600)
for label in ['0-baseline','1-fixed','2-fixed','3-baseline']:
 rows=json.loads((root/'paired'/(label+'.json')).read_text());assert len(rows)==6 and all(r['primaryMetric']['score']>0 for r in rows)
common=[java,'--add-modules','jdk.incubator.vector','-XX:ActiveProcessorCount=4','-Xmx512m']
run('baseline-cold-hot',common+['-cp','baseline.jar:probes.jar:benchmark.jar','com.integrallis.vectors.core.BFloat16MatrixColdHotProbe','48','5120'],required=False)
for bits in [64,128,256]:
 for rows,cols in [(48,5120),(5,37),(8193,37),(512,2048)]:
  run(f'fixed-cold-hot-{bits}-{rows}-{cols}',common+[f'-Dvectors.maxBits={bits}','-cp','fixed.jar:probes.jar:benchmark.jar','com.integrallis.vectors.core.BFloat16MatrixColdHotProbe',str(rows),str(cols)])
for arm in ['baseline','fixed']:
 run(arm+'-allocation',common+['-cp',arm+'.jar:probes.jar:benchmark.jar','com.integrallis.vectors.core.BFloat16MatrixAllocationProbe'])
(root/'STATUS').write_text('BF16_PAIRED_COMPLETE\n')
