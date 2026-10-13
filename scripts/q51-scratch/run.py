"""Isolate the two Q5_1 scratch edits without reviving historical launchers."""
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tarfile
import time
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / os.environ.get('Q51_PROTOCOL','protocol.json')).read_text())


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def run(out, name, command, timeout=900):
    start = time.monotonic()
    capped = False
    with (out / (name + '.stdout')).open('w') as stdout, (out / (name + '.stderr')).open('w') as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            capped = True
            os.killpg(process.pid, signal.SIGTERM)
            try:
                code = process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                code = process.wait()
    save(out / (name + '.execution.json'), {'command':command, 'exitCode':code,
         'wallSeconds':time.monotonic()-start, 'timedOut':capped, 'timeoutSeconds':timeout})
    if code or capped:
        raise RuntimeError(f'{name} failed: {code}, timeout={capped}')


def prepare_reference(out):
    url = os.environ['Q51_SCRATCH_REFERENCE_URL']
    payload = out / 'reference.tar.gz'
    urllib.request.urlretrieve(url, payload)
    assert sha(payload) == CONFIG['payloadSha256']
    runtime = out / 'runtime'
    runtime.mkdir()
    with tarfile.open(payload) as archive:
        metadata=[m.name for m in archive.getmembers() if any(part.startswith('._') or part=='__MACOSX' for part in Path(m.name).parts)]
        archive.extractall(runtime,members=[m for m in archive.getmembers() if m.name not in metadata],filter='data')
    save(out/'excluded-archive-metadata.json',{'appleDoubleSidecars':metadata})
    assert sha(runtime/'prior-hybrid.jar') == CONFIG['priorJarSha256']
    assert sha(runtime/'candidate.jar') == CONFIG['oldFullJarSha256']
    core = runtime/'lib/vectors-core-0.1.30.jar'
    assert sha(core) == CONFIG['vectorsSha256']
    with zipfile.ZipFile(runtime/'prior-hybrid.jar') as prior, zipfile.ZipFile(runtime/'candidate.jar') as full:
        old_delta = [name for name in sorted(set(prior.namelist()) | set(full.namelist()))
                     if name.endswith('.class') and (name not in prior.namelist() or name not in full.namelist() or prior.read(name) != full.read(name))]
    assert old_delta == ['com/integrallis/models/backend/purejava/ops/TensorOps.class']
    run(out, 'rust-build', ['cargo','build','--locked','--release','--manifest-path',str(runtime/'rust/Cargo.toml')],600)
    sources = out/'oracle-source'; sources.mkdir()
    with tarfile.open(runtime/'oracle-source.tar') as archive:
        archive.extractall(sources, filter='data')
    oracle = out/'oracle-build'
    run(out,'oracle-configure',['cmake','-S',str(sources),'-B',str(oracle),'-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=OFF','-DGGML_NATIVE=OFF','-DGGML_CUDA=OFF','-DGGML_METAL=OFF','-DLLAMA_CURL=OFF','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_SERVER=OFF'])
    run(out,'oracle-build',['cmake','--build',str(oracle),'--target','llama-quantize','-j','4'])
    source = out/'source-q50.gguf'
    urllib.request.urlretrieve('https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q5_0.gguf',source)
    assert sha(source)=='925946ab27801fea3eead42933f857b6449c6d299f062e374cee2ac48426f2a5'
    model=out/'causal-q51.gguf'
    run(out,'convert-q51',[str(oracle/'bin/llama-quantize'),'--allow-requantize','--pure','--output-tensor-type','q5_1','--token-embedding-type','q5_1',str(source),str(model),'Q5_1','8'])
    assert sha(model)==CONFIG['derivedFixtureSha256']
    return runtime, old_delta


