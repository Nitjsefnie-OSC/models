"""Complete fixed-case aggregation; no per-shard threshold substitution or historical pooling."""
import importlib.util,json,pathlib,sys
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('run',HERE/'run.py');run=importlib.util.module_from_spec(spec);spec.loader.exec_module(run)
P=run.P

def coverage(parts,expected):
 flat=[id for part in parts for id in part]
 assert flat==expected,'missing, duplicated, reordered or substituted case identity'
 assert len(flat)==len(set(flat))==148

def combine(root):
 complete={};expected=[id for part in P['shardCaseIds'] for id in part]
 for arch in ['x86','arm']:
  for model in ['qwen-0.8b','qwen-4b']:
   summaries=[]
   for shard in range(4):
    folder=root/f'gdn-sharded-{arch}-{model}-{shard}-analyze'
    provenance=json.loads((folder/'provenance.json').read_text())
    assert provenance['protocol']==P and provenance['shard']==shard and provenance['model']==model
    summary=run.analyze(folder);assert summary['caseIds']==P['shardCaseIds'][shard]
    if shard==0:
     for arm in ['native','baseline']:
      state=json.loads((folder/f'state-{arm}.execution.json').read_text());assert state['exitCode']==0 and not state['timedOut']
      records=run.records(folder/f'state-{arm}.stdout')
      for kind,count in [('fresh-session',6),('implicit-reset',2),('batch-parity',2),('complete',1)]:assert sum(r.get('kind')==kind for r in records)==count
    summaries.append(summary)
   coverage([s['caseIds'] for s in summaries],expected)
   quality={}
   for arm in ['native','baseline','published']:
    quality[arm]={}
    for summary in summaries:
     for dataset,value in summary['quality'][arm].items():
      target=quality[arm].setdefault(dataset,{'correct':0,'total':0})
      for key in ['correct','total']:target[key]+=value[key]
   assert quality['native']==quality['baseline']
   assert {d:v['total'] for d,v in quality['native'].items()}=={'jevbench':120,'snips':28}
   gate=all(quality['native'][d]['correct']>=v['correct'] for d,v in quality['published'].items())
   complete[f'{arch}/{model}']={'cases':148,'vectorsPerCase':9,'currentRecurrenceExact':True,'stateChecksPass':True,'quality':quality,'publishedQualityNonRegression':gate,'publishedRoutingPassByShard':[s['publishedRoutingPass'] for s in summaries]}
 return {'protocol':P,'processBoundaryScope':P['processBoundaryScope'],'strata':complete,'allGatesPass':all(x['publishedQualityNonRegression'] for x in complete.values())}
if __name__=='__main__':
 result=combine(pathlib.Path(sys.argv[1]));pathlib.Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+'\n')
 assert result['allGatesPass'],'final task quality regressed'
