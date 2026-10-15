"""Freeze released-runtime smoke inputs from hashed, published RAG evidence."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[2]; catalog=root.parent/'model-jars/catalog'; out=root/'benchmark-results/2026-10-11-bonsai-production'
q=json.loads((catalog/'qualifications.json').read_text())['entries']; models={m['id']:m for m in json.loads((catalog/'models.json').read_text())['models']}
jobs=[]
for e in q:
 if not e.get('qualified'): continue
 m=models[e['modelId']]; b=(root/e['report']).read_bytes(); assert hashlib.sha256(b).hexdigest()==e['reportSha256'],e['modelId']
 r=json.loads(b); s=r['settings']; assert r['artifactSha256']==m['sha256'] and r['artifactSizeBytes']==m['sizeBytes'],e['modelId']
 j={'id':m['id'],'format':m['format'],'backend':e['backend'],'revision':m['revision'],'wl':s.get('workload',e['workload']),'tpl':s['promptTemplate'],'context':s['contextLength'],'threads':s['threads'],'mt':s['maxOutputTokens'],'topK':s['retrievalTopK'],'settingsSource':{'modelId':r['modelId'],'report':e['report'],'reportSha256':e['reportSha256']}}
 stops=s['generationControls'].get('stopSequences')
 if stops: j['stopSequence']=stops
 if m['format']=='gguf':j['files']=[{'path':'model.gguf','sha256':m['sha256'],'sizeBytes':m['sizeBytes'],'uri':m['downloadUri']}]
 else:
  j['files']=[{**f,'uri':m['sourceUri']+'/resolve/'+m['revision']+'/'+f['path']} for f in m['files']]
 jobs.append(j)
assert len(jobs)==len({j['id'] for j in jobs})==65
jobs.sort(key=lambda j:sum(f['sizeBytes'] for f in j['files']))
(out/'released-catalog-regression-jobs.json').write_text(json.dumps(jobs,indent=2)+'\n')
# Validate against the exact deployed helper, without downloading weights.
worker=(root/'scripts/fleet/smoke-worker.sh').read_text(); helper=worker.split("<<'ARTIFACT_HELPER'\n",1)[1].split('\nARTIFACT_HELPER',1)[0]
ns={'__name__':'manifest_validator'};exec(compile(helper,'smoke-worker-helper','exec'),ns)
for j in jobs: ns['validate_job'](j)
print(len(jobs),'validated jobs;',sum(sum(f['sizeBytes'] for f in j['files']) for j in jobs),'bytes')
print([(j['id'],j.get('stopSequence')) for j in jobs if 'stopSequence'in j])
