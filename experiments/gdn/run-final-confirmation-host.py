"""Compare immutable production artifacts in independent JVMs on one owned host."""
import hashlib,json,os,pathlib,subprocess,time
out=pathlib.Path('/work/out')
config=json.loads(pathlib.Path('confirmation.json').read_text())
arms=config['arms']
failures=[]

def status(value):
    (out/'STATUS').write_text(value+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)

def run(command,name,timeout=6200):
    start=time.monotonic()
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
    (out/(name+'.execution.json')).write_text(json.dumps({'command':command,'exitCode':result.returncode,'wallSeconds':time.monotonic()-start},indent=2)+'\n')
    assert result.returncode==0,(name,result.returncode)
    rows=[]
    for line in (out/(name+'.stdout')).read_text().splitlines():
        if line.startswith('{'):rows.append(json.loads(line))
    return rows

status('BUILDING')
for arm in arms:
    run(['/root/.cargo/bin/cargo','build','--release','--manifest-path',f'rust/{arm}/Cargo.toml'],'build-'+arm)
    f=pathlib.Path(f'rust/{arm}/target/release/libjmodels_kernels.so')
    (out/('native-'+arm+'.sha256')).write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'\n')
base=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms2g','-Xmx10g']
# No models/vectors tuning properties: worker count, polling and dispatch are the library defaults.
assert not any(a.startswith('-Dmodels.') or a.startswith('-Dvectors.') for a in base)

def execute(arm,model,phase,name,extra=[]):
    native=arm
    return run(['/usr/bin/time','-v',*base,'-cp',f'lib/{arm}/*:route-proof.jar',
        'com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof',phase,model,
        f'/work/rust/{native}/target/release/libjmodels_kernels.so','data/tasks.json',*extra],name)

if config['phase']=='correctness':
    pathlib.Path('oracle-source').mkdir()
    run(['tar','xf','oracle-source.tar','-C','oracle-source'],'oracle-extract')
    run(['cmake','-S','oracle-source','-B','oracle-build','-DCMAKE_BUILD_TYPE=Release',
        '-DBUILD_SHARED_LIBS=OFF','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_EXAMPLES=OFF',
        '-DLLAMA_BUILD_APP=OFF','-DLLAMA_BUILD_UI=OFF','-DLLAMA_OPENSSL=OFF'], 'oracle-configure')
    run(['cmake','--build','oracle-build','--target','llama-server','-j','8'],'oracle-build')

summary=[]
for model in config['models']:
    key=model['key'];file=key+'.gguf'
    status('DOWNLOAD_'+key)
    run(['curl','-fL','--retry','3',model['downloadUri'],'-o',file],'download-'+key)
    h=hashlib.sha256()
    with open(file,'rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    assert h.hexdigest()==model['sha256'],key
    if config['phase']=='correctness':
        for arm in [a for a in arms if a!='published']:
            status('ROUTING_'+key+'_'+arm)
            execute(arm,file,'routing',key+'-'+arm+'-routing',['true' if arm in ('native','strictnative') else 'false'])
        records={}
        for arm in arms:
            status('COHORT_'+key+'_'+arm)
            rows=execute(arm,file,'cohort',key+'-'+arm+'-cohort')
            requests=[r for r in rows if r['kind']=='request']
            assert len(requests)==148,(key,arm,len(requests))
            records[arm]={r['id']:r for r in requests}
            assert len(records[arm])==148
            if arm=='baseline':continue
            changes=[]
            for id,r in records[arm].items():
                before=records['baseline'][id]
                changed={k:{'baseline':before[k],'candidate':r[k]} for k in
                    ['fullLogitSha256','inputTokens','greedyTokens','prediction'] if r[k]!=before[k]}
                if changed:changes.append({'id':id,'changes':changed})
            result={'model':key,'arm':arm,'requests':148,'changes':changes,
                'correctLabels':sum(r['prediction']==r['expected'] for r in requests)}
            summary.append(result)
            (out/'comparison.json').write_text(json.dumps(summary,indent=2)+'\n')
            if arm!='published':assert not changes,(key,arm,'numeric contract changed')
            # Published arithmetic is known to drift. Preserve every difference for oracle diagnosis;
            # never call that comparison exact or silently remove changed cases.
        status('ORACLE_'+key)
        try:
            run(['python3','run-oracle.py',key,file,str(pathlib.Path('oracle-build/bin/llama-server').resolve())],key+'-oracle')
        except AssertionError as error:
            failures.append({'model':key,'gate':'independent-oracle','error':str(error)})
            (out/'failed-gates.json').write_text(json.dumps(failures,indent=2)+'\n')
    else:
        assert config['phase']=='timing'
        for pair in range(10):
            order=arms[pair%len(arms):]+arms[:pair%len(arms)]
            if pair%2:order.reverse()
            for arm in order:
                status(f'TIMING_{key}_{pair}_{arm}')
                execute(arm,file,'timing',f'{key}-pair{pair}-{arm}',[str(pair)])
if failures:
    status('FAILED_ORACLE')
    raise AssertionError(failures)
status('COMPLETE')
