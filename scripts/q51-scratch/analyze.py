"""Same-host code-form diagnosis; original published comparison remains separate."""
import importlib.util
import itertools
import json
import math
from pathlib import Path
import re
import statistics
import sys

HERE=Path(__file__).resolve().parent
CONFIG=json.loads((HERE/'protocol.json').read_text())
spec=importlib.util.spec_from_file_location('paired',HERE.parent/'bonsai-paired/analyze.py')
paired=importlib.util.module_from_spec(spec);spec.loader.exec_module(paired)
paired.CONFIG=CONFIG


def read(root,pair,arm):
    prefix=root/f'timing-{pair}-{arm}'
    assert json.loads(prefix.with_suffix('.execution.json').read_text())['exitCode']==0
    records=[json.loads(line) for line in prefix.with_suffix('.stdout').read_text().splitlines() if line.startswith('{')]
    rows=[r for r in records if r['kind']=='request']
    assert sorted(r['repeat'] for r in rows)==list(range(-32,3))
    assert all(len(r['fullLogitSha256'])==9 and len(r['greedyTokens'])==8 for r in rows)
    loads=[r for r in records if r['kind']=='load'];assert len(loads)==1
    rss=re.search(r'Maximum resident set size \(kbytes\):\s*(\d+)',prefix.with_suffix('.stderr').read_text());assert rss
    warm=[r for r in rows if r['repeat']>=0]
    return {'loadNanos':loads[0]['nanos'],'peakRssKb':int(rss.group(1)),**{k:statistics.mean(r[k] for r in warm) for k in ['prefillNanos','decodeNanos','modelNanos','javaAllocatedBytes']}},rows


def analyze(root):
    provenance=json.loads((root/'provenance.json').read_text());assert provenance['protocol']==CONFIG
    assert (root/'CORRECT').is_file()
    variants=[r['name'] for r in CONFIG['variants']]
    contrasts=[('row-reuse','prior'),('full','row-reuse'),('full','prior')]
    logs={f'{a}/{b}':{k:[] for k in CONFIG['limits']} for a,b in contrasts}
    history={}
    for pair in range(CONFIG['triplets']):
        receipt=json.loads((root/f'triplet-{pair}.complete.json').read_text())
        assert receipt=={'pair':pair,'order':list(list(itertools.permutations(variants))[pair%6])}
        values={};rows={}
        for variant in variants:values[variant],rows[variant]=read(root,pair,variant)
        for group in zip(*(rows[v] for v in variants)):
            identity=[(r['id'],r['repeat'],r['inputTokens'],r['fullLogitSha256'],r['greedyTokens'],r['prediction']) for r in group]
            assert identity.count(identity[0])==len(identity),'variant numerical difference'
            row=group[0];signature=(row['fullLogitSha256'],row['greedyTokens'],row['prediction'])
            assert history.setdefault(row['id'],signature)==signature,'cold/warm/process numerical difference'
        for a,b in contrasts:
            for metric in CONFIG['limits']:logs[f'{a}/{b}'][metric].append(math.log(values[a][metric]/values[b][metric]))
    return {'scope':CONFIG['scope'],'triplets':CONFIG['triplets'],'numericalExact':True,
            'contrasts':{name:{metric:paired.bootstrap([samples],CONFIG['limits'][metric]) for metric,samples in metrics.items()} for name,metrics in logs.items()},
            'notFinalReleaseQualification':True}


if __name__=='__main__':
    try:result=analyze(Path(sys.argv[1]))
    except Exception as error:
        result={'complete':False,'error':f'{type(error).__name__}: {error}'}
        Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+'\n');raise
    Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+'\n')
