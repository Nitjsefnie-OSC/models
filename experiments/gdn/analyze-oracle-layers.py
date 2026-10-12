"""Compare narrow all-layer traces on identical prompt tokens; no gate changes."""
import json,pathlib,sys
import numpy as np
root=pathlib.Path(sys.argv[1]);result={}
def records(p):return [json.loads(x) for x in p.read_text().splitlines()]
def array(folder,rows,name,oracle=False):
 values=[]
 for r in rows:
  if r['name']!=name:continue
  if oracle:
   data=(folder/r['file']).read_bytes()
   x=np.ndarray(shape=tuple(r['ne'][::-1]),dtype='<f4',buffer=data,strides=tuple(r['nb'][::-1])).reshape(-1).copy()
  else:x=np.fromfile(folder/r['file'],dtype='<f4')
  values.append(x)
 return np.concatenate(values) if values else None
for model in ['qwen-0.8b','qwen-4b']:
 mf=root/(model+'-models')/'easy-intent-00'
 if not (mf/'result.json').exists():continue
 mr=records(mf/'tensors.jsonl');layers=sorted({int(r['name'].split('.')[1]) for r in mr if r['name'].startswith('blk.')})
 result[model]={}
 for arm in ['baseline-32-repack1','baseline-32-repack0','additive-32-repack1','additive-32-repack0']:
  of=root/(model+'-'+arm)/'easy-intent-00'
  if not (of/'result.json').exists():continue
  assert json.loads((mf/'result.json').read_text())['inputTokens']==json.loads((of/'result.json').read_text())['inputTokens']
  orr=records(of/'tensors.jsonl');rows=[]
  for layer in layers:
   names={r['name'] for r in mr};linear=f'blk.{layer}.ssm_out.weight.output' in names
   mapping=[('normalizedInput','attn_qkv.weight.input' if linear else 'attn_q.weight.input','attn_norm'),
    ('attentionOutput','ssm_out.weight.output' if linear else 'attn_output.weight.output','linear_attn_out' if linear else 'attn_output'),
    ('ffnNormalizedInput','ffn_gate.weight.input','attn_post_norm'),('ffnOutput','ffn_down.weight.output','ffn_out')]
   for role,weight,oracle_name in mapping:
    a=array(mf,mr,f'blk.{layer}.'+weight);b=array(of,orr,f'{oracle_name}-{layer}',True)
    assert a is not None and b is not None,(layer,role,oracle_name)
    assert a.shape==b.shape,(layer,role,a.shape,b.shape)
    assert np.isfinite(a).all() and np.isfinite(b).all()
    error=a.astype(np.float64)-b.astype(np.float64)
    rows.append({'layer':layer,'attention':'linear' if linear else 'full','role':role,'elements':len(a),
     'maxAbsError':float(np.max(np.abs(error))),'rmse':float(np.sqrt(np.mean(error*error))),
     'referenceRms':float(np.sqrt(np.mean(b.astype(np.float64)**2))),'exactElements':int(np.count_nonzero(a==b))})
  result[model][arm]=rows
print(json.dumps({'scope':'diagnostic all-layer trace, not a qualification verdict','models':result},indent=2))
