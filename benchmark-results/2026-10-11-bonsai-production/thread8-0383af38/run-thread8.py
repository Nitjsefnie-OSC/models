"""Isolated profiling and supporting comparison; does not grant qualification."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

out = Path('/work/thread8-out')
out.mkdir(exist_ok=True)
model = '/work/bonsai2.gguf'
model_id = 'prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0'
revision = 'e8fd209c975c80aa7fb36a8423571ff3b1b7e955'
commands = []
java = ['taskset', '-c', '0-7', '/opt/jdk/bin/java', '-XX:ActiveProcessorCount=8', '-Dmodels.native.kernels.library=/work/candidate-src/backend-native/src/main/rust/model-kernels/target/release/libjmodels_kernels.so', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Xmx16g', '-cp', '/work/batch/lib/*']

def run(name, command, timeout=5400):
    row = {'name': name, 'command': command, 'startedAt': time.time()}
    commands.append(row)
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    with (out / (name + '.log')).open('w') as log:
        result = subprocess.run(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    row.update(exitCode=result.returncode, completedAt=time.time())
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    subprocess.run(['aws', 's3', 'cp', str(out), os.environ['RESULT_URI'], '--recursive', '--only-show-errors'], check=True)
    if result.returncode:
        raise RuntimeError(name + ' failed')

run('projection-abba', java + ['-cp', '/work/batch/lib/*:/work/candidate-src/benchmark-results/2026-10-11-bonsai-production', 'PtqProjectionBenchmark', model, '/work/candidate-src/backend-native/src/main/rust/model-kernels/target/release/libjmodels_kernels.so', str(out / 'projection-abba.json')], 1200)

infer = ['com.integrallis.models.bench.InferenceBenchmarkCli', '--model-id', model_id, '--prompt', 'The quick brown fox', '--max-tokens', '16', '--warmups', '1', '--iterations', '3', '--context', '512', '--threads', '8']
run('models-inference', java + infer + ['--backend', 'rust-ffm', '--backend-version', 'models-source@0383af38', '--model', model, '--output', str(out / 'models-inference.json')], 2400)


server_path = '/work/prism/build/bin/llama-server'
server_command = ['taskset', '-c', '0-7', server_path, '-m', model, '-c', '512', '-t', '8', '-tb', '8', '-ngl', '0', '--parallel', '1', '--host', '127.0.0.1', '--port', '8080', '--no-warmup']
(out / 'reference.json').write_text(json.dumps({'repository': 'https://github.com/PrismML-Eng/llama.cpp', 'revision': revision, 'command': server_command, 'binarySha256': hashlib.sha256(Path(server_path).read_bytes()).hexdigest(), 'role': 'publisher llama.cpp fork; supporting reference, not Ollama'}, indent=2) + '\n')
with (out / 'reference-server.log').open('w') as log:
    server = subprocess.Popen(server_command, stdout=log, stderr=subprocess.STDOUT)
    try:
        for attempt in range(180):
            if server.poll() is not None:
                raise RuntimeError('reference server exited')
            try:
                with urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(1)
        else:
            raise RuntimeError('reference server startup timed out')
        run('reference-inference', java + infer + ['--backend', 'llama.cpp', '--backend-version', 'PrismML-Eng/llama.cpp@' + revision, '--model', 'bonsai2', '--artifact', model, '--endpoint', 'http://127.0.0.1:8080', '--pid', str(server.pid), '--output', str(out / 'reference-inference.json')], 2400)
    finally:
        server.terminate()
        try:
            server.wait(timeout=30)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
(out / 'STATUS').write_text('BASELINE_COMPLETE\n')
subprocess.run(['aws', 's3', 'cp', str(out), os.environ['RESULT_URI'], '--recursive', '--only-show-errors'], check=True)
