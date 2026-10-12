import hashlib,json,os,pathlib,subprocess
out=pathlib.Path('/work/out')
def run(command,name,timeout=1800):
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout,check=True)
def status(text):
    (out/'STATUS').write_text(text+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
variant='sequential-aligned-parallel-exclusive'
status('BUILDING')
run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{variant}/Cargo.toml'],'build-exclusive')
library=f'/work/rust/{variant}/target/release/libjmodels_kernels.so'
(out/'native-hashes.json').write_text(json.dumps({variant:hashlib.sha256(pathlib.Path(library).read_bytes()).hexdigest()},indent=2))
java=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED',
    '-XX:ActiveProcessorCount=4','-Xms1g','-Xmx4g','-Dmodels.native.kernels.threads=4',
    '-Dgdn.nativeLibrary='+library,'-cp','lib/*:gdn.jar']
status('CORRECTNESS')
for name in ['PollingPoolCheck','RegisterEdgeCheck','ExclusiveProof','GroupProof']:
    run([*java,'com.integrallis.models.backend.purejava.qwen35.'+name,*([library] if name in ('ExclusiveProof','GroupProof') else [])],name)
arms=['java','java-tiled-stage','native-aligned-parallel']
order=[]
for repeat in range(3):
    names=arms[repeat:]+arms[:repeat]
    if repeat%2:names.reverse()
    for arm in names:
        for mode,benchmark,parameter,count in [('prefix','GdnJmh','tokens=1,18,32,128,512',5),('group','GroupJmh','branches=1,3,8,16',4)]:
            name=f'exclusive-{repeat}-{mode}-{arm}'
            order.append({'repeat':repeat,'mode':mode,'arm':arm})
            (out/'run-order.json').write_text(json.dumps(order,indent=2))
            status(name)
            run([*java,'org.openjdk.jmh.Main',benchmark+'.recurrence','-p','arm='+arm,'-p',parameter,
                '-f','1','-wi','5','-i','10','-w','1s','-r','1s','-prof','gc','-foe','true',
                '-rf','json','-rff',str(out/(name+'.json'))],name)
            assert len(json.loads((out/(name+'.json')).read_text()))==count
status('COMPLETE')
