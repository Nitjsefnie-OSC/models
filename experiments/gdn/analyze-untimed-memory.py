"""Attribute read-only cohort RSS snapshots; not a paired performance measurement."""
import collections,json,pathlib,re,sys
output=pathlib.Path(sys.argv[1]);reports=[]
for arg in sys.argv[2:]:
 p=pathlib.Path(arg);heap=(p/'heap-info.txt').read_text();bounds=re.search(r'\[(0x[0-9a-f]+), (0x[0-9a-f]+)\)',heap);assert bounds
 low,high=(int(x,16)for x in bounds.groups());totals=collections.defaultdict(collections.Counter);category=None
 for line in (p/'smaps').read_text().splitlines():
  match=re.match(r'([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+\S+\s*(.*)',line)
  if match:
   address=int(match[1],16);name=match[4]
   category='Java heap' if low<=address<high else 'GGUF file' if '.gguf' in name else 'Other file' if name.startswith('/') else 'Other anonymous'
  elif category:
   match=re.match(r'(Rss|Pss|Private_Dirty|Size):\s+(\d+)',line)
   if match:totals[category][match[1]]+=int(match[2])
 reports.append({'capture':str(p),'metadata':json.loads((p/'capture.json').read_text()),'heapInfo':heap,'kilobytes':totals})
output.write_text(json.dumps({'scope':'Untimed correctness JVM snapshots at different requests on separate same-type hosts. Descriptive memory attribution only; not a paired RSS gate, not evidence of retention lifetime or a leak. GC.heap_info requested without explicit GC.','captures':reports},indent=2)+'\n')
print(output)
