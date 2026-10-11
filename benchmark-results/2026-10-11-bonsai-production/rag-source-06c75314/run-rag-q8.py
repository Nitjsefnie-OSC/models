"""Isolated profiling and supporting comparison; does not grant qualification."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

out = Path('/work/rag-q8-out')
out.mkdir(exist_ok=True)
import shutil
shutil.copy2(__file__, out / 'run-rag-q8.py')
model = '/work/bonsai2.gguf'
model_id = 'prism_ml_ternary_bonsai_2_27b_gguf_ptq1_0'
revision = 'e8fd209c975c80aa7fb36a8423571ff3b1b7e955'
commands = []
java = ['/opt/jdk/bin/java', '-Dmodels.native.kernels.library=/work/candidate-src/backend-native/src/main/rust/model-kernels/target/release/libjmodels_kernels.so', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Xmx16g', '-cp', '/work/q8/lib/*']

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

infer = ['com.integrallis.models.rag.RagBenchmarkCli', '--framework', 'plain-java', '--model-id', model_id, '--workload', 'general', '--prompt-template', 'chatml-no-think', '--top-k', '1', '--max-tokens', '64', '--warmups', '1', '--iterations', '3', '--context', '2048', '--threads', '16']
run('models-rag', java + infer + ['--backend', 'rust-ffm', '--backend-version', 'models-source@06c75314', '--model', model, '--output', str(out / 'models-rag.json')], 6000)

server_path = '/work/prism/build/bin/llama-server'
server_command = [server_path, '-m', model, '-c', '2048', '-t', '16', '-tb', '16', '-ngl', '0', '--parallel', '1', '--host', '127.0.0.1', '--port', '8080', '--no-warmup']
(out / 'reference.json').write_text(json.dumps({'repository': 'https://github.com/PrismML-Eng/llama.cpp', 'revision': revision, 'command': server_command, 'binarySha256': hashlib.sha256(Path(server_path).read_bytes()).hexdigest(), 'role': 'publisher llama.cpp fork; exact artifact publisher reference; production-rag-publisher-reference-v1'}, indent=2) + '\n')
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
        run('reference-rag', java + infer + ['--backend', 'llama.cpp', '--backend-version', 'PrismML-Eng/llama.cpp@' + revision, '--model', 'bonsai2', '--artifact', model, '--endpoint', 'http://127.0.0.1:8080', '--pid', str(server.pid), '--output', str(out / 'reference-rag.json')], 6000)
    finally:
        server.terminate()
        try:
            server.wait(timeout=30)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
(out / 'STATUS').write_text('PAIRED_RAG_COMPLETE\n')
subprocess.run(['aws', 's3', 'cp', str(out), os.environ['RESULT_URI'], '--recursive', '--only-show-errors'], check=True)
