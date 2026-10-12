"""Freeze public prompts without inspecting candidate results or selecting on outcomes."""
import hashlib, json, pathlib, subprocess, urllib.request

root=pathlib.Path(__file__).resolve().parents[2]
out=root/'experiments/gdn/data';out.mkdir(exist_ok=True)
jev=root.parent.parent/'references/openjev/gh-jevbench'
tasks=[];sources=[]
for name in ['easy','original']:
    path=jev/f'datasets/public/{name}.jsonl'
    raw=path.read_bytes();(out/f'jevbench-{name}.jsonl').write_bytes(raw)
    sources.append({'dataset':'jevbench','file':name,'revision':subprocess.check_output(['git','-C',str(jev),'rev-parse','HEAD'],text=True).strip(),'sha256':hashlib.sha256(raw).hexdigest()})
    for line in raw.decode().splitlines():
        row=json.loads(line);labels=row['labels'];q=row['question'];criteria=q.get('criteria',{})
        if q['type']=='noul':criteria={'no':criteria.get('false','No'),'yes':criteria.get('true','Yes')}
        elif q['type']=='score':criteria=dict(zip(labels,criteria))
        else:criteria={k:(v or k) for k,v in criteria.items()}
        criteria={k:v for k,v in criteria.items() if v!=k}
        prompt=row['state'] if isinstance(row['state'],str) else json.dumps(row['state'],ensure_ascii=False)
        if criteria:prompt+='\nOptions:'+''.join('\n- '+label+': '+criteria.get(label,label) for label in labels)
        prompt+='\n'+q['instructions']+'\n'+''.join(chr(65+n)+': '+label+'\n' for n,label in enumerate(labels))+'Answer:'
        expected=row['expected']
        if isinstance(expected,bool):expected='yes' if expected else 'no'
        tasks.append({'dataset':'jevbench','id':row['id'],'prompt':prompt,'labels':labels,'expected':str(expected)})
revision='b86ac7f1577868c42158d0dec77db50956046696'
labels=['AddToPlaylist','BookRestaurant','GetWeather','PlayMusic','RateBook','SearchCreativeWork','SearchScreeningEvent']
for label in labels:
    url=f'https://raw.githubusercontent.com/sonos/nlu-benchmark/{revision}/2017-06-custom-intent-engines/{label}/validate_{label}.json'
    raw=urllib.request.urlopen(url).read();(out/f'snips-{label}.json').write_bytes(raw)
    sources.append({'dataset':'snips','url':url,'revision':revision,'sha256':hashlib.sha256(raw).hexdigest()})
    rows=json.loads(raw.decode('utf-8-sig'))[label]
    # First four source-ordered validation rows per intent, selected before measurement.
    for n,row in enumerate(rows[:4]):
        text=''.join(part['text'] for part in row['data'])
        prompt=text+'\nChoose the intent of this request.\n'+''.join(chr(65+j)+': '+v+'\n' for j,v in enumerate(labels))+'Answer:'
        tasks.append({'dataset':'snips','id':f'snips-{label}-{n}','prompt':prompt,'labels':labels,'expected':label})
assert sum(t['dataset']=='jevbench' for t in tasks)==120
(out/'tasks.json').write_text(json.dumps(tasks,ensure_ascii=False,indent=2)+'\n')
(out/'sources.json').write_text(json.dumps(sources,indent=2)+'\n')
(out/'README.md').write_text('''# Public datasets

JevBench: https://github.com/fstandhartinger/jevbench — retained easy/original public cohort,
120 cases. Prompts follow the current shipped evidence/rubric/instruction/options layout.
This is a current-source reproduction; it does not assert that every historical runtime detail
is identical. Immutable source revisions and hashes are in sources.json.

SNIPS: https://github.com/sonos/nlu-benchmark — first four validation examples per intent in
source order, 28 total, all seven labels retained. This is an independent regression cohort,
not the full SNIPS benchmark or an estimate of state-of-the-art accuracy.
Citation: Coucke A. et al., "Snips Voice Platform: an embedded Spoken Language Understanding
system for private-by-design voice interfaces." 2018. Original source files are retained.

Select timing samples prospectively: first and last six JevBench cases and first two per SNIPS
intent. No case is excluded from correctness because of performance or its answer.
''')
print(len(tasks),'tasks')
