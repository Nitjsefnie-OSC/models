"""Resume the publisher arm after repairing SSE comment handling; preserve the first failure."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

out = Path('/work/rag-q8-out/reference-sse-fixed')
out.mkdir(exist_ok=False)
shutil.copyfile(__file__, out / Path(__file__).name)
uri = 's3://models-qual-077051030817/results/bonsai-production-20261011T191541Z/rag-source-06c75314/'
lib = Path('/work/rag-fixed/lib')
shutil.copytree('/work/q8/lib', lib)
for jar in lib.glob('models-rag-bench-*.jar'):
    jar.unlink()
jar = Path('/home/ubuntu/models-rag-bench-sse-fixed.jar')
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '0907a36d591f48ccfaa7e88c0e8967356b0296cfb2652f978137c18b367cf88c'
shutil.copyfile(jar, lib / jar.name)
(out / 'classpath.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(lib.glob('*.jar'))}, indent=2) + '\n')
reference = json.loads(Path('/work/rag-q8-out/reference.json').read_text())
reference['measurementHarnessRevision'] = 'be81aa2b'
reference['change'] = 'Ignore SSE comments and metadata; rendering, sampling, scoring and timed generation are unchanged'
(out / 'reference.json').write_text(json.dumps(reference, indent=2) + '\n')
commands = json.loads(Path('/work/rag-q8-out/commands.json').read_text())
command = next(row['command'] for row in commands if row['name'] == 'reference-rag')
command[command.index('-cp') + 1] = str(lib / '*')
command[command.index('--output') + 1] = str(out / 'reference-rag.json')
server = None
try:
    with (out / 'reference-server.log').open('w') as server_log:
        server = subprocess.Popen(reference['command'], stdout=server_log, stderr=subprocess.STDOUT)
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
            raise RuntimeError('reference startup timeout')
        command[command.index('--pid') + 1] = str(server.pid)
        (out / 'command.json').write_text(json.dumps({'command': command, 'startedAt': time.time()}, indent=2) + '\n')
        with (out / 'reference-rag.log').open('w') as log:
            subprocess.run(['/usr/bin/time', '-v', *command], stdout=log, stderr=subprocess.STDOUT, check=True, timeout=6000)
    (out / 'STATUS').write_text('PAIRED_RAG_COMPLETE\n')
except BaseException as error:
    (out / 'STATUS').write_text('FAILED ' + repr(error) + '\n')
    raise
finally:
    if server is not None:
        server.terminate()
        try:
            server.wait(timeout=30)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
    subprocess.run(['aws', 's3', 'cp', '/work/rag-q8-out', uri, '--recursive', '--only-show-errors'], check=True)
    subprocess.run(['shutdown', '-h', 'now'], check=True)
