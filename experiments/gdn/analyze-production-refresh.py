"""Host-stratified paired analysis for the frozen allocation-corrected final product comparison."""
import json,math,pathlib,random,re,statistics,sys
folder=pathlib.Path(sys.argv[1]);output=pathlib.Path(sys.argv[2]);partial='--partial' in sys.argv[3:];incomplete=[]
arms=['native','published'];metrics=['loadNanos','coldRequestNanos','prefillNanos','decodeNanos','requestNanos','peakRssKiB','javaAllocatedBytes']
shards={'x86-large-a':('x86','qwen-4b',range(5)),'x86-large-b':('x86','qwen-4b',range(5,10)),
'arm-small':('arm','qwen-0.8b',range(10)),'arm-large-a':('arm','qwen-4b',range(5)),'arm-large-b':('arm','qwen-4b',range(5,10))}
def read(shard,model,pair,arm):
    prefix=folder/shard/f'{model}-pair{pair}-{arm}'
    execution=json.loads(pathlib.Path(str(prefix)+'.execution.json').read_text());assert execution['exitCode']==0,prefix
    assert execution['shard']==shard
    rows=[json.loads(line) for line in pathlib.Path(str(prefix)+'.stdout').read_text().splitlines() if line.startswith('{')]
    loads=[r for r in rows if r['kind']=='load'];assert len(loads)==1
    requests=[r for r in rows if r['kind']=='request'];assert len(requests)==35
    assert [r['repeat'] for r in requests]==list(range(-32,3))
    assert all(len(r['fullLogitSha256'])==9 for r in requests)
    warm=requests[-3:]
    rss=re.search(r'Maximum resident set size \(kbytes\):\s*(\d+)',pathlib.Path(str(prefix)+'.stderr').read_text());assert rss
    values={'loadNanos':loads[0]['nanos'],'peakRssKiB':int(rss.group(1)),'coldRequestNanos':requests[0]['requestNanos']}
    values.update({k:statistics.mean(r[k] for r in warm) for k in ['prefillNanos','decodeNanos','requestNanos','javaAllocatedBytes']})
    anchor=next(r for r in rows if r['kind']=='warmupAnchors')
    telemetry={'shard':shard,'pair':pair,'arm':arm,'anchors':anchor,'measured':[{
        k:r[k] for k in ['id','repeat','javaAllocatedBytes','compilationMillisDuringRequest','classesLoadedDuringRequest']} for r in warm]}
    return values,requests,telemetry

def paired(strata):
    logs=[[math.log(c/r) for c,r in pairs] for pairs in strata.values()]
    flat=[v for group in logs for v in group];assert len(flat)==10
    rng=random.Random(716309)
    sampled=sorted(math.exp(statistics.mean([x for group in logs for x in rng.choices(group,k=len(group))])) for _ in range(20000))
    low,high=sampled[500],sampled[19499]
    return {'ratio':math.exp(statistics.mean(flat)),'ci95':[low,high],'precisionTargetMet':high-low<=.02,
        'direction':'slower' if low>1 else 'faster' if high<1 else 'unresolved',
        'hostStrata':{name:{'ratio':math.exp(statistics.mean(group)),'processRatios':[math.exp(v) for v in group]} for name,group in zip(strata,logs)}}
results=[]
for arch in ['x86','arm']:
  for model in ['qwen-0.8b','qwen-4b']:
    required=[shard for shard,(sa,sm,pairs) in shards.items() if (sa,sm)==(arch,model)]
    if not required:continue
    unfinished=[shard for shard in required if not (folder/shard/'STATUS').exists() or (folder/shard/'STATUS').read_text().strip() not in ('COMPLETE','COMPLETE_WITH_FAILED_PUBLISHED_GATES')]
    if unfinished:
      incomplete.append({'architecture':arch,'model':model,'unfinishedShards':unfinished})
      if partial:continue
      raise AssertionError(('incomplete architecture/model',unfinished))
    samples={arm:{} for arm in arms};changes=[];telemetry=[];seen=set()
    for shard,(sa,sm,pairs) in shards.items():
      if (sa,sm)!=(arch,model):continue
      assert (folder/shard/'STATUS').read_text().strip()in ('COMPLETE','COMPLETE_WITH_FAILED_PUBLISHED_GATES'),shard
      for pair in pairs:
        assert pair not in seen;seen.add(pair);records={}
        for arm in arms:
          values,records[arm],t=read(shard,model,pair,arm);samples[arm][pair]=(shard,values);telemetry.append(t)
        for arm in arms[1:]:
          for baseline,candidate in zip(records['native'],records[arm]):
            assert (baseline['id'],baseline['repeat'])==(candidate['id'],candidate['repeat'])
            changed=[k for k in ['inputTokens','fullLogitSha256','greedyTokens','prediction'] if baseline[k]!=candidate[k]]
            if changed:changes.append({'shard':shard,'pair':pair,'arm':arm,'id':candidate['id'],'repeat':candidate['repeat'],'fields':changed})
    assert seen==set(range(10))
    comparisons=[]
    for candidate,reference in [('native','published')]:
      measured={}
      for metric in metrics:
        strata={}
        for pair in range(10):
          cs,cv=samples[candidate][pair];rs,rv=samples[reference][pair];assert cs==rs
          strata.setdefault(cs,[]).append((cv[metric],rv[metric]))
        measured[metric]=paired(strata)
      comparisons.append({'candidate':candidate,'reference':reference,'metrics':measured})
    results.append({'architecture':arch,'model':model,'processPairs':10,'warmupRequestsPerProcess':32,'changes':changes,'comparisons':comparisons,'telemetry':telemetry})
output.write_text(json.dumps({'complete':not incomplete,'incomplete':incomplete,'method':'Same-host process pairs; geometric mean; host-stratified percentile bootstrap 20000 resamples seed716309; no discarded forks','failedPublishedGates':{shard:json.loads((folder/shard/'failed-gates.json').read_text()) for shard in shards if (folder/shard/'failed-gates.json').exists()},'results':results},indent=2)+'\n')
assert not any(c['arm']!='published' for r in results for c in r['changes']),'unexpected comparison arm'
print(output)
