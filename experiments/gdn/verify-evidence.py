"""Fail closed on missing final matrices, changed archives, or an unconfirmed owned host."""
import gzip, hashlib, json, pathlib

root = pathlib.Path(__file__).resolve().parents[2]
out = root / 'benchmark-results/2026-10-11-gdn-proof'
tasks = json.loads((root / 'experiments/gdn/data/tasks.json').read_text())
ids = {t['id'] for t in tasks}
timing_ids = {t['id'] for n, t in enumerate(tasks) if n < 6 or 114 <= n < 120
    or (t['id'].startswith('snips-') and t['id'].endswith(('-0', '-1')))}
assert len(ids) == 148 and len(timing_ids) == 26
arms = {'java', 'java-tiled', 'java-tiled-stage', 'native-shared'}
for phase, expected, case_ids in [('correctness', 604, ids), ('timing', 636, timing_ids)]:
    for arch in ('x86', 'arm'):
        host = out / f'remote-cohort-{phase}-v6' / arch
        assert (host / 'STATUS').read_text().strip() == 'COMPLETE', host
        rows = [json.loads(line) for line in (host / 'cohort.stdout').read_text().splitlines()
            if line.startswith('{')]
        assert len(rows) == expected, (host, len(rows))
        assert all(r['kind'] == 'decision' and r['difference'].startswith('0:') and r['calls'] > 0
            for r in rows), host
        for arm in arms:
            warm = [r for r in rows if r['arm'] == arm and r['repeat'] < 0]
            assert len(warm) == 3, (host, arm, 'warmup')
            for repeat in range(1 if phase == 'correctness' else 6):
                selected = [r for r in rows if r['arm'] == arm and r['repeat'] == repeat]
                assert len(selected) == len(case_ids) and {r['id'] for r in selected} == case_ids, (host, arm, repeat)
        assert 'GDN_SHARED_CONTEXT providers=1 separateRecurrenceContexts=0' in (host / 'cohort.stderr').read_text()
        print('PASS complete diagnostic cohort', phase, arch, expected)
summary = json.loads((out / 'summary.json').read_text())
for arch in ('x86', 'arm'):
    assert summary['jmh']['remote-jmh-v3/' + arch]['completeKernelMatrix']
    assert summary['exclusiveKernels']['remote-jmh-v4/' + arch]['completeKernelMatrix']
print('PASS complete original and corrected kernel matrices')
for row in json.loads((out / 'large-artifact-manifest.json').read_text()):
    compressed = (out / row['archive']).read_bytes()
    assert hashlib.sha256(compressed).hexdigest() == row['archiveSha256']
    assert hashlib.sha256(gzip.decompress(compressed)).hexdigest() == row['rawSha256']
print('PASS lossless diagnostic archives')
owned = set()
unobserved_terminal = set()
final_query = json.loads((out / 'final-arm-instance-query.json').read_text())
assert final_query['response'] == {'Reservations': []}
query_args = final_query['command']
queried_absent = set(query_args[query_args.index('--instance-ids') + 1:query_args.index('--output')])
for receipt in out.glob('hosts*.json'):
    for host in json.loads(receipt.read_text())['hosts']:
        owned.add(host['instanceId'])
        receipt_path = out / 'termination-receipts' / (host['instanceId'] + '.json')
        if not receipt_path.exists():
            assert host['instanceId'] in queried_absent
            unobserved_terminal.add(host['instanceId'])
            continue
        status = json.loads(receipt_path.read_text())
        assert status['instanceId'] == host['instanceId'] and status['state'] == 'terminated'
print('PASS observed termination receipts', len(owned - unobserved_terminal))
print('LIMITATION terminal state not observed; explicit EC2 query returns no instance:', sorted(unobserved_terminal))
for label, code in [('original', 1), ('exclusive', 0)]:
    assert json.loads((out / f'actual-attention-miri-stable-{label}.json').read_text())['exitCode'] == code
assert 'Data race detected' in (out / 'actual-attention-miri-stable-original.stderr').read_text()
print('PASS actual attention ownership failure/correction recorded')
for name in ('attention-ci-pinned-miri.log', 'attention-ci-linux-miri.log'):
    assert 'test result: ok. 1 passed' in (out / name).read_text(), name
assert 'Data race detected' in (out / 'attention-ci-linux-miri-original.log').read_text()
print('PASS pinned CI memory check rejects original and accepts corrected attention')
production = json.loads((out / 'attention-production-source.json').read_text())
validation = production['repositoryValidation']
build_log = (out / validation['log']).read_bytes()
assert validation['exitCode'] == 0 and b'BUILD SUCCESSFUL' in build_log
assert hashlib.sha256(build_log).hexdigest() == validation['logSha256']
tests = json.loads((out / validation['testSummary']).read_text())['summary']
assert tests['tests'] > tests['skipped'] and tests['failures'] == tests['errors'] == 0
print('PASS committed attention source build/compliance; recorded gated skips:', tests['skipped'])
oracle=out/'local-oracle-v1'
assert (oracle/'STATUS').read_text().strip()=='COMPLETE'
results=json.loads((oracle/'results.json').read_text())
assert len(results)==2 and all(r['tokenizationExact'] and r['continuationExact'] for r in results)
assert json.loads((oracle/'termination.json').read_text())['processExited']
print('PASS two-prompt independent token oracle and owned server exit')
print('Evidence completeness verified; production qualification is NOT implied.')
