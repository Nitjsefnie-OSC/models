import importlib.util,struct,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('scope',Path(__file__).with_name('fixture_scope.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def string(v):
 b=v.encode();return struct.pack('<Q',len(b))+b
class FixtureScope(unittest.TestCase):
 def file(self,path,last=7):
  data=b'GGUF'+struct.pack('<IQQ',3,291,2)
  data+=string('general.architecture')+struct.pack('<I',8)+string('qwen2')
  data+=string('test.array')+struct.pack('<IIQiii',9,5,3,1,2,3)
  for i in range(291):data+=string(str(i))+struct.pack('<IQQIQ',2,896,128,0 if i<121 else (last if i==290 else 7),0)
  path.write_bytes(data)
 def test_expected_tensor_inventory(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'test.gguf';self.file(p);self.assertEqual(m.inspect(p)['types'],{0:121,7:170})
 def test_bf16_invalidates_scope(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'test.gguf';self.file(p,30)
   with self.assertRaises(AssertionError):m.inspect(p)
if __name__=='__main__':unittest.main()
