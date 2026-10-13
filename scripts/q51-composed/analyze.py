"""Analyze every frozen composed Q51 observation without pooling older candidates."""
import pathlib,importlib.util,json,itertools,math,sys
H=pathlib.Path(__file__).resolve().parent;C=json.loads((H/'protocol.json').read_text())
spec=importlib.util.spec_from_file_location('old',H.parent/'q51-route/analyze.py');old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old);old.paired.CONFIG=C

def records(p):return [json.loads(l) for l in p.read_text().splitlines() if l.startswith('{') and json.loads(l).get('kind')=='request']
def identity(r):return (r['id'],r['inputTokens'],r['fullLogitSha256'],r['greedyTokens'],r['prediction'])
def analyze(root):
 provenance=json.loads((root/'provenance.json').read_text());assert provenance['protocol']==C
 cohorts={};quality={}
 for arm in C['variants']:
  e=json.loads((root/f'cohort-{arm}.execution.json').read_text());assert e['exitCode']==0 and not e['timedOut']
  r=records(root/f'cohort-{arm}.stdout');assert len(r)==148 and len({x['id'] for x in r})==148 and all(len(x['fullLogitSha256'])==9 for x in r)
  cohorts[arm]=[identity(x) for x in r];quality[arm]={}
  for x in r:
   q=quality[arm].setdefault(x['dataset'],{'correct':0,'total':0});q['correct']+=int(x['prediction']==x['expected']);q['total']+=1
 assert cohorts['candidate']==cohorts['hybrid'],'composed Q51 route changes full logits'
 assert [x[0] for x in cohorts['candidate']]==[x[0] for x in cohorts['published']],'published task identities/order changed'
 quality_pass=all(quality['candidate'][d]['total']==q['total'] and quality['candidate'][d]['correct']>=q['correct'] for d,q in quality['published'].items())
 comparisons=[('candidate','hybrid'),('candidate','published')];logs={a+'/'+b:{k:[] for k in C['limits']} for a,b in comparisons};history={};published_history={};published_changes=[];raw=[]
 for i in range(C['triplets']):
  receipt=json.loads((root/f'triplet-{i}.complete.json').read_text());assert receipt=={'pair':i,'order':list(list(itertools.permutations(C['variants']))[i%6])}
  values={};rows={}
  for arm in C['variants']:values[arm],rows[arm]=old.read(root,i,arm)
  for a,b in zip(rows['candidate'],rows['hybrid']):
   assert identity(a)==identity(b) and a['repeat']==b['repeat'],'timed Q51 route changes full logits'
   key=a['id'];v=identity(a);assert history.setdefault(key,v)==v,'candidate cold/warm/process instability'
  for a in rows['published']:
   key=a['id'];v=identity(a)
   if published_history.setdefault(key,v)!=v:published_changes.append({'pair':i,'id':key,'repeat':a['repeat'],'value':v})
  raw.append({'pair':i,'values':values})
  for a,b in comparisons:
   for k in C['limits']:logs[a+'/'+b][k].append(math.log(values[a][k]/values[b][k]))
 contrasts={name:{k:old.paired.bootstrap([v],C['limits'][k]) for k,v in metrics.items()} for name,metrics in logs.items()}
 all_gates=quality_pass and all(v['status']=='PASS' for metrics in contrasts.values() for v in metrics.values())
 return {'scope':C['scope'],'complete':True,'allGatesPass':all_gates,'candidateHybridExact':True,'quality':quality,'publishedQualityNonRegression':quality_pass,'publishedColdWarmChanges':published_changes,'contrasts':contrasts,'pairs':raw}
if __name__=='__main__':
 try:r=analyze(pathlib.Path(sys.argv[1]))
 except Exception as e:
  pathlib.Path(sys.argv[2]).write_text(json.dumps({'complete':False,'error':repr(e)},indent=2)+'\n');raise
 pathlib.Path(sys.argv[2]).write_text(json.dumps(r,indent=2)+'\n')
 if not r['allGatesPass']:raise SystemExit('Frozen qualification gates did not all pass; complete observations retained.')
