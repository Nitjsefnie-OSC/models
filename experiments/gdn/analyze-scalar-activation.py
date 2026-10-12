"""Descriptive fixed five-pair diagnostic; not a replacement production gate."""
import json,math,pathlib,re,statistics,sys
p=pathlib.Path(sys.argv[1]);output=pathlib.Path(sys.argv[2]);pairs=[];metrics=['loadNanos','coldRequestNanos','coldAllocatedBytes','javaAllocatedBytes','requestNanos','peakRssKiB']
for pair in range(5):
 arms={}
 for arm in ['native','scalar']:
  stem=p/f'pair{pair}-{arm}';receipt=pathlib.Path(str(stem)+'.execution.json')
  if not receipt.exists():continue
  assert json.loads(receipt.read_text())['exitCode']==0
  records=[json.loads(l)for l in pathlib.Path(str(stem)+'.stdout').read_text().splitlines()if l.startswith('{')];r=[x for x in records if x.get('kind')=='request'];assert len(r)==35
  arms[arm]={'loadNanos':next(x['nanos']for x in records if x.get('kind')=='load'),'coldRequestNanos':r[0]['requestNanos'],'coldAllocatedBytes':r[0]['javaAllocatedBytes'],'javaAllocatedBytes':statistics.mean(x['javaAllocatedBytes']for x in r[-3:]),'requestNanos':statistics.mean(x['requestNanos']for x in r[-3:]),'peakRssKiB':int(re.search(r'Maximum resident set size \(kbytes\):\s*(\d+)',pathlib.Path(str(stem)+'.stderr').read_text())[1])}
 if len(arms)==2:pairs.append({'pair':pair,'arms':arms,'scalarOverFinalVector':{k:arms['scalar'][k]/arms['native'][k]for k in metrics}})
summary={k:{'geometricMean':math.exp(statistics.mean(math.log(p['scalarOverFinalVector'][k])for p in pairs)),'min':min(p['scalarOverFinalVector'][k]for p in pairs),'max':max(p['scalarOverFinalVector'][k]for p in pairs)}for k in metrics}if pairs else{}
correctness=json.loads((p/'correctness.json').read_text())if(p/'correctness.json').exists()else None
output.write_text(json.dumps({'scope':'Descriptive five-pair ARM0.8B diagnostic only, all pairs retained. Does not replace original ten-pair product qualification or 4B/other-architecture coverage.','complete':len(pairs)==5 and(p/'STATUS').read_text().strip()=='COMPLETE','correctness':correctness,'summary':summary,'pairs':pairs},indent=2)+'\n')
print(output)
