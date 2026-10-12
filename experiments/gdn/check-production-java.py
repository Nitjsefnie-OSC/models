import hashlib,json,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
out=root/'benchmark-results/2026-10-11-gdn-proof'
stage=root/'experiments/gdn/generated/payload-production-correctness-v2'
source=root.parent/'models-gdn-java-production/backend-java/src/test/java/com/integrallis/models/backend/purejava/qwen35/GatedDeltaNetKernelTest.java'
temp=root/'experiments/gdn/generated/production-contract-probe';temp.mkdir(exist_ok=True)
# Add a mixed vector/tail width to the exact row-order control, without modifying frozen artifacts.
probeSource=temp/source.name
probeSource.write_text(source.read_text().replace('new int[] {7, 128, 256}', 'new int[] {7, 128, 131, 256}'))
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
libs=[]
for group,artifact,version in [('org.assertj','assertj-core','3.27.7'),('org.junit.jupiter','junit-jupiter-api','6.0.3'),('org.apiguardian','apiguardian-api','1.1.2'),('org.opentest4j','opentest4j','1.3.0')]:
    jars=list((cache/group/artifact/version).glob('*/*.jar'));assert len(jars)==1;libs.append(str(jars[0]))
classpath=':'.join([str(stage/'lib/java/*'),*libs])
jdk=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin')
subprocess.run([str(jdk/'javac'),'--add-modules','jdk.incubator.vector','-cp',classpath,'-d',str(temp),str(probeSource),str(root/'experiments/gdn/source-checks/ProductionJavaKernelProbe.java')],check=True)
cases={'default':[], '128':['-Dvectors.maxBits=128'],'scalar':['-Dvectors.forceScalar=true'],
       'unfused':['-Dvectors.useVectorFMA=false','-Dvectors.useScalarFMA=false'],
       'vector-unfused':['-Dvectors.useVectorFMA=false','-Dvectors.useScalarFMA=true'],
       'scalar-unfused':['-Dvectors.useVectorFMA=true','-Dvectors.useScalarFMA=false'],
       'one-worker':['-Dvectors.gguf.threads=1']}
for name,flags in cases.items():
    command=[str(jdk/'java'),'--add-modules','jdk.incubator.vector','-XX:ActiveProcessorCount=4',*flags,'-cp',str(temp)+':'+classpath,'com.integrallis.models.backend.purejava.qwen35.ProductionJavaKernelProbe']
    with (out/('java-production-contract-'+name+'.log')).open('w') as log:
        result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=120)
    (out/('java-production-contract-'+name+'.json')).write_text(json.dumps({'command':command,'exitCode':result.returncode,'sourceRevision':'acf295bd','testSourceSha256':hashlib.sha256(probeSource.read_bytes()).hexdigest()},indent=2)+'\n')
    assert result.returncode==0,name
    print(name,'PASS',flush=True)
