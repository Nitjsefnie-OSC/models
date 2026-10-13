"""Read only GGUF metadata/tensor headers; do not map or evaluate model weights."""
import collections,struct

def inspect(path):
 with path.open('rb') as f:
  def unpack(fmt):return struct.unpack('<'+fmt,f.read(struct.calcsize('<'+fmt)))[0]
  def string():return f.read(unpack('Q')).decode('utf-8')
  def skip(kind):
   widths={0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8}
   if kind in widths:f.seek(widths[kind],1)
   elif kind==8:f.seek(unpack('Q'),1)
   elif kind==9:
    element,count=unpack('I'),unpack('Q')
    if element in widths:f.seek(widths[element]*count,1)
    else:
     for _ in range(count):skip(element)
   else:raise ValueError('unknown GGUF metadata type')
  assert f.read(4)==b'GGUF';version=unpack('I');assert version in (2,3)
  count,metadata=unpack('Q'),unpack('Q');architecture=None
  for _ in range(metadata):
   key=string();kind=unpack('I')
   if key=='general.architecture':assert kind==8;architecture=string()
   else:skip(kind)
  tensors=[]
  for _ in range(count):
   name=string();dims=[unpack('Q') for _ in range(unpack('I'))];kind=unpack('I');offset=unpack('Q')
   tensors.append({'name':name,'dimensions':dims,'type':kind,'offset':offset})
  assert architecture=='qwen2' and count==291
  types=dict(collections.Counter(t['type'] for t in tensors));assert types=={0:121,7:170},types
  return {'architecture':architecture,'tensorCount':count,'types':types,'tensors':tensors,'excludedTypes':['BF16','PTQ'],'scope':'Exact checksum-bound fixture has no BF16 or PTQ tensors.'}
