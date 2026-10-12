import hashlib,json,os,pathlib,subprocess

out=pathlib.Path('/work/out')
config=json.loads(pathlib.Path('measurement.json').read_text())
def run(command,name,timeout=1800):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout,check=True)
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
for variant in config['nativeVariants']:
    run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{variant}/Cargo.toml'],f'build-{variant}')
libraries=[f'/work/rust/{v}/target/release/libjmodels_kernels.so' for v in ['auto','sequential','sequential-aligned']]
(out/'native-hashes.json').write_text(json.dumps({v:hashlib.sha256(pathlib.Path(f'/work/rust/{v}/target/release/libjmodels_kernels.so').read_bytes()).hexdigest() for v in config['nativeVariants']},indent=2))
java=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
      '-XX:ActiveProcessorCount=4','-Xms1g','-Xmx4g','-Dmodels.native.kernels.threads=4','-cp','lib/*:gdn.jar']
status('POLLING_CORRECTNESS')
run([*java,'com.integrallis.models.backend.purejava.qwen35.PollingPoolCheck'],'pool-check')
run([*java,'com.integrallis.models.backend.purejava.qwen35.RegisterEdgeCheck'],'register-edges')
run([*java[:-2],'-Dgdn.arms='+','.join(a for a in config['arms'] if a.startswith('java')),*java[-2:],
    'com.integrallis.models.backend.purejava.qwen35.GdnProof',*libraries],'polling-correctness')
arms=config['arms']
order=[]
for repeat in range(3):
    names=arms[repeat:]+arms[:repeat]
    if repeat%2:names.reverse()
    for arm in names:
        name=f'jmh-{repeat}-{arm}';order.append({'repeat':repeat,'arm':arm})
        (out/'run-order.json').write_text(json.dumps(order,indent=2))
        status(name)
        native=config['nativeForArm'].get(arm,'sequential-aligned')
        run([*java,'-Dgdn.nativeLibrary='+f'/work/rust/{native}/target/release/libjmodels_kernels.so','org.openjdk.jmh.Main','GdnJmh.recurrence',
            '-p','arm='+arm,'-p','tokens=1,18,32,128,512','-f','1','-wi','5','-i','10',
            '-w','1s','-r','1s','-prof','gc','-foe','true','-rf','json','-rff',str(out/(name+'.json'))],name)
        rows=json.loads((out/(name+'.json')).read_text())
        assert len(rows)==5,(name,len(rows))
status('COMPLETE')
