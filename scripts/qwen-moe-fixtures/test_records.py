import importlib.util,json,tempfile,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('fixture_run',Path(__file__).with_name('run.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Records(unittest.TestCase):
 def test_missing_request_or_snapshot_is_rejected(self):
  rows=[{'kind':'fresh-session','id':str(task),'hashes':['a','b','c']} for task in range(2) for repeat in range(3)]
  rows += [{'kind':kind} for kind,n in [('implicit-reset',2),('batch-parity',2),('complete',1)] for _ in range(n)]
  with tempfile.TemporaryDirectory() as tmp:
   p=Path(tmp)/'records'
   def write(r):p.write_text('\n'.join(json.dumps(v) for v in r))
   write(rows);m.verify_records(p)
   write(rows[1:])
   with self.assertRaises(AssertionError):m.verify_records(p)
   rows[0]['hashes'].pop();write(rows)
   with self.assertRaises(AssertionError):m.verify_records(p)
if __name__=='__main__':unittest.main()
