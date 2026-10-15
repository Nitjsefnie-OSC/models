"""Frozen ARM fallback/native route comparison; this does not grant RAG qualification."""
import json
import os
from pathlib import Path
import subprocess
import time

out = Path('/work/out')
commands = []
def upload():
    subprocess.run(['aws', 's3', 'cp', str(out), os.environ['RESULT_URI'], '--recursive', '--only-show-errors'], check=True, timeout=120)
def run(name, command, timeout=1800):
    row = {'name': name, 'command': command, 'startedAt': time.time()}
    commands.append(row)
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    with (out / (name + '.log')).open('w') as log:
        result = subprocess.run(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    row.update(exitCode=result.returncode, completedAt=time.time())
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    upload()
    if result.returncode:
        raise RuntimeError(name + ' failed')

java = ['/opt/jdk/bin/java', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED',
    '-Xmx16g', '-cp']
model = '/work/bonsai2.gguf'
def classpath(arm):
    return '/work/arm/vector/lib/*:/work/arm/classes'
def library(arm):
    return '/work/arm/' + ('libjmodels_kernels-neon.so' if arm == 'neon' else 'libjmodels_kernels.so')
def jvm(arm):
    return java[:-1] + ['-Dmodels.native.kernels.library=' + library(arm), '-cp']
try:
    for arm in ['native', 'neon']:
        run(arm + '-projections', jvm(arm) + [classpath(arm), 'PtqJavaQ8Probe', model,
            library(arm), str(out / (arm + '-projections.json')), '16'])
    for arm in ['vector', 'native', 'neon']:
        backend = 'rust-ffm' if arm != 'vector' else 'pure-java'
        run(arm + '-oracle', jvm(arm) + [classpath(arm), 'BonsaiContinuationProbe', model, backend, str(out / (arm + '-oracle.json'))])
        run(arm + '-inference', jvm(arm) + [classpath(arm), 'com.integrallis.models.bench.InferenceBenchmarkCli',
            '--backend', backend, '--backend-version', 'models-source@' + ('42185aa7' if arm == 'neon' else '75ec53de') + ';arm-' + arm,
            '--model-id', 'prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0', '--model', model,
            '--prompt', 'The quick brown fox', '--max-tokens', '8', '--warmups', '1', '--iterations', '3',
            '--context', '512', '--threads', '16', '--output', str(out / (arm + '-inference.json'))])
    reports = {arm: json.loads((out / (arm + '-inference.json')).read_text()) for arm in ['vector', 'native', 'neon']}
    hashes = lambda r: [t['outputSha256'] for t in r['trials']]
    exact = hashes(reports['vector']) == hashes(reports['native']) == hashes(reports['neon'])
    complete = all(r['summary']['successfulTrials'] == 3 for r in reports.values())
    result = {'complete': complete, 'outputHashesMatch': exact, 'retentionRule': 'complete; same outputs; both median rates improve by at least 5%', 'comparisons': {}}
    for baseline, candidate in [('native', 'neon'), ('vector', 'neon')]:
        ratios = {key: reports[candidate]['summary'][key] / reports[baseline]['summary'][key]
            for key in ['p50DecodeTokensPerSecond', 'p50PrefillTokensPerSecond']}
        result['comparisons'][candidate + 'Over' + baseline.title()] = {'ratios': ratios,
            'retain': complete and exact and all(value >= 1.05 for value in ratios.values())}
    (out / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    (out / 'STATUS').write_text('ARM_NEON_COMPLETE\n')
except BaseException as error:
    (out / 'STATUS').write_text('FAILED ' + repr(error) + '\n')
    raise
finally:
    upload()