def prepare(out, models):
    runtime, old_delta = prepare_reference(out)
    shared_classes=out/'shared-width-classes'
    shared_sources=[]
    for repair in CONFIG.get('sharedKernelRepairs',[]):
        source_file=out/Path(repair['path']).name
        source_file.write_bytes(subprocess.check_output(['git','-C',str(models),'show',repair['revision']+':'+repair['path']]))
        assert sha(source_file)==repair['sha256']
        shared_sources.append(str(source_file))
    if shared_sources:
        shared_classes.mkdir()
        run(out,'compile-shared-width-repairs',['javac','--release','25','-g:none','--add-modules','jdk.incubator.vector','-cp',f'{runtime}/prior-hybrid.jar:{runtime}/lib/*','-d',str(shared_classes),*shared_sources])
        allowed={'PinnedReduction.class','Q5_1Dequantizer.class','Q5_1Dequantizer$Narrow.class'}
        assert {p.name for p in shared_classes.rglob('*.class')}==allowed
    variants=[]
    for variant in CONFIG['variants']:
        directory=out/variant['name'];directory.mkdir()
        src=subprocess.check_output(['git','-C',str(models),'show',variant['revision']+':backend-java/src/main/java/com/integrallis/models/backend/purejava/ops/TensorOps.java'])
        source_file=directory/'TensorOps.java';source_file.write_bytes(src)
        assert sha(source_file)==variant['sourceSha256']
        classes=directory/'classes';classes.mkdir()
        run(out,'compile-'+variant['name'],['javac','--release','25','-g:none','-parameters','--add-modules','jdk.incubator.vector','-cp',f'{runtime}/prior-hybrid.jar:{runtime}/lib/*','-d',str(classes),str(source_file)])
        compiled=list(classes.rglob('*.class'))
        assert compiled and all(p.name.startswith('TensorOps') for p in compiled)
        if shared_sources:
            shutil.copytree(shared_classes,classes,dirs_exist_ok=True)
            compiled=list(classes.rglob('*.class'))
        compiled_hashes={str(p.relative_to(classes)):sha(p) for p in compiled}
        target=directory/'backend-java.jar'
        with zipfile.ZipFile(runtime/'prior-hybrid.jar') as old, zipfile.ZipFile(target,'w') as new:
            for entry in old.infolist():
                replacement=classes/entry.filename
                new.writestr(entry,replacement.read_bytes() if replacement.is_file() else old.read(entry))
            for replacement in compiled:
                name=str(replacement.relative_to(classes))
                if name not in old.namelist():new.write(replacement,name)
        variants.append({**variant,'jarSha256':sha(target),'classHashes':compiled_hashes})
    for variant in variants[1:]:
        assert variant['classHashes'].keys()==variants[0]['classHashes'].keys()
        delta=[name for name in variant['classHashes'] if variant['classHashes'][name]!=variants[0]['classHashes'][name]]
        assert delta==['com/integrallis/models/backend/purejava/ops/TensorOps.class']
    save(out/'provenance.json',{'protocol':CONFIG,'variants':variants,'oldPayloadClassDelta':old_delta,
         'java':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),
         'host':subprocess.check_output(['lscpu'],text=True),'affinity':sorted(os.sched_getaffinity(0)),
         'memory':Path('/proc/meminfo').read_text(),'runtimeFiles':{str(p.relative_to(runtime)):sha(p) for p in sorted((runtime/'lib').glob('*.jar'))}})
    (out/'PREPARED').write_text('complete\n')


def records(out, name):
    return [json.loads(line) for line in (out/(name+'.stdout')).read_text().splitlines() if line.startswith('{')]


