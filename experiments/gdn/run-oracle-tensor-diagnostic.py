"""Bounded independent tensor captures; no correctness gate is changed by this diagnostic."""
import hashlib,json,os,pathlib,shutil,subprocess,time
out=pathlib.Path('/work/out');config=json.loads(pathlib.Path('diagnostic.json').read_text());commands=[]
def sync(): subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
def run(name,command,timeout=1200):
    (out/'STATUS').write_text(name+'\n');start=time.monotonic()
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
    commands.append({'name':name,'command':command,'exitCode':result.returncode,'wallSeconds':time.monotonic()-start})
    (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n');sync();assert result.returncode==0,name
pathlib.Path('oracle-source').mkdir()
run('extract',['tar','xf','oracle-source.tar','-C','oracle-source'])
shutil.copy('oracle-tensor-capture.cpp','oracle-source/examples/eval-callback/eval-callback.cpp')
run('configure',['cmake','-S','oracle-source','-B','oracle-build','-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_EXAMPLES=ON',
    '-DLLAMA_BUILD_APP=OFF','-DLLAMA_BUILD_UI=OFF','-DLLAMA_OPENSSL=OFF','-DGGML_BLAS=OFF'])
run('build-baseline',['cmake','--build','oracle-build','--target','llama-eval-callback','-j','8'])
shutil.copy('oracle-build/bin/llama-eval-callback','oracle-baseline')
p=pathlib.Path('oracle-source/ggml/src/ggml-cpu/ops.cpp');s=p.read_text()
old='const float scale = 1.0f/fmaxf(sqrtf(sum), eps);';new='const float scale = 1.0f/sqrtf(sum + eps);'
assert s.count(old)==1;p.write_text(s.replace(old,new))
(out/'oracle-change.json').write_text(json.dumps({'before':old,'after':new,'baseRevision':config['oracleRevision'],
    'sourceBeforeSha256':hashlib.sha256(s.encode()).hexdigest(),'sourceAfterSha256':hashlib.sha256(p.read_bytes()).hexdigest()},indent=2)+'\n')
run('build-additive',['cmake','--build','oracle-build','--target','llama-eval-callback','-j','8'])
shutil.copy('oracle-build/bin/llama-eval-callback','oracle-additive')
run('build-native',['/root/.cargo/bin/cargo','build','--locked','--release','--manifest-path','rust/Cargo.toml'])
(out/'binary-hashes.json').write_text(json.dumps({f:hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest()
    for f in ['oracle-baseline','oracle-additive','rust/target/release/libjmodels_kernels.so']},indent=2)+'\n')
for model in config['models']:
    key=model['key'];file=key+'.gguf'
    run('download-'+key,['curl','-fL','--retry','3',model['downloadUri'],'-o',file])
    assert hashlib.sha256(pathlib.Path(file).read_bytes()).hexdigest()==model['sha256']
    capture=out/(key+'-models');capture.mkdir()
    run(key+'-models',['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
        '-Xmx16g','-cp','capture.jar:lib/*','com.integrallis.models.backend.purejava.qwen35.OracleTensorCapture',
        file,'rust/target/release/libjmodels_kernels.so','tasks.json',str(capture)])
    requests=[json.loads(p.read_text()) for p in sorted(capture.glob('*/result.json'))]
    assert len(requests)==9
    req=out/(key+'-requests.json');req.write_text(json.dumps(requests,indent=2)+'\n')
    for arm,batch in [('baseline',512),('baseline',32),('additive',32)]:
        label=f'{key}-{arm}-{batch}';dest=out/label;dest.mkdir()
        run(label,[str(pathlib.Path('oracle-'+arm).resolve()),file,str(req),str(dest),str(batch)])
    pathlib.Path(file).unlink()
(out/'STATUS').write_text('COMPLETE\n');sync()
