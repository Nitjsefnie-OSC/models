"""Assemble a measurement payload, replacing every product JAR with Central bytes."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=False)
lib = out / 'lib'
lib.mkdir()
records = []
sources = list((root / 'models-rag-bench/build/install/models-rag-bench/lib').glob('*.jar'))
sources.append(root / 'models-bench/build/libs/models-bench-0.3.58.jar')
for source in sorted(sources):
    name = source.name
    target = lib / name
    row = {'file': name}
    if name.startswith(('models-', 'backend-', 'vectors-')) and not name.startswith(('models-bench-', 'models-rag-bench-')):
        artifact, version = name[:-4].rsplit('-', 1)
        expected = '0.1.29' if artifact.startswith('vectors-') else '0.3.58'
        assert version == expected, name
        url = f'https://repo.maven.apache.org/maven2/com/integrallis/{artifact}/{version}/{name}'
        raw = urllib.request.urlopen(url, timeout=120).read()
        sha1 = urllib.request.urlopen(url + '.sha1', timeout=120).read().decode().strip().split()[0]
        assert hashlib.sha1(raw).hexdigest() == sha1, name
        target.write_bytes(raw)
        row.update(origin='Maven Central', url=url, centralSha1=sha1)
    else:
        shutil.copyfile(source, target)
        row.update(origin='source-built measurement harness' if name.startswith('models-') else 'resolved external dependency', source=str(source))
    row.update(sha256=hashlib.sha256(target.read_bytes()).hexdigest(), sizeBytes=target.stat().st_size)
    records.append(row)
    print(name, flush=True)
(out / 'classpath.json').write_text(json.dumps(records, indent=2) + '\n')
shutil.copyfile(Path(__file__).with_name('run-baseline.py'), out / 'run-baseline.py')
with tarfile.open(str(out) + '.tar.gz', 'w:gz') as archive:
    archive.add(out, arcname='payload')
print(hashlib.sha256(Path(str(out) + '.tar.gz').read_bytes()).hexdigest())
