import importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('gdn',Path(__file__).with_name('run.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class GdnAnalysis(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.out=Path(self.tmp.name)
  self.rows=[{'kind':'request','id':str(i),'dataset':'jevbench' if i<74 else 'snips','inputTokens':[1,2],'fullLogitSha256':['exact']*9,'greedyTokens':[3]*8,'prediction':'A','expected':'A'} for i in range(37)]
  (self.out/'provenance.json').write_text(json.dumps({'shard':1,'caseIds':[r['id'] for r in self.rows]}))
  for arm in ('baseline','native','published'):
   self.write(arm,self.rows)
   for phase in ('cohort','routing'):(self.out/f'{phase}-{arm}.execution.json').write_text('{"exitCode":0,"timedOut":false}')
 def tearDown(self):self.tmp.cleanup()
 def write(self,arm,rows):(self.out/f'cohort-{arm}.stdout').write_text('\n'.join(json.dumps(r) for r in rows))
 def test_full_contract(self):self.assertTrue(m.analyze(self.out)['publishedQualityNonRegression'])
 def test_quality_loss_retained(self):
  self.rows[0]['prediction']='B'
  for a in ('native','baseline'):self.write(a,self.rows)
  self.assertFalse(m.analyze(self.out)['publishedQualityNonRegression'])
 def test_recurrence_drift_fails(self):
  self.rows[0]['fullLogitSha256']=['different']*9;self.write('native',self.rows)
  with self.assertRaisesRegex(AssertionError,'recurrence numerical'):m.analyze(self.out)
 def test_missing_case_fails(self):
  self.write('native',self.rows[:-1])
  with self.assertRaises(AssertionError):m.analyze(self.out)
 def test_failed_execution_rejected(self):
  (self.out/'cohort-native.execution.json').write_text('{"exitCode":1,"timedOut":false}')
  with self.assertRaisesRegex(AssertionError,'incomplete cohort'):m.analyze(self.out)
 def test_published_routing_failure_retained(self):
  (self.out/'routing-published.execution.json').write_text('{"exitCode":1,"timedOut":false}')
  self.assertFalse(m.analyze(self.out)['publishedRoutingPass'])
 def test_first_shard_state_failures_and_drift_rejected(self):
  (self.out/'provenance.json').write_text(json.dumps({'shard':0,'caseIds':[r['id'] for r in self.rows]}))
  state=[{'kind':'fresh-session','id':str(i),'hashes':['same']} for i in range(6)]+[{'kind':'implicit-reset','task':i,'hash':'same'} for i in range(2)]+[{'kind':'batch-parity','task':i,'hashes':['same']} for i in range(2)]
  for arm in ('native','baseline'):
   (self.out/f'state-{arm}.execution.json').write_text('{"exitCode":0,"timedOut":false}')
   (self.out/f'state-{arm}.stdout').write_text('\n'.join(json.dumps(r) for r in state))
  self.assertTrue(m.analyze(self.out)['publishedQualityNonRegression'])
  state[0]['hashes']=['changed']
  (self.out/'state-native.stdout').write_text('\n'.join(json.dumps(r) for r in state))
  with self.assertRaisesRegex(AssertionError,'fresh-state difference'):m.analyze(self.out)
  (self.out/'state-native.execution.json').write_text('{"exitCode":1,"timedOut":false}')
  with self.assertRaisesRegex(AssertionError,'fresh/reset/chunk'):m.analyze(self.out)
 def test_ablation_exact(self):
  source='public boolean supportsGatedDeltaNet() {\n    return gatedDeltaNetSupported;\n  }'
  self.assertIn('return false;',m.ablate_provider(source))
  with self.assertRaises(AssertionError):m.ablate_provider(source+source)
if __name__=='__main__':unittest.main()
