"""Composed same-host route selection; historical failures remain separate."""
import importlib.util
import itertools
import json
import math
import os
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
    cold=next(r for r in rows if r['repeat']==-32)
    return {'coldRequestNanos':cold['requestNanos'],'coldAllocatedBytes':cold['javaAllocatedBytes'],'loadNanos':loads[0]['nanos'],'peakRssKb':int(rss.group(1)),**{k:statistics.mean(r[k] for r in warm) for k in ['prefillNanos','decodeNanos','modelNanos','requestNanos','javaAllocatedBytes']}},rows


def analyze(root):
    provenance=json.loads((root/'route-provenance.json').read_text());assert provenance['protocol']==CONFIG
    variants=CONFIG['variants']
    cohort=[]
    for arm in variants:
        execution=json.loads((root/f'cohort-{arm}.execution.json').read_text());assert execution['exitCode']==0 and not execution['timedOut']
        records=[json.loads(l) for l in (root/f'cohort-{arm}.stdout').read_text().splitlines() if l.startswith('{')]
        requests=[r for r in records if r['kind']=='request'];assert len(requests)==148
        values={r['id']:(r['inputTokens'],r['fullLogitSha256'],r['greedyTokens'],r['prediction']) for r in requests};assert len(values)==148
        assert all(len(v[1])==9 for v in values.values());cohort.append(values)
    assert all(v==cohort[0] for v in cohort),'full cohort numerical difference'
    contrasts=[('hybrid','native'),('hybrid','java'),('java','native')]
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
            'notPublishedReleaseComparison':True}


if __name__=='__main__':
    try:result=analyze(Path(sys.argv[1]))
    except Exception as error:
        result={'complete':False,'error':f'{type(error).__name__}: {error}'}
        Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+'\n');raise
    Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+'\n')