def execute(out, phase, pair, diagnostic_variant=None):
    assert (out/'PREPARED').is_file()
    runtime=out/'runtime'
    native=runtime/'rust/target/release/libjmodels_kernels.so'
    def command(variant, mode, value):
        return ['/usr/bin/time','-v','java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g',f'-Dmodels.native.kernels.threads={CONFIG["threads"]}',f'-Dvectors.gguf.threads={CONFIG["threads"]}', '-cp',f'{out}/{variant}/backend-java.jar:{runtime}/probe.jar:{runtime}/lib/*','experiments.q5.Q51CausalProof',mode,str(out/'causal-q51.gguf'),str(native),str(runtime/'tasks.json'),str(value),'native']
    variants=[v['name'] for v in CONFIG['variants']]
    if phase=='diagnostic-window':
        assert diagnostic_variant in variants
        variant=diagnostic_variant
        cmd=command(variant,'timing',0)
        cmd[3:3]=[f'-XX:StartFlightRecording=filename={out}/window-{variant}.jfr,settings=profile,duration=90s,dumponexit=true', '-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation',f'-XX:LogFile={out}/window-{variant}.xml']
        start=time.monotonic()
        with (out/f'window-{variant}.stdout').open('w') as stdout, (out/f'window-{variant}.stderr').open('w') as stderr:
            process=subprocess.Popen(cmd,stdout=stdout,stderr=stderr,start_new_session=True)
            capped=False
            try:code=process.wait(timeout=180)
            except subprocess.TimeoutExpired:
                capped=True
                os.killpg(process.pid,signal.SIGTERM)
                try:code=process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);code=process.wait()
        save(out/f'window-{variant}.execution.json',{'command':cmd,'exitCode':code,'wallSeconds':time.monotonic()-start,'boundedDiagnosticWindow':True,'windowSeconds':180,'windowReached':capped,'qualificationMeasurement':False,'fullNumericalCohortExecuted':False})
        assert (out/f'window-{variant}.jfr').stat().st_size>0,'recording was not retained'
        return
    if phase=='correctness':
        baseline=None
        for variant in variants:
            run(out,'routing-'+variant,command(variant,'routing',str(platform.machine()=='x86_64').lower()))
            name='cohort-'+variant
            run(out,name,command(variant,'cohort',0),1800)
            actual={r['id']:(r['inputTokens'],r['fullLogitSha256'],r['greedyTokens']) for r in records(out,name) if r['kind']=='request'}
            assert len(actual)==148 and all(len(v[1])==9 for v in actual.values())
            if baseline is None:baseline=actual
            else:assert actual==baseline,'numerical contract changed'
        (out/'CORRECT').write_text('148 cases,9 full vectors,all variants exact\n')
    elif phase in ('diagnostic','diagnostic-only'):
        if phase=='diagnostic':assert (out/'CORRECT').is_file()
        diagnostic_signatures=[]
        for variant in variants:
            cmd=command(variant,'timing',0)
            cmd[3:3]=[f'-XX:StartFlightRecording=filename={out}/diagnostic-{variant}.jfr,settings=profile,dumponexit=true', '-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation',f'-XX:LogFile={out}/diagnostic-{variant}.xml']
            run(out,'diagnostic-'+variant,cmd,CONFIG['perProcessSeconds'])
            observed=[r for r in records(out,'diagnostic-'+variant) if r['kind']=='request']
            assert sorted(r['repeat'] for r in observed)==list(range(-32,3))
            diagnostic_signatures.append([(r['id'],r['repeat'],r['inputTokens'],r['fullLogitSha256'],r['greedyTokens']) for r in observed])
        assert all(value==diagnostic_signatures[0] for value in diagnostic_signatures)
        save(out/'diagnostic-scope.json',{'scope':'JFR/compiler instrumentation only; all measured durations and allocation totals excluded from qualification','full148CaseGateExecutedHere':phase=='diagnostic','instrumentedSnapshotsExactAcrossVariants':True})
    else:
        assert (out/'CORRECT').is_file()
        order=list(itertools.permutations(variants))[pair%6]
        for variant in order:
            name=f'timing-{pair}-{variant}'
            assert not (out/(name+'.execution.json')).exists(),'do not replace observations'
            run(out,name,command(variant,'timing',pair),CONFIG['perProcessSeconds'])
        save(out/f'triplet-{pair}.complete.json',{'pair':pair,'order':order})


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('phase',choices=['prepare','correctness','timing','diagnostic','diagnostic-only','diagnostic-window'])
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--models',type=Path)
    parser.add_argument('--pair',type=int,choices=range(CONFIG['triplets']))
    parser.add_argument('--diagnostic-variant',choices=[v['name'] for v in CONFIG['variants']])
    args=parser.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=True)
    if args.phase=='prepare':prepare(out,args.models.resolve())
    else:execute(out,args.phase,args.pair,args.diagnostic_variant)


if __name__=='__main__':main()
