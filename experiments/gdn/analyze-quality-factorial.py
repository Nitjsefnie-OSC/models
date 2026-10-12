"""Attribute label changes on a fixed public cohort; no quality gate is weakened."""
import json,pathlib,sys
folder=pathlib.Path(sys.argv[1]);output=pathlib.Path(sys.argv[2]);arms=['published','activation','projection','combined'];records={};summary={}
for arm in arms:
 rows=[json.loads(l) for l in (folder/(arm+'.stdout')).read_text().splitlines() if l.startswith('{')]
 requests=[r for r in rows if r['kind']=='request'];assert len(requests)==148
 records[arm]={r['id']:r for r in requests};assert len(records[arm])==148
 summary[arm]={'correct':sum(r['prediction']==r['expected'] for r in requests),'total':148,
 'perDataset':{dataset:{'correct':sum(r['prediction']==r['expected'] for r in requests if r['dataset']==dataset),'total':sum(r['dataset']==dataset for r in requests)} for dataset in sorted({r['dataset'] for r in requests})}}
changes=[]
for id,base in records['published'].items():
 for arm in arms:assert records[arm][id]['inputTokens']==base['inputTokens'] and records[arm][id]['expected']==base['expected']
 predictions={arm:records[arm][id]['prediction'] for arm in arms}
 if len(set(predictions.values()))>1:
  evidence={}
  for arm in arms:
   r=records[arm][id];scores=r['labelScores'];ordered=sorted(scores.values(),reverse=True)
   evidence[arm]={'prediction':r['prediction'],'scores':scores,'topTwoMargin':ordered[0]-ordered[1],'correct':r['prediction']==r['expected']}
  changes.append({'id':id,'expected':base['expected'],'arms':evidence})
comparisons=[]
for a,b in [('activation','published'),('projection','published'),('combined','projection'),('combined','activation'),('combined','published')]:
 comparisons.append({'candidate':a,'reference':b,'changedLabels':sum(records[a][id]['prediction']!=records[b][id]['prediction'] for id in records[a]),
 'changedFullLogits':sum(records[a][id]['prefillLogitsSha256']!=records[b][id]['prefillLogitsSha256'] for id in records[a]),
 'correctToWrong':sum(records[b][id]['prediction']==records[b][id]['expected'] and records[a][id]['prediction']!=records[a][id]['expected'] for id in records[a]),
 'wrongToCorrect':sum(records[b][id]['prediction']!=records[b][id]['expected'] and records[a][id]['prediction']==records[a][id]['expected'] for id in records[a])})
report={'cohort':148,'model':'Qwen3.5-0.8B Q4_K_M','host':'m6a.xlarge, shipping four-worker defaults','interpretation':'Descriptive causal diagnostic, not production performance or a relaxed task-quality gate. Projection arm includes the already documented projection/ownership corrected Java artifact; combined uses final Java recurrence ablation.','summary':summary,'comparisons':comparisons,'changedLabelCases':changes}
output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'summary':summary,'comparisons':comparisons},indent=2))
