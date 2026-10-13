import importlib.util,pathlib,unittest,copy
P=pathlib.Path(__file__).parent
s=importlib.util.spec_from_file_location('aggregate',P/'aggregate.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Shards(unittest.TestCase):
 def test_exact_original_coverage_and_boundaries(self):
  parts=m.P['shardCaseIds'];expected=[x for part in parts for x in part]
  self.assertEqual([37]*4,list(map(len,parts)));m.coverage(parts,expected)
  for kind in ['missing','duplicate','reordered','substituted']:
   bad=copy.deepcopy(parts)
   if kind=='missing':bad[0].pop()
   elif kind=='duplicate':bad[1][0]=bad[0][0]
   elif kind=='reordered':bad[2][0],bad[2][1]=bad[2][1],bad[2][0]
   else:bad[3][-1]='unfrozen-case'
   with self.subTest(kind=kind),self.assertRaises(AssertionError):m.coverage(bad,expected)
 def test_nine_full_snapshots_required(self):
  ids=['a'];row={'kind':'request','id':'a','inputTokens':[1], 'fullLogitSha256':['x']*9,'greedyTokens':[2]*8,'prediction':'A'}
  m.run.signatures([row],ids)
  row['fullLogitSha256'].pop()
  with self.assertRaises(AssertionError):m.run.signatures([row],ids)
if __name__=='__main__':unittest.main()
