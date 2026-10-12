"""Read-only tensor diagnostics; never changes a qualification gate."""
import json,pathlib,sys
import numpy as np
root=pathlib.Path(sys.argv[1]);result={'scope':'diagnosis only; original independent greedy gate remains unchanged','models':{}}
variants=sys.argv[2:] or ['baseline-512','baseline-32','additive-32']
def records(path):return [json.loads(s) for s in path.read_text().splitlines()] if path.exists() else []
def metrics(a,b):
    assert a.shape==b.shape,(a.shape,b.shape)
    assert np.isfinite(a).all() and np.isfinite(b).all()
    da=a.astype(np.float64);db=b.astype(np.float64);err=da-db
    return {'elements':a.size,'exactElements':int(np.count_nonzero(a==b)),
        'maxAbsError':float(np.max(np.abs(err))),'rmse':float(np.sqrt(np.mean(err**2))),
        'referenceRms':float(np.sqrt(np.mean(db**2))),'meanError':float(np.mean(err))}
def top_two(values):
    winner=int(np.argmax(values));remaining=values.copy();remaining[winner]=-np.inf
    return np.array([winner,int(np.argmax(remaining))])
def q8k_difference(a,b):
    # Replay the pinned GGML/Models Q8_K reference quantizer on identical block boundaries.
    assert a.size%256==0 and b.size==a.size
    def quantize(x):
      blocks=x.reshape(-1,256);maximum=blocks[np.arange(len(blocks)),np.argmax(np.abs(blocks),axis=1)]
      inv=np.zeros_like(maximum);np.divide(np.float32(-127),maximum,out=inv,where=maximum!=0)
      product=np.float32(blocks*inv[:,None]);bits=np.float32(product+np.float32(12582912)).view(np.uint32)
      q=np.minimum((bits&0x007fffff).astype(np.int32)-0x00400000,127).astype(np.int8)
      scale=np.zeros_like(inv);np.divide(np.float32(1),inv,out=scale,where=inv!=0)
      return q,scale
    qa,sa=quantize(a);qb,sb=quantize(b)
    return {'quantizedIntegersDifferent':int(np.count_nonzero(qa!=qb)),
        'blocksWithDifferentIntegers':int(np.count_nonzero(np.any(qa!=qb,axis=1))),
        'scaleBitsDifferent':int(np.count_nonzero(sa.view(np.uint32)!=sb.view(np.uint32))),
        'blocks':len(sa)}
def oracle_tensor(folder,row):
    data=(folder/row['file']).read_bytes()
    return np.ndarray(shape=tuple(row['ne'][::-1]),dtype='<f4',buffer=data,strides=tuple(row['nb'][::-1])).reshape(-1).copy()
def models_logit(folder,step):
    rows=records(folder/'tensors.jsonl');r=next(r for r in rows if r['name']==f'logits-{step}')
    return np.fromfile(folder/r['file'],dtype='<f4')
mapping={'attn_qkv.weight':'linear_attn_qkv_mixed-0','attn_gate.weight':'z-0',
    'ssm_beta.weight':'beta-0','ssm_alpha.weight':'alpha-0','ssm_out.weight':'linear_attn_out-0',
    'ffn_gate.weight':'ffn_gate-0','ffn_up.weight':'ffn_up-0','ffn_down.weight':'ffn_out-0'}
for model in ['qwen-0.8b','qwen-4b']:
  models=root/(model+'-models')
  if not models.exists():continue
  results={};result['models'][model]=results
  for arm in variants:
    oracle=root/(model+'-'+arm)
    if not oracle.exists():continue
    rows=[]
    for request in sorted(models.glob('*/result.json')):
      case=request.parent.name;target=oracle/case
      if not (target/'result.json').exists():continue
      actual=json.loads(request.read_text());reference=json.loads((target/'result.json').read_text());assert actual['inputTokens']==reference['inputTokens']
      steps=[]
      for step in range(8):
        a=models_logit(request.parent,step);b=np.fromfile(target/f'logits-{step}.f32',dtype='<f4');m=metrics(a,b)
        topa=top_two(a);topb=top_two(b)
        assert topa[0]==actual['tokens'][step] and topb[0]==reference['tokens'][step], 'captured logits disagree with recorded greedy token'
        m.update(step=step,modelsTopTokens=topa.tolist(),oracleTopTokens=topb.tolist(),modelsMargin=float(a[topa[0]]-a[topa[1]]),
            oracleMargin=float(b[topb[0]]-b[topb[1]]),modelsGapAtOracleWinner=float(a[topa[0]]-a[topb[0]]),
            oracleGapAtModelsWinner=float(b[topb[0]]-b[topa[0]]))
        steps.append(m)
        if actual['tokens'][step]!=reference['tokens'][step]:break
      rows.append({'id':case,'exactTokens':actual['tokens']==reference['tokens'],'modelsTokens':actual['tokens'],
        'oracleTokens':reference['tokens'],'comparablePrefixSteps':steps})
    tensor_rows=[];mf=models/'easy-intent-00';of=oracle/'easy-intent-00';mr=records(mf/'tensors.jsonl');orr=records(of/'tensors.jsonl')
    for weight,oracle_name in mapping.items():
      selected=[r for r in mr if r['name']=='blk.0.'+weight+'.output'];other=[r for r in orr if r['name']==oracle_name]
      if not selected or not other:continue
      a=np.concatenate([np.fromfile(mf/r['file'],dtype='<f4') for r in selected]);b=np.concatenate([oracle_tensor(of,r) for r in other])
      if a.shape!=b.shape:
        tensor_rows.append({'weight':weight,'oracleTensor':oracle_name,'modelsShape':list(a.shape),'oracleShape':list(b.shape),'comparable':False});continue
      tensor_rows.append({'weight':weight,'oracleTensor':oracle_name,'comparable':True,**metrics(a,b)})
    # Follow the graph from its first normalized input through the recurrent and FFN outputs.
    for weight,oracle_name in [('attn_qkv.weight','attn_norm-0'),('ssm_out.weight','final_output-0'),('ffn_gate.weight','attn_post_norm-0')]:
      selected=[r for r in mr if r['name']=='blk.0.'+weight+'.input'];other=[r for r in orr if r['name']==oracle_name]
      if selected and other:
        a=np.concatenate([np.fromfile(mf/r['file'],dtype='<f4') for r in selected]);b=np.concatenate([oracle_tensor(of,r) for r in other])
        if a.shape==b.shape:tensor_rows.append({'weight':weight+'.input','oracleTensor':oracle_name,'comparable':True,**metrics(a,b),'q8KReferenceReplay':q8k_difference(a,b)})
    results[arm]={'cases':rows,'exactCases':sum(r['exactTokens'] for r in rows),'firstLayer':tensor_rows,
       'availableOracleTensorNames':sorted({r['name'] for r in orr}),'availableModelsTensorNames':sorted({r['name'] for r in mr if 'logits-' not in r['name']})}
print(json.dumps(result,indent=2))
