"""Run the exact Bonsai artifact against released products, then the pinned publisher, serially."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--payload', type=Path, required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--model', type=Path, required=True)
parser.add_argument('--reference-server', type=Path, required=True)
parser.add_argument('--reference-sha256', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--result-uri', required=True)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
shutil.copyfile(__file__, out / Path(__file__).name)
commands = []
revision = 'e8fd209c975c80aa7fb36a8423571ff3b1b7e955'
model_id = 'prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0'
model_hash = '53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def upload():
    subprocess.run(['aws', 's3', 'cp', str(out), args.result_uri, '--recursive', '--only-show-errors'], check=True, timeout=120)

def run(name, command, timeout=6000):
    row = {'name': name, 'command': command, 'startedAt': time.time()}
    commands.append(row)
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    upload()
    with (out / (name + '.log')).open('w') as log:
        result = subprocess.run(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    row.update(exitCode=result.returncode, completedAt=time.time())
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    upload()
    if result.returncode:
        raise RuntimeError(name + ' failed')

try:
    for variable in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS']:
        if '-Dmodels.' in os.environ.get(variable, ''):
            raise RuntimeError(variable + ' contains Models tuning properties')
    if args.model.stat().st_size != 5946648928 or sha(args.model) != model_hash:
        raise RuntimeError('model identity differs')
    if sha(args.reference_server) != args.reference_sha256:
        raise RuntimeError('publisher binary identity differs')
    provenance = json.loads((args.payload / 'provenance.json').read_text())
    classpath = json.loads((args.payload / 'classpath.json').read_text())
    if {r['file'] for r in classpath} != {p.name for p in (args.payload / 'lib').glob('*.jar')}:
        raise RuntimeError('classpath contains missing or unrecorded JARs')
    for row in classpath:
        if sha(args.payload / 'lib' / row['file']) != row['sha256']:
            raise RuntimeError('classpath hash mismatch: ' + row['file'])
    for name in ['provenance.json', 'classpath.json']:
        shutil.copyfile(args.payload / name, out / name)
    for name, command in [('hostname', ['hostname']), ('lscpu', ['lscpu']), ('processes-before', ['ps', 'aux'])]:
        (out / (name + '.txt')).write_bytes(subprocess.check_output(command))
    (out / 'java-version.txt').write_bytes(subprocess.check_output([args.java, '-version'], stderr=subprocess.STDOUT))
    label = 'models@' + provenance['modelsVersion'] + '+' + provenance['harnessRevision']
    java = [args.java, '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Xmx16g', '-cp', str(args.payload / 'lib/*')]
    common = ['com.integrallis.models.rag.RagBenchmarkCli', '--framework', 'plain-java', '--model-id', model_id,
        '--workload', 'general', '--prompt-template', 'chatml-no-think', '--top-k', '1', '--max-tokens', '64', '--context', '2048', '--threads', '16']
    candidate = ['--backend', 'rust-ffm', '--backend-version', label, '--model', str(args.model)]
    smoke_dir = out / 'default-correctness'
    smoke_dir.mkdir()
    smoke_path = smoke_dir / 'models-rust-ffm.json'
    (out / 'STATUS').write_text('RUNNING_RELEASED_DEFAULT_SMOKE\n')
    run('default-smoke', java + common + candidate + ['--warmups', '0', '--iterations', '1', '--output', str(smoke_path)], 2400)
    smoke = json.loads(smoke_path.read_text())
    summary = smoke['summary']
    if not (summary['totalAttempts'] == summary['successfulAttempts'] == 9 and summary['correctAnswerRate'] == 1
            and summary['abstentionAccuracy'] == 1 and not smoke['failures']
            and smoke['backendDiagnostics']['environment']['native-quantized-decode'] == 'true'
            and smoke['settings']['generationControls']['promptCache'] == 'longest-common-prefix'):
        raise RuntimeError('released default smoke failed')
    (smoke_dir / 'configuration.json').write_text(json.dumps({'configuration': 'library-defaults',
        'tuningSystemProperties': [], 'backendVersion': label, 'reportSha256': sha(smoke_path)}, indent=2) + '\n')
    (out / 'STATUS').write_text('RUNNING_RELEASED_PAIRED_RAG\n')
    trials = ['--warmups', '1', '--iterations', '3']
    candidate_path = out / 'models-rag.json'
    reference_path = out / 'reference-rag.json'
    run('models-rag', java + common + candidate + trials + ['--output', str(candidate_path)])
    server_command = [str(args.reference_server), '-m', str(args.model), '-c', '2048', '-t', '16', '-tb', '16',
        '-ngl', '0', '--parallel', '1', '--host', '127.0.0.1', '--port', '8080', '--no-warmup']
    (out / 'reference.json').write_text(json.dumps({'repository': 'https://github.com/PrismML-Eng/llama.cpp',
        'revision': revision, 'binarySha256': args.reference_sha256, 'command': server_command}, indent=2) + '\n')
    with (out / 'reference-server.log').open('w') as log:
        server = subprocess.Popen(server_command, stdout=log, stderr=subprocess.STDOUT)
        try:
            for attempt in range(180):
                if server.poll() is not None:
                    raise RuntimeError('publisher server exited')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    time.sleep(1)
            else:
                raise RuntimeError('publisher startup timeout')
            run('reference-rag', java + common + trials + ['--backend', 'llama.cpp', '--backend-version',
                'PrismML-Eng/llama.cpp@' + revision, '--model', 'bonsai2', '--artifact', str(args.model),
                '--endpoint', 'http://127.0.0.1:8080', '--pid', str(server.pid), '--output', str(reference_path)])
        finally:
            server.terminate()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
    # Re-assess after importing the unchanged raw reports into their repository-relative paths.
    run('qualification', java + ['com.integrallis.models.rag.RagQualificationCli', '--candidate', str(candidate_path),
        '--comparator', str(reference_path), '--publisher-reference', 'prism-bonsai2-ptq1_0', '--require-qualified',
        '--output', str(out / 'verdict.json')], 60)
    (out / 'STATUS').write_text('RELEASED_BONSAI_QUALIFICATION_COMPLETE\n')
except BaseException as error:
    (out / 'STATUS').write_text('FAILED ' + repr(error) + '\n')
    raise
finally:
    upload()
