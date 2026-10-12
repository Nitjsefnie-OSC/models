import hashlib,json,os,pathlib,subprocess
out=pathlib.Path('/work/out')
def run(command,name,timeout=900):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout,check=True)
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
status('BUILDING')
run(['/root/.cargo/bin/cargo','build','--release','--manifest-path','rust/auto/Cargo.toml'],'build-auto')
library='/work/rust/auto/target/release/libjmodels_kernels.so'
(out/'native-hash.txt').write_text(hashlib.sha256(pathlib.Path(library).read_bytes()).hexdigest()+'\n')
model=json.loads(pathlib.Path('model.json').read_text())
run(['curl','-fL','--retry','3',model['downloadUri'],'-o','model.gguf'],'model-download')
digest=hashlib.sha256()
with open('model.gguf','rb') as f:
    for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
assert digest.hexdigest()==model['sha256']
java=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
    '-XX:ActiveProcessorCount=4','-Xms2g','-Xmx10g','-Dmodels.native.kernels.threads=4',
    '-Dmodels.purejava.maxContextLength=2048']
status('GATE_STABILITY')
run([*java,'-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation','-XX:LogFile=/work/out/gate-compilation.xml',
    '-cp','lib/*:gdn.jar','com.integrallis.models.backend.purejava.qwen35.GateStabilityCheck'],'gate')
status('REFERENCE_STABILITY')
run([*java,'-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation','-XX:LogFile=/work/out/reference-compilation.xml',
    '-cp','lib/*:gdn.jar','com.integrallis.models.backend.purejava.qwen35.GdnStabilityCheck',
    library,'model.gguf','data/tasks.json','/work/out/tensors'],'reference')
if json.loads(pathlib.Path('diagnostic.json').read_text())['controls']:
    status('DETERMINISTIC_CONTROL')
    run([*java,'-javaagent:gdn.jar=all','-cp','lib/*:gdn.jar',
        'com.integrallis.models.backend.purejava.qwen35.GateStabilityCheck'],'gate-control')
    for mode in ['conv','all']:
        status('REFERENCE_CONTROL_'+mode)
        run([*java,'-javaagent:gdn.jar='+mode,'-cp','lib/*:gdn.jar',
            'com.integrallis.models.backend.purejava.qwen35.GdnStabilityCheck',
            library,'model.gguf','data/tasks.json','/work/out/tensors-'+mode],'reference-'+mode)
status('COMPLETE')
