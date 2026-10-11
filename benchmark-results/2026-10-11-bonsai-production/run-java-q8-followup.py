"""Compare the frozen scalar and vector Java Q8 implementations on one otherwise idle host."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

out = Path('/work/java-q8-followup')
uri = 's3://models-qual-077051030817/results/bonsai-java-q8-20261011/vector-31132e2c/'
# Initial scalar arm is owned by cloud-init; never overlap inference processes.
for attempt in range(600):
    status = Path('/work/out/STATUS').read_text().strip()
    if status == 'JAVA_Q8_SCALAR_BASELINE_COMPLETE':
        break
    if 'FAILED' in status:
        raise RuntimeError(status)
    if attempt % 12 == 0:
        print('Waiting for the scalar baseline to finish', flush=True)
    time.sleep(3)
else:
    raise TimeoutError('scalar baseline deadline')
out.mkdir(exist_ok=True)
shutil.copy2(__file__, out / 'run-java-q8-followup.py')
commands = []

def upload():
    subprocess.run(['aws', 's3', 'cp', str(out), uri, '--recursive', '--only-show-errors'], check=True, timeout=60)

def run(name, command, timeout=1200):
    row = {'name': name, 'command': command, 'startedAt': time.time()}
    commands.append(row)
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    with (out / (name + '.log')).open('w') as log:
        completed = subprocess.run(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    row.update(exitCode=completed.returncode, completedAt=time.time())
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    upload()
    if completed.returncode:
        raise RuntimeError(name + ' failed')

try:
    jar = Path('/home/ubuntu/backend-java-31132e2c.jar')
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == '17afdc79482af05d04046435159284bfd0e2a363f9ba21aeeaee2880d9fa059c'
    lib = Path('/work/vector/lib')
    shutil.copytree('/work/qa/lib', lib)
    (lib / 'backend-java-0.3.58.jar').unlink()
    shutil.copy2(jar, lib / jar.name)
    (out / 'input-hashes.json').write_text(json.dumps({str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(lib.glob('*.jar'))}, indent=2) + '\n')
    scalar_classpath = '/work/qa/lib/*:/home/ubuntu/bonsai-java-q8-probe'
    vector_classpath = '/work/vector/lib/*:/home/ubuntu/bonsai-java-q8-probe'
    java = ['/opt/jdk/bin/java', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Xmx16g', '-cp']
    probe = ['PtqJavaQ8Probe', '/work/bonsai2.gguf', '/work/qa/libjmodels_kernels.so']
    run('scalar-projections', java + [scalar_classpath] + probe + [str(out / 'scalar-projections.json'), '16'])
    run('vector-projections', java + [vector_classpath] + probe + [str(out / 'vector-projections.json'), '16'])
    command = json.loads(Path('/work/out/benchmark-command.json').read_text())
    command[command.index('-cp') + 1] = '/work/vector/lib/*'
    command[command.index('--backend-version') + 1] = 'models-source@31132e2c;java-q8-vector;backend-java-only'
    command[command.index('--output') + 1] = str(out / 'java-q8-vector.json')
    run('vector-inference', command, 1200)
    baseline = json.loads(Path('/work/out/java-q8-scalar.json').read_text())
    candidate = json.loads((out / 'java-q8-vector.json').read_text())
    metric = lambda report, name: report['summary'][name]
    decode = metric(candidate, 'p50DecodeTokensPerSecond') / metric(baseline, 'p50DecodeTokensPerSecond')
    prefill = metric(candidate, 'p50PrefillTokensPerSecond') / metric(baseline, 'p50PrefillTokensPerSecond')
    hashes = lambda report: [trial['outputSha256'] for trial in report['trials']]
    same_outputs = hashes(candidate) == hashes(baseline)
    complete = all(r['summary']['successfulTrials'] == 3 for r in [candidate, baseline])
    result = {'baselineSource': '8b5c0b29', 'candidateBackendJavaSource': '31132e2c', 'contract': 'PTQ1_0 x Q8_0; exact Java/native output check in projection probes', 'decodeRatio': decode, 'prefillRatio': prefill, 'outputHashesMatch': same_outputs, 'complete': complete, 'retentionRule': 'all three trials succeed; output hashes match; both median rates improve by at least 5%', 'retainVectorCandidate': complete and same_outputs and decode >= 1.05 and prefill >= 1.05}
    (out / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    (out / 'STATUS').write_text('JAVA_Q8_PAIRED_COMPLETE\n')
except BaseException as error:
    (out / 'STATUS').write_text('FAILED ' + repr(error) + '\n')
    raise
finally:
    upload()
    subprocess.run(['shutdown', '-h', 'now'], check=True)
