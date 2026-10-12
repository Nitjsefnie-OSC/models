"""Explain captured Q5_K projection differences from fused quantizer rounding.

Float32 operands multiply exactly in float64 (at most 48 significant bits).
Rounding that product to integer models fused multiply/add nearest_int;
rounding to float32 first models the explicit separate operations in Models.
Only recorded inputs and original weight bytes are used; no model is executed.
"""
import hashlib,json,pathlib,struct,sys
import numpy as np
sys.path.insert(0,sys.argv[1]);from gguf import GGUFReader
model=pathlib.Path(sys.argv[2]);root=pathlib.Path(sys.argv[3]);out=pathlib.Path(sys.argv[4]);out.mkdir(exist_ok=False)
reader=GGUFReader(str(model),'r');tensor=next(t for t in reader.tensors if t.name=='blk.0.attn_qkv.weight')
assert tensor.tensor_type.name=='Q5_K' and tensor.shape.tolist()==[1024,6144]
mf=root/'qwen-0.8b-models/easy-intent-00';of=root/'qwen-0.8b-baseline-512/easy-intent-00'
def records(p):return [json.loads(x) for x in p.read_text().splitlines()]
def capture(folder,name):
 rows=[r for r in records(folder/'tensors.jsonl') if r['name']==name]
 return np.concatenate([np.fromfile(folder/r['file'],dtype='<f4') for r in rows]),[
  {'file':str(folder/r['file']),'sha256':hashlib.sha256((folder/r['file']).read_bytes()).hexdigest()} for r in rows]
inputs,files=capture(mf,'blk.0.attn_qkv.weight.input');x=inputs.reshape(-1,256)
maximum=x[np.arange(len(x)),np.argmax(np.abs(x),axis=1)];inv=np.float32(-127)/maximum
separate=np.rint(np.float32(x*inv[:,None])).astype(np.int32)
fused=np.rint(x.astype(np.float64)*inv[:,None].astype(np.float64)).astype(np.int32)
changes=np.argwhere(separate!=fused)
a,af=capture(mf,'blk.0.attn_qkv.weight.output');b,bf=capture(of,'linear_attn_qkv_mixed-0')
a=a.reshape(-1,6144);b=b.reshape(-1,6144);report=[]
with model.open('rb') as f:
 for block,lane in changes:
  token,local_block=divmod(int(block),4);lane=int(lane);delta=int(fused[block,lane]-separate[block,lane])
  scale=float(np.float32(1)/inv[block]);predicted=[];raw_blocks=[]
  for row in range(6144):
   offset=tensor.data_offset+(row*4+local_block)*176;f.seek(offset);raw=f.read(176);raw_blocks.append(raw)
   group,l=divmod(lane,32);codes=raw[48+(group//2)*32+l]
   q=((codes>>(4 if group%2 else 0))&15)+(((raw[16+l]>>group)&1)<<4)
   packed=raw[4:16]
   if group<4:sc=packed[group]&63;minimum=packed[group+4]&63
   else:sc=(packed[group+4]&15)|((packed[group-4]>>6)<<4);minimum=(packed[group+4]>>4)|((packed[group]>>6)<<4)
   d,dmin=struct.unpack_from('<ee',raw,0)
   predicted.append((d*sc*q-dmin*minimum)*scale*delta)
  predicted=np.array(predicted);observed=b[token].astype(np.float64)-a[token].astype(np.float64)
  assert np.isfinite(predicted).all()
  rawfile=out/f'token-{token}-q5k-block-{local_block}.bin';rawfile.write_bytes(b''.join(raw_blocks))
  predfile=out/f'token-{token}-predicted-delta.f64';predicted.astype('<f8').tofile(predfile)
  worst=int(np.argmax(np.abs(observed)))
  report.append({'token':token,'inputColumn':local_block*256+lane,'globalBlock':int(block),
   'separateQ':int(separate[block,lane]),'fusedQ':int(fused[block,lane]),'input':float(x[block,lane]),
   'inverseScale':float(inv[block]),'activationScale':scale,'changedIntegers':delta,
   'maxObservedOutputDifference':float(np.max(np.abs(observed))),
   'maxErrorAfterAccountingForSingleQuantizerInteger':float(np.max(np.abs(observed-predicted))),
   'worstRow':worst,'worstObserved':float(observed[worst]),'worstPredicted':float(predicted[worst]),
   'rawWeightBlocks':rawfile.name,'rawWeightBlocksSha256':hashlib.sha256(rawfile.read_bytes()).hexdigest(),
   'predictedDeltaFile':predfile.name})
tokens=sorted({int(block)//4 for block,lane in changes});other=[i for i in range(122) if i not in tokens]
result={'scope':'scalar replay of recorded projection differences, not a changed qualification gate',
 'weightTensor':'blk.0.attn_qkv.weight','tensorByteOffset':tensor.data_offset,'inputFiles':files,
 'modelsOutputFiles':af,'oracleOutputFiles':bf,'boundaryChanges':report,
 'maxOutputDifferenceOnOtherTokens':float(np.max(np.abs(a[other]-b[other])))}
(out/'report.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
