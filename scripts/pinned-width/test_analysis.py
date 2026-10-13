import importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('analysis',Path(__file__).with_name('analyze.py'));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class AnalysisTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  (self.root/'provenance.json').write_text(json.dumps({'protocol':a.P}))
  for kernel in ('dot','decoder'):
   for pair in range(a.P['pairs']):
    (self.root/f'{kernel}-{pair}.complete.json').write_text(json.dumps({'pair':pair,'kernel':kernel,'numericalExact':True}))
    for arm in ('baseline','candidate'):
     prefix=self.root/f'{kernel}-{pair}-{arm}'
     prefix.with_suffix('.execution.json').write_text(json.dumps({'exitCode':0}))
     rows=[]
     for size in a.P['shapes']:
      rows.extend({'kind':'measure','size':size,'block':n,'calls':1000,'nanos':10000 if arm=='baseline' else 12000,'bytes':0,'checksum':52} for n in range(5))
      rows.append({'kind':'output','size':size,'sha256':'fixed','checksum':52})
     prefix.with_suffix('.stdout').write_text('\n'.join(json.dumps(r) for r in rows))
 def tearDown(self):self.temp.cleanup()
 def test_regression_is_retained(self):
  result=a.analyze(self.root)
  self.assertFalse(result['independentKernels']['dot'][896]['nanos']['gate'])
  self.assertTrue(result['independentKernels']['dot'][896]['bytes']['gate'])
 def test_missing_pair_rejected(self):
  (self.root/'dot-0.complete.json').unlink()
  with self.assertRaises(FileNotFoundError):a.analyze(self.root)
 def test_numeric_change_rejected(self):
  p=self.root/'dot-0-candidate.stdout';p.write_text(p.read_text().replace('"checksum": 52','"checksum": 53',1))
  with self.assertRaises(AssertionError):a.analyze(self.root)
if __name__=='__main__':unittest.main()
