"""Independent scalar replay of recorded Q8_0 projections; no model inference.

Reads original GGUF weight bytes and recorded first-layer normalized activations.
All integer dot products use Python integers; float32 rounding is explicit.
Reports double accumulated quantized and unquantized scalar dot references.
"""
import hashlib,json,math,pathlib,struct,sys
model=pathlib.Path(sys.argv[1]);remote=pathlib.Path(sys.argv[2]);dest=pathlib.Path(sys.argv[3]);dest.mkdir(exist_ok=False)
def f32(x):return struct.unpack('<f',struct.pack('<f',x))[0]
def half(x):return struct.unpack('<e',struct.pack('<e',x))[0]
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  while chunk:=f.read(1<<20):h.update(chunk)
 return h.hexdigest()
f=model.open('rb')
def read(fmt):return struct.unpack('<'+fmt,f.read(struct.calcsize('<'+fmt)))[0]
def string():return f.read(read('Q')).decode()
formats={0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}
def value(kind):
 if kind==8:return string()
 if kind==9:
  element,count=read('I'),read('Q')
  for _ in range(count):value(element)
  return None
 return read(formats[kind])
assert f.read(4)==b'GGUF';version=read('I');assert version==3
nt,nm=read('Q'),read('Q');alignment=32
for _ in range(nm):
 name=string();v=value(read('I'))
 if name=='general.alignment':alignment=v
tensors={}
for _ in range(nt):
 name=string();dims=[read('Q') for _ in range(read('I'))];kind,offset=read('I'),read('Q')
 if name in ('blk.0.ssm_beta.weight','blk.0.ssm_alpha.weight'):tensors[name]={'shape':dims,'type':kind,'offset':offset}
data_start=(f.tell()+alignment-1)//alignment*alignment
def records(p):return [json.loads(x) for x in p.read_text().splitlines()]
def floats(p):
 b=p.read_bytes();return list(struct.unpack('<'+'f'*(len(b)//4),b))
variant=sys.argv[4] if len(sys.argv)>4 else 'baseline-512'
mf=remote/'qwen-0.8b-models/easy-intent-00';of=remote/('qwen-0.8b-'+variant)/'easy-intent-00'
mr=records(mf/'tensors.jsonl');orr=records(of/'tensors.jsonl')
def captured(folder,rows,name):
 selected=[r for r in rows if r['name']==name];result=[]
 for r in selected:
  if 'nb' in r:
   stride=4
   for dim,actual_stride in zip(r['ne'],r['nb']):
    assert actual_stride==stride,r
    stride*=dim
  result+=floats(folder/r['file'])
 return result,[{'file':str(folder/r['file']),'sha256':digest(folder/r['file'])} for r in selected]
report={'scope':'independent scalar analysis of recorded bytes, not a changed qualification gate',
 'modelFile':str(model),'modelSha256':digest(model),'oracleVariant':variant,'ggufVersion':version,'dataStart':data_start,'projections':[]}
for name,t in tensors.items():
 assert t['type']==8 and t['shape']==[1024,16],t
 f.seek(data_start+t['offset']);raw=f.read(16*32*34);weight_file=dest/(name+'.q8_0');weight_file.write_bytes(raw)
 weights=[[(struct.unpack_from('<e',raw,(row*32+b)*34)[0],
            struct.unpack_from('<32b',raw,(row*32+b)*34+2)) for b in range(32)] for row in range(16)]
 actual,actual_files=captured(mf,mr,name+'.output')
 oracle,oracle_files=captured(of,orr,'beta-0' if 'beta' in name else 'alpha-0')
 rows=[]
 for source in ('models','oracle'):
  inputs,files=captured(mf,mr,name+'.input') if source=='models' else captured(of,orr,'attn_norm-0')
  assert len(inputs)==122*1024 and len(actual)==len(oracle)==122*16
  input_file=dest/(name+'.'+source+'.input.f32');input_file.write_bytes(struct.pack('<'+'f'*len(inputs),*inputs))
  outputs=[];float_outputs=[]
  for token in range(122):
   xs=inputs[token*1024:(token+1)*1024];qs=[]
   for b in range(32):
    x=xs[b*32:(b+1)*32];maximum=max(map(abs,x));scale=f32(maximum/127)
    inverse=f32(1/scale) if source=='oracle' and scale else (f32(127/maximum) if maximum else 0)
    qs.append((half(scale),[round(f32(v*inverse)) for v in x]))
   for row in range(16):
    outputs.append(math.fsum(wd*xd*sum(w*q for w,q in zip(wq,xq)) for (wd,wq),(xd,xq) in zip(weights[row],qs)))
    float_outputs.append(math.fsum(wd*w*xs[b*32+k] for b,(wd,wq) in enumerate(weights[row]) for k,w in enumerate(wq)))
  def compare(expected,observed,start=0,end=None):
   errors=[abs(a-b) for a,b in zip(expected[start:end],observed[start:end])]
   return {'maxAbsError':max(errors),'rmse':math.sqrt(math.fsum(e*e for e in errors)/len(errors))}
  rows.append({'inputSource':source,'inputFile':input_file.name,'inputSha256':digest(input_file),'captureFiles':files,
   'quantizedScalarVsModels':compare(outputs,actual),'quantizedScalarVsOracle':compare(outputs,oracle),
   'first120TokensScalarVsOracle':compare(outputs,oracle,0,120*16),'lastTwoTokensScalarVsOracle':compare(outputs,oracle,120*16),
   'floatScalarVsModels':compare(float_outputs,actual),'floatScalarVsOracle':compare(float_outputs,oracle),
   'lastTwoTokens':[{'token':i//16,'row':i%16,'quantizedScalar':outputs[i],'floatScalar':float_outputs[i],
      'models':actual[i],'oracle':oracle[i]} for i in range(120*16,122*16)]})
 report['projections'].append({'name':name,**t,'absoluteByteOffset':data_start+t['offset'],'weightFile':weight_file.name,
   'weightSha256':digest(weight_file),'modelsOutputFiles':actual_files,'oracleOutputFiles':oracle_files,'replays':rows})
(dest/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'report':str(dest/'report.json'),'modelSha256':report['modelSha256']}))
