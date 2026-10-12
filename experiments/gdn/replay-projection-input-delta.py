"""Predict first-layer FFN projection differences from recorded input quantization.

For every prompt token, select the output row with the largest observed difference
and independently decode its actual Q4_K/Q5_K weight bytes. Compute the difference
of scalar dequantized dot products, without executing either inference engine.
"""
import hashlib,json,math,pathlib,struct,sys
import numpy as np
sys.path.insert(0,sys.argv[1]);from gguf import GGUFReader
model=pathlib.Path(sys.argv[2]);root=pathlib.Path(sys.argv[3]);variant=sys.argv[4];out=pathlib.Path(sys.argv[5]);out.mkdir(exist_ok=False)
reader=GGUFReader(str(model),'r');weights={t.name:t for t in reader.tensors}
mf=root/'qwen-0.8b-models/easy-intent-00';of=root/('qwen-0.8b-'+variant)/'easy-intent-00'
def records(p):return [json.loads(x) for x in p.read_text().splitlines()]
def capture(folder,name):
 rows=[r for r in records(folder/'tensors.jsonl') if r['name']==name]
 return np.concatenate([np.fromfile(folder/r['file'],dtype='<f4') for r in rows]),[
  {'file':str(folder/r['file']),'sha256':hashlib.sha256((folder/r['file']).read_bytes()).hexdigest()} for r in rows]
def quantize(x):
 blocks=x.reshape(-1,256);maximum=blocks[np.arange(len(blocks)),np.argmax(np.abs(blocks),axis=1)]
 inv=np.zeros_like(maximum);np.divide(np.float32(-127),maximum,out=inv,where=maximum!=0)
 q=np.rint(np.float32(blocks*inv[:,None])).astype(np.int32)
 scale=np.zeros_like(inv);np.divide(np.float32(1),inv,out=scale,where=inv!=0)
 return q,scale
results=[]
with model.open('rb') as f:
 for short,oracle_output in [('ffn_gate','ffn_gate-0'),('ffn_up','ffn_up-0')]:
  name='blk.0.'+short+'.weight';t=weights[name];kind=t.tensor_type.name;assert kind in ['Q4_K','Q5_K'],kind
  cols,rows=map(int,t.shape);block_size=144 if kind=='Q4_K' else 176;blocks_per_row=cols//256
  mi,mif=capture(mf,name+'.input');oi,oif=capture(of,'attn_post_norm-0');assert mi.shape==oi.shape
  mq,ms=quantize(mi);oq,os=quantize(oi);delta=(oq.astype(np.float64)*os[:,None]-mq.astype(np.float64)*ms[:,None]).reshape(-1,cols)
  mo,mof=capture(mf,name+'.output');oo,oof=capture(of,oracle_output);mo=mo.reshape(-1,rows);oo=oo.reshape(-1,rows)
  entries=[];saved={}
  for token in range(len(mo)):
   row=int(np.argmax(np.abs(oo[token]-mo[token])));f.seek(t.data_offset+row*blocks_per_row*block_size)
   raw=f.read(blocks_per_row*block_size);coefficients=[]
   for b in range(blocks_per_row):
    data=raw[b*block_size:(b+1)*block_size];d,dmin=struct.unpack_from('<ee',data,0);packed=data[4:16]
    for lane in range(256):
     group,l=divmod(lane,32);code=data[(16 if kind=='Q4_K' else 48)+(group//2)*32+l]
     q=(code>>(4 if group%2 else 0))&15
     if kind=='Q5_K':q+=((data[16+l]>>group)&1)<<4
     if group<4:sc=packed[group]&63;minimum=packed[group+4]&63
     else:sc=(packed[group+4]&15)|((packed[group-4]>>6)<<4);minimum=(packed[group+4]>>4)|((packed[group]>>6)<<4)
     coefficients.append(d*sc*q-dmin*minimum)
   predicted=math.fsum(w*x for w,x in zip(coefficients,delta[token]));observed=float(oo[token,row])-float(mo[token,row])
   assert math.isfinite(predicted)
   if row not in saved:
    file=out/(short+'-row-'+str(row)+'.bin');file.write_bytes(raw);saved[row]={'file':file.name,'sha256':hashlib.sha256(raw).hexdigest()}
   entries.append({'token':token,'row':row,'observedDelta':observed,'predictedDelta':predicted,'residual':observed-predicted})
  results.append({'weight':name,'ggufType':kind,'absoluteByteOffset':t.data_offset,'rows':rows,'cols':cols,
   'modelsInputFiles':mif,'oracleInputFiles':oif,'modelsOutputFiles':mof,'oracleOutputFiles':oof,
   'differentQuantizedIntegers':int(np.count_nonzero(mq!=oq)),'maxObservedDifference':float(np.max(np.abs(mo-oo))),
   'maxResidualAfterScalarInputDelta':max(abs(e['residual']) for e in entries),'weightRows':saved,'samples':entries})
(out/'report.json').write_text(json.dumps({'scope':'diagnostic scalar projection delta; no qualification gate changed','oracleVariant':variant,'projections':results},indent=2)+'\n')
print(json.dumps([{k:r[k] for k in ['weight','differentQuantizedIntegers','maxObservedDifference','maxResidualAfterScalarInputDelta']} for r in results],indent=2))
