"""Merge complete paired cases; preserve interrupted source reports and exclude their unpaired cases."""
import hashlib
import json
from pathlib import Path
import sys

case_file, fleet_file, shard_root, output_root = map(Path, sys.argv[1:])
case_hash = hashlib.sha256(case_file.read_bytes()).hexdigest()
fleet = json.loads(fleet_file.read_text())
all_cases = json.loads(case_file.read_text())['cases']
token_oracle = {r['index']: r for r in map(json.loads, case_file.with_name('public-qa-reference-tokens.jsonl').read_text().splitlines())}
merged = {arm: [] for arm in ['models', 'publisher']}
provenance = {arm: [] for arm in merged}
covered = set()
for shard in fleet['shards']:
    directory = shard_root / f"shard-{shard['shard']}"
    interrupted_count = {0: 10, 2: 16}.get(shard['shard'])
    status = (directory / 'STATUS').read_text().strip()
    if interrupted_count is None:
        assert status == 'PAIRED_PUBLIC_QA_COMPLETE', f'incomplete shard {directory}'
    else:
        interruptions = json.loads(fleet_file.with_name('interrupted-instances.json').read_text())
        instance = next(i for r in interruptions['Reservations'] for i in r['Instances']
                        if i['InstanceId'] == shard['instanceId'])
        assert instance['State']['Name'] == 'terminated'
        assert instance['StateReason']['Code'] == 'Server.SpotInstanceTermination'
        assert status == 'RUNNING_PAIRED_PUBLIC_QA', 'unexpected interruption status'
    selected_count = interrupted_count if interrupted_count is not None else shard['count']
    assert json.loads((directory / 'token-preflight-passed.json').read_text())['allTokenIdsMatch'] is True
    hostname = (directory / 'hostname.txt').read_text().strip()
    assert hostname == shard['privateDnsName'].split('.')[0], f'host identity differs for {directory}'
    indices = set(range(shard['first'], shard['first'] + selected_count))
    assert not covered.intersection(indices), 'overlapping shards'
    covered.update(indices)
    for arm in merged:
        source = directory / f'{arm}.jsonl'
        raw = source.read_bytes()
        header, *rows = [json.loads(line) for line in raw.splitlines() if line]
        assert header['caseSha256'] == case_hash and header['context'] == 2048, 'case manifest or context differs'
        assert header['tokenizationMode'] == 'trusted-frozen-chatml-v2', 'invalid prompt tokenization harness'
        assert header['generation'] == {'temperature': 0, 'topK': 1, 'topP': 1, 'seed': 42,
            'repetitionPenalty': 1, 'rawPrompt': True, 'promptCache': False}, 'generation controls differ'
        assert header['first'] == shard['first'] and header['count'] == shard['count'], 'shard interval differs'
        source_count = len(rows)
        expected_source_count = selected_count if interrupted_count is not None and arm == 'publisher' else shard['count']
        assert source_count == expected_source_count
        assert {r['index'] for r in rows} == set(range(shard['first'], shard['first'] + expected_source_count)), 'missing/duplicate source rows'
        excluded_indices = [r['index'] for r in rows if r['index'] not in indices]
        rows = [r for r in rows if r['index'] in indices]
        assert len(rows) == selected_count and {r['index'] for r in rows} == indices, 'missing/duplicate paired case rows'
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
            assert row.get('error') or row['inputTokenIds'] == token_oracle[row['index']]['inputTokenIds'], 'prompt token IDs differ'
        merged[arm].extend(rows)
        provenance[arm].append({'shard': shard['shard'], 'instanceId': shard['instanceId'], 'hostname': hostname,
            'source': str(source), 'sourceStatus': status, 'sourceCount': source_count,
            'excludedUnpairedIndices': excluded_indices, 'selectedCount': selected_count, 'sha256': hashlib.sha256(raw).hexdigest(), 'manifest': header})
assert covered == set(range(len(all_cases))), 'incomplete case coverage'
output_root.mkdir(parents=True, exist_ok=False)
for arm, rows in merged.items():
    header = {'type': 'manifest', 'caseSha256': case_hash, 'count': len(rows), 'context': 2048,
        'aggregation': 'answer quality only; both arms paired within each host; interrupted unpaired cases rerun on replacement hosts', 'shards': provenance[arm]}
    with (output_root / f'{arm}.jsonl').open('x') as out:
        for row in [header] + sorted(rows, key=lambda r:r['index']):
            out.write(json.dumps(row, ensure_ascii=False) + '\n')
print(f'Merged all {len(all_cases)} cases from {len(fleet["shards"])} complete paired hosts')
