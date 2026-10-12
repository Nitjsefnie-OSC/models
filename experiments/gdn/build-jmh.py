import pathlib,shutil,subprocess
root=pathlib.Path(__file__).resolve().parents[2]
java=pathlib.Path('/Users/briansam-bodden/.sdkman/candidates/java/25.0.3-tem/bin')
generated=root/'experiments/gdn/generated';classes=generated/'jmh-classes';classes.mkdir(exist_ok=True)
lib=generated/'jmh-lib';lib.mkdir(exist_ok=True)
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
for group,artifact,version in [('org.openjdk.jmh','jmh-core','1.37'),('org.openjdk.jmh','jmh-generator-annprocess','1.37'),
        ('net.sf.jopt-simple','jopt-simple','5.0.4'),('org.apache.commons','commons-math3','3.6.1')]:
    matches=list((cache/group/artifact/version).glob('*/*.jar'));assert len(matches)==1,(artifact,matches)
    shutil.copy(matches[0],lib/matches[0].name)
classpath=':'.join([str(root/'models-bench/build/install/models-bench/lib/*'),str(root/'backend-native/build/libs/*'),str(lib/'*')])
sources=[*generated.glob('*Recurrence.java'),*(root/'experiments/gdn').glob('*.java')]
subprocess.run([str(java/'javac'),'--add-modules','jdk.incubator.vector','-cp',classpath,
    '-processor','org.openjdk.jmh.generators.BenchmarkProcessor','-d',str(classes),*[str(p) for p in sources]],check=True)
manifest=generated/'agent-manifest.mf'
manifest.write_text('Manifest-Version: 1.0\nPremain-Class: com.integrallis.models.backend.purejava.qwen35.StabilityAgent\n\n')
subprocess.run([str(java/'jar'),'cfm',str(generated/'gdn-jmh.jar'),str(manifest),'-C',str(classes),'.'],check=True)
subprocess.run([str(java/'java'),'-XX:ActiveProcessorCount=4','-cp',str(classes),
    'com.integrallis.models.backend.purejava.qwen35.PollingPoolCheck'],check=True,timeout=60)
print(generated/'gdn-jmh.jar')
