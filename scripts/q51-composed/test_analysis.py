import importlib.util,json,pathlib,tempfile,unittest
p=pathlib.Path(__file__).with_name('analyze.py');s=importlib.util.spec_from_file_location('finalq51',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Analysis(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.p=pathlib.Path(self.tmp.name);self.write('provenance.json',{'protocol':m.C})
  for arm in m.C['variants']:
   cohort=[self.row(str(i),0) for i in range(148)];self.records(f'cohort-{arm}.stdout',cohort);self.write(f'cohort-{arm}.execution.json',{'exitCode':0,'timedOut':False})
  for i in range(m.C['triplets']):
   self.write(f'triplet-{i}.complete.json',{'pair':i,'order':list(list(m.itertools.permutations(m.C['variants']))[i%6])})
   for arm in m.C['variants']:
    self.records(f'timing-{i}-{arm}.stdout',[{'kind':'load','nanos':100}]+[self.row('0',n) for n in range(-32,3)])
    self.write(f'timing-{i}-{arm}.execution.json',{'exitCode':0,'timedOut':False});(self.p/f'timing-{i}-{arm}.stderr').write_text('Maximum resident set size (kbytes): 1000\n')
 def row(self,id,n):return {'kind':'request','id':id,'dataset':'jevbench' if int(id)<120 else 'snips','repeat':n,'inputTokens':[1,2],'fullLogitSha256':['same']*9,'greedyTokens':[3]*8,'prediction':'A','expected':'A',**{k:100 for k in ['prefillNanos','decodeNanos','modelNanos','requestNanos','javaAllocatedBytes']}}
 def write(self,n,x):(self.p/n).write_text(json.dumps(x))
 def records(self,n,rows):(self.p/n).write_text('\n'.join(json.dumps(r) for r in rows))
 def tearDown(self):self.tmp.cleanup()
 def test_complete_fixed_protocol(self):self.assertTrue(m.analyze(self.p)['allGatesPass'])
 def test_missing_pair_rejected(self):
  (self.p/'triplet-11.complete.json').unlink()
  with self.assertRaises(FileNotFoundError):m.analyze(self.p)
 def test_quality_loss_retained(self):
  for a in ['candidate','hybrid']:
   rows=m.records(self.p/f'cohort-{a}.stdout');rows[0]['prediction']='B';self.records(f'cohort-{a}.stdout',rows)
  x=m.analyze(self.p);self.assertFalse(x['publishedQualityNonRegression']);self.assertFalse(x['allGatesPass'])
 def test_current_numeric_failure_rejected(self):
  rows=m.records(self.p/'cohort-candidate.stdout');rows[0]['fullLogitSha256'][0]='drift';self.records('cohort-candidate.stdout',rows)
  with self.assertRaisesRegex(AssertionError,'full logits'):m.analyze(self.p)
 def test_published_instability_retained(self):
  rows=[json.loads(l) for l in (self.p/'timing-1-published.stdout').read_text().splitlines()];rows[1]['fullLogitSha256'][0]='cold drift';self.records('timing-1-published.stdout',rows)
  self.assertTrue(m.analyze(self.p)['publishedColdWarmChanges'])
if __name__=='__main__':unittest.main()
