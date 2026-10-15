"""Assemble released product bytes with the explicitly source-built measurement harness."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--models-version', required=True)
parser.add_argument('--vectors-version', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
lib = out / 'lib'
lib.mkdir()
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
records = []
sources = sorted((root / 'models-rag-bench/build/install/models-rag-bench/lib').glob('*.jar'))
if not sources:
    raise RuntimeError('Build :models-rag-bench:installDist first')
for source in sources:
    name = source.name
    target = lib / name
    row = {'file': name}
    if name.startswith(('models-', 'backend-', 'vectors-')) and not name.startswith('models-rag-bench-'):
        artifact, version = name[:-4].rsplit('-', 1)
        expected = args.vectors_version if artifact.startswith('vectors-') else args.models_version
        if version != expected:
            raise RuntimeError(f'Stale distribution entry {name}; expected {expected}')
        url = f'https://repo.maven.apache.org/maven2/com/integrallis/{artifact}/{version}/{name}'
        raw = urllib.request.urlopen(url, timeout=120).read()
        sha1 = urllib.request.urlopen(url + '.sha1', timeout=120).read().decode().strip().split()[0]
        if hashlib.sha1(raw).hexdigest() != sha1:
            raise RuntimeError(f'Central checksum mismatch: {name}')
        target.write_bytes(raw)
        row.update(origin='Maven Central', url=url, centralSha1=sha1)
    else:
        shutil.copyfile(source, target)
        harness = name.startswith('models-rag-bench-')
        row.update(origin='source-built measurement harness' if harness else 'resolved external dependency')
        if harness:
            row['sourceRevision'] = revision
    row.update(sha256=hashlib.sha256(target.read_bytes()).hexdigest(), sizeBytes=target.stat().st_size)
    records.append(row)
    print(name, flush=True)
with zipfile.ZipFile(lib / f'backend-native-{args.models_version}.jar') as native:
    for resource in ['native.properties', 'libjmodels_kernels.so']:
        native.getinfo('META-INF/models/native/linux-x86_64/' + resource)
(out / 'classpath.json').write_text(json.dumps(records, indent=2) + '\n')
(out / 'provenance.json').write_text(json.dumps({
    'modelsVersion': args.models_version, 'vectorsVersion': args.vectors_version,
    'harnessRevision': revision,
    'harnessWorkingTree': subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True),
    'productSource': 'Downloaded Maven Central JARs; no locally built product JARs',
}, indent=2) + '\n')
shutil.copyfile(__file__, out / Path(__file__).name)
with tarfile.open(str(out) + '.tar.gz', 'w:gz') as archive:
    archive.add(out, arcname='released')
print(hashlib.sha256(Path(str(out) + '.tar.gz').read_bytes()).hexdigest())
