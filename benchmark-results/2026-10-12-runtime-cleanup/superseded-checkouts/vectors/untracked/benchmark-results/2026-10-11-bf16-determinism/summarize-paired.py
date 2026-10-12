#!/usr/bin/env python3
"""Summarize retained ABBA JMH files without dropping forks or iterations."""
import argparse, json, pathlib, statistics
parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
a = parser.parse_args()
groups = {}
for index, arm in enumerate(['baseline', 'fixed', 'fixed', 'baseline']):
    source = a.directory / 'paired' / f'{index}-{arm}.json'
    records = json.loads(source.read_text())
    if len(records) != 6:
        raise ValueError(f'{source}: expected six benchmark/shape records')
    for record in records:
        key = (record['benchmark'].rsplit('.', 1)[1], record['params']['shape'])
        groups.setdefault(key, {}).setdefault(arm, []).append(record)
results = []
for (kernel, shape), arms in groups.items():
    row = {'kernel': kernel, 'shape': shape}
    for arm in ['baseline', 'fixed']:
        records = arms[arm]
        if len(records) != 2:
            raise ValueError(f'{kernel}/{shape}/{arm}: expected two independent forks')
        row[arm + 'Microseconds'] = statistics.mean(r['primaryMetric']['score'] for r in records)
        row[arm + 'ForkMicroseconds'] = [r['primaryMetric']['score'] for r in records]
        row[arm + 'BytesPerCall'] = statistics.mean(r['secondaryMetrics']['gc.alloc.rate.norm']['score'] for r in records)
    row['latencyRatio'] = row['fixedMicroseconds'] / row['baselineMicroseconds']
    results.append(row)
summary = {'latencyInvestigationThreshold': 1.05, 'results': results,
           'requiresLatencyInvestigation': any(r['latencyRatio'] > 1.05 for r in results)}
(a.directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
for r in results:
    print(f"{r['kernel']} {r['shape']}: {r['latencyRatio']:.4f}x latency")
