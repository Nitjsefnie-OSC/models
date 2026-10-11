"""Bounded paired public-QA shard; each arm runs alone and checkpoint uploads persist failures."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

root = Path('/work/qa')
out = Path('/work/out')
out.mkdir(exist_ok=True)
first, count = int(os.environ['FIRST_CASE']), int(os.environ['CASE_COUNT'])
result_uri = os.environ['RESULT_URI']
commands = []

def upload():
    subprocess.run(['aws', 's3', 'cp', str(out), result_uri, '--recursive', '--only-show-errors'], check=True, timeout=60)

def run(name, command):
    row = {'name': name, 'command': command, 'startedAt': time.time()}
    commands.append(row)
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    with (out / (name + '.log')).open('w') as log:
        process = subprocess.Popen(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    upload()
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
    row.update(exitCode=process.returncode, completedAt=time.time())
    (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    upload()
    if process.returncode:
        raise RuntimeError(name + ' failed')

java = ['/opt/jdk/bin/java', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Xmx16g',
        '-Dmodels.native.kernels.library=/work/qa/libjmodels_kernels.so', '-cp', '/work/qa/lib/*:/work/qa/classes']
model = '/work/bonsai2.gguf'
cases = str(root / 'public-qa-cases.json')
identity = (root / 'source-revision.txt').read_text().strip()
try:
    run('models', java + ['PublicQaProbe', model, cases, str(out / 'models.jsonl'), 'rust-ffm', '2048', str(first), str(count), 'models-source@' + identity])
    server_command = ['/work/prism/build/bin/llama-server', '-m', model, '-c', '2048', '-t', '16', '-tb', '16', '-ngl', '0', '--parallel', '1', '--host', '127.0.0.1', '--port', '8080', '--no-warmup']
    (out / 'reference-command.json').write_text(json.dumps(server_command, indent=2) + '\n')
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
                raise RuntimeError('reference startup timed out')
            run('publisher', ['python3', str(root / 'run-public-reference.py'), cases, str(out / 'publisher.jsonl'), 'http://127.0.0.1:8080', str(first), str(count)])
        finally:
            server.terminate()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
    (out / 'STATUS').write_text('PAIRED_PUBLIC_QA_COMPLETE\n')
except BaseException as error:
    (out / 'STATUS').write_text('FAILED ' + repr(error) + '\n')
    raise
finally:
    upload()
