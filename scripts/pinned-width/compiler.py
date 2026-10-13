"""Capture generated code only; instrumented durations never qualify performance."""
import hashlib,json,subprocess,sys
from pathlib import Path
out=Path(sys.argv[1]).resolve()
manifest={'scope':'Compiler diagnostics only. Instrumentation may alter compilation; no timing or allocation gate uses these processes.','logCompilationEnabled':False,'runs':[]}
for kernel,klass in [('dot','ops.PinnedReduction'),('decoder','quant.Q5_1Dequantizer')]:
    for arm in ('baseline','candidate'):
        prefix=out/f'compiler-{kernel}-{arm}'
        command=['java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms256m','-Xmx2g','-XX:+UnlockDiagnosticVMOptions','-XX:+PrintCompilation',f'-XX:CompileCommand=print,com.integrallis.models.backend.purejava.{klass}::*','-XX:CompileCommand=print,WidthBench::*','-cp',str(out/kernel/arm),'WidthBench',kernel]
        with prefix.with_suffix('.stdout').open('w') as stdout,prefix.with_suffix('.stderr').open('w') as stderr:
            process=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=180)
        record={'kernel':kernel,'arm':arm,'command':command,'exitCode':process.returncode,'stdoutSha256':hashlib.sha256(prefix.with_suffix('.stdout').read_bytes()).hexdigest()}
        manifest['runs'].append(record)
        (out/'compiler-provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
        assert process.returncode==0
    records=[]
    for arm in ('baseline','candidate'):
        values=[json.loads(line) for line in (out/f'compiler-{kernel}-{arm}.stdout').read_text().splitlines() if line.startswith('{')]
        records.append([r for r in values if r['kind']=='output'])
    assert records[0]==records[1], 'instrumented numerical output differs'
(out/'compiler-COMPLETE').write_text('instrumented numerical outputs match; inspect compiled code independently\n')
