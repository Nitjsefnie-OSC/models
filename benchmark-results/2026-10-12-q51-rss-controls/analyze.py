from pathlib import Path
import json,math,random,statistics,re
base=Path('/Users/briansam-bodden/Code/java-ai/projects/models-gdn-proof/benchmark-results/2026-10-11-gdn-proof/remote-q51-rss-controls-v2');result=[]
for arch in ['x86','arm']:
 for arm in ['candidate','scalar-parallel','simd-serial','scalar-serial']:
  stats={m:[] for m in ['latency','allocation','peakRss','load']}
  for pair in range(5):
   values=[]
   for which in ['baseline',arm]:
    p=base/arch/f'bert-timing-{pair}-{which}.log';text=p.read_text();r=[json.loads(x) for x in text.splitlines() if x.startswith('{')];assert any(x['kind']=='completed' for x in r);values.append(({(x['id'],x['repeat']):x for x in r if x['kind']=='embedding'},int(re.search(r'Maximum resident set size \(kbytes\): (\d+)',text)[1]),next(x['nanos'] for x in r if x['kind']=='load')))
   for metric,key in [('latency','nanos'),('allocation','allocatedBytes')]:stats[metric].append(statistics.mean(math.log(values[1][0][k][key]/values[0][0][k][key]) for k in values[0][0]))
   stats['peakRss'].append(math.log(values[1][1]/values[0][1]));stats['load'].append(math.log(values[1][2]/values[0][2]))
  for metric,vs in stats.items():
   rng=random.Random(724);b=sorted(math.exp(sum(rng.choices(vs,k=5))/5) for _ in range(10000));result.append(dict(arch=arch,control='baseline',candidate=arm,metric=metric,pairs=5,ratio=math.exp(statistics.mean(vs)),ci95=[b[250],b[9750]]))
print(json.dumps(result,indent=2))
