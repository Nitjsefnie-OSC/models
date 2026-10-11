"""Merge complete paired shards only, retaining host identities and every source-report digest."""
import hashlib
import json
from pathlib import Path
import sys

case_file, fleet_file, shard_root, output_root = map(Path, sys.argv[1:])
case_hash = hashlib.sha256(case_file.read_bytes()).hexdigest()
fleet = json.loads(fleet_file.read_text())
all_cases = json.loads(case_file.read_text())['cases']
merged = {arm: [] for arm in ['models', 'publisher']}
provenance = {arm: [] for arm in merged}
covered = set()
for shard in fleet['shards']:
    directory = shard_root / f"shard-{shard['shard']}"
    assert (directory / 'STATUS').read_text().strip() == 'PAIRED_PUBLIC_QA_COMPLETE', f'incomplete shard {directory}'
    hostname = (directory / 'hostname.txt').read_text().strip()
    assert hostname == shard['privateDnsName'].split('.')[0], f'host identity differs for {directory}'
    indices = set(range(shard['first'], shard['first'] + shard['count']))
    assert not covered.intersection(indices), 'overlapping shards'
    covered.update(indices)
    for arm in merged:
        source = directory / f'{arm}.jsonl'
        raw = source.read_bytes()
        header, *rows = [json.loads(line) for line in raw.splitlines() if line]
        assert header['caseSha256'] == case_hash and header['context'] == 2048, 'case manifest or context differs'
        assert header['generation'] == {'temperature': 0, 'topK': 1, 'topP': 1, 'seed': 42,
            'repetitionPenalty': 1, 'rawPrompt': True, 'promptCache': False}, 'generation controls differ'
        assert header['first'] == shard['first'] and header['count'] == shard['count'], 'shard interval differs'
        assert len(rows) == shard['count'] and {r['index'] for r in rows} == indices, 'missing/duplicate case rows'
        if arm == 'models':
            assert header['runtimeIdentity'] == 'models-source@' + fleet['runtimeRevision'], 'runtime source differs'
            assert header['processors'] == 16 and header['backend'] == 'rust-ffm', 'candidate execution differs'
            assert header['artifactSizeBytes'] == 5946648928, 'artifact size differs'
            assert header['diagnostics']['environment']['native-quantized-decode'] == 'true', 'native route was inactive'
            assert header['diagnostics']['environment']['ptq-activation-contract'] == 'q8_0-f16-scales-nearest-even', 'numeric contract differs'
        else:
            assert header['backendVersion'] == 'PrismML-Eng/llama.cpp@e8fd209c975c80aa7fb36a8423571ff3b1b7e955', 'reference revision differs'
            assert header['endpoint'] == 'http://127.0.0.1:8080', 'comparator was not local'
        for row in rows:
            case = all_cases[row['index']]
            assert row['id'] == case['id'] and row['dataset'] == case['dataset'] and row['promptSha256'] == case['promptSha256'], 'case identity differs'
        merged[arm].extend(rows)
        provenance[arm].append({'shard': shard['shard'], 'instanceId': shard['instanceId'], 'hostname': hostname,
            'source': str(source), 'sha256': hashlib.sha256(raw).hexdigest(), 'manifest': header})
assert covered == set(range(len(all_cases))), 'incomplete case coverage'
output_root.mkdir(parents=True, exist_ok=False)
for arm, rows in merged.items():
    header = {'type': 'manifest', 'caseSha256': case_hash, 'count': len(rows), 'context': 2048,
        'aggregation': 'answer quality only; both arms paired within each host', 'shards': provenance[arm]}
    with (output_root / f'{arm}.jsonl').open('x') as out:
        for row in [header] + sorted(rows, key=lambda r:r['index']):
            out.write(json.dumps(row, ensure_ascii=False) + '\n')
print(f'Merged all {len(all_cases)} cases from {len(fleet["shards"])} complete paired hosts')
