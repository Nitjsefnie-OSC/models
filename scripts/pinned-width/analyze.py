import json,math,random,statistics,sys
from pathlib import Path
P=json.loads((Path(__file__).parent/'protocol.json').read_text())
def interval(values,limit):
 rng=random.Random(P['bootstrapSeed'])
 if all(v==0 for v in values):return {'ratio':0.0,'ci95':[0.0,0.0],'gate':True}
 if any(v<=0 or not math.isfinite(v) for v in values):return {'ratios':[v if math.isfinite(v) else None for v in values],'gate':False,'reason':'zero baseline or mixed zero allocation; no finite geometric interval'}
 logs=[math.log(v) for v in values]
 samples=sorted(math.exp(statistics.mean(rng.choices(logs,k=len(logs)))) for _ in range(P['bootstrapResamples']))
 low=samples[int(.025*len(samples))];high=samples[int(.975*len(samples))]
 return {'ratio':math.exp(statistics.mean(logs)),'ci95':[low,high],'gate':high<=limit and high-low<=P['maximumIntervalWidth'],'ratios':values}
def analyze(root):
 provenance=json.loads((root/'provenance.json').read_text());assert provenance['protocol']==P
 summary={}
 for kernel in ('dot','decoder'):
  values={size:{'nanos':[],'bytes':[]} for size in P['shapes']}
  for pair in range(P['pairs']):
   assert json.loads((root/f'{kernel}-{pair}.complete.json').read_text())=={'pair':pair,'kernel':kernel,'numericalExact':True}
   arms={};signatures=[]
   for arm in ('baseline','candidate'):
    prefix=root/f'{kernel}-{pair}-{arm}'
    assert json.loads(prefix.with_suffix('.execution.json').read_text())['exitCode']==0
    records=[json.loads(l) for l in prefix.with_suffix('.stdout').read_text().splitlines() if l.startswith('{')]
    signatures.append({'outputs':[r for r in records if r['kind']=='output'],'blocks':[(r['size'],r['block'],r['checksum']) for r in records if r['kind']=='measure']})
    arms[arm]={}
    for size in P['shapes']:
     rows=[r for r in records if r['kind']=='measure' and r['size']==size]
     assert sorted(r['block'] for r in rows)==list(range(5))
     assert all(r['calls']==1000 for r in rows)
     arms[arm][size]={k:statistics.mean(r[k]/r['calls'] for r in rows) for k in ('nanos','bytes')}
   assert signatures[0]==signatures[1],'numeric difference in timed observations'
   for size in P['shapes']:
    for metric in ('nanos','bytes'):
     a=arms['candidate'][size][metric];b=arms['baseline'][size][metric]
     values[size][metric].append(a/b if b else (1.0 if not a else float('inf')))
  summary[kernel]={size:{m:interval(v,P['latencyRatioMaximum'] if m=='nanos' else P['allocationRatioMaximum']) for m,v in metrics.items()} for size,metrics in values.items()}
 return {'scope':P['scope'],'pairs':P['pairs'],'independentKernels':summary,'notFinalReleaseQualification':True}
if __name__=='__main__':Path(sys.argv[2]).write_text(json.dumps(analyze(Path(sys.argv[1])),indent=2)+'\n')
