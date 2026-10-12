"""Keep exact recurrence checks distinct from published task-quality changes."""
import collections,json,pathlib,sys
root=pathlib.Path(sys.argv[1]);output=pathlib.Path(sys.argv[2]);results=[];pending=[]
for arch in ['x86','arm']:
 for model in ['qwen-0.8b','qwen-4b']:
  records={}
  for arm in ['baseline','native','published']:
   stem=root/(arch+'-correctness')/f'{model}-{arm}-cohort';receipt=pathlib.Path(str(stem)+'.execution.json')
   if not receipt.exists():pending.append({'architecture':arch,'model':model,'arm':arm});continue
   assert json.loads(receipt.read_text())['exitCode']==0,receipt
   rows=[json.loads(l)for l in pathlib.Path(str(stem)+'.stdout').read_text().splitlines()if l.startswith('{')]
   records[arm]={r['id']:r for r in rows if r.get('kind')=='request'};assert len(records[arm])==148
   assert all(len(r['fullLogitSha256'])==9 for r in records[arm].values())
  result={'architecture':arch,'model':model,'completedArms':list(records),'correctLabels':{a:sum(r['prediction']==r['expected']for r in rs.values())for a,rs in records.items()}}
  if 'baseline'in records and 'native'in records:
   differences=[]
   for id,n in records['native'].items():
    b=records['baseline'][id];fields=[k for k in ['inputTokens','fullLogitSha256','greedyTokens','prediction','expected']if n[k]!=b[k]]
    if fields:differences.append({'id':id,'fields':fields})
   result['javaNativeDifferences']=differences;assert not differences,('recurrence exactness failure',arch,model,differences)
  if 'published'in records and 'native'in records:
   changes=[]
   for id,n in records['native'].items():
    p=records['published'][id]
    if n['prediction']!=p['prediction']:changes.append({'id':id,'dataset':n['dataset'],'expected':n['expected'],'native':n['prediction'],'published':p['prediction'],'transition':('correct'if p['prediction']==p['expected']else'wrong')+'-to-'+('correct'if n['prediction']==n['expected']else'wrong')})
   result['publishedLabelChanges']=changes;result['publishedTransitionCounts']=dict(collections.Counter(c['transition']for c in changes))
   result['publishedDifferentFullLogitCases']=sum(n['fullLogitSha256']!=records['published'][id]['fullLogitSha256']for id,n in records['native'].items())
  results.append(result)
output.write_text(json.dumps({'complete':not pending,'pending':pending,'scope':'Independent cloned prefill plus eight decode vectors per case. Java/native exactness and published label changes are separate assertions; accuracy decreases are not relabeled as passes.','results':results},indent=2)+'\n')
print(output)
