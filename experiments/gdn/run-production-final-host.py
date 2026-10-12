"""Fixed warmup final timing: one declared model/pair shard per owned host."""
import hashlib,json,os,pathlib,subprocess,time
out=pathlib.Path('/work/out');config=json.loads(pathlib.Path('confirmation.json').read_text())
shardId=os.environ['SHARD_ID']; shard=config['shards'][shardId];arms=config['arms'];failures=[]
(out/'shard.json').write_text(json.dumps({'shard':shardId,**shard},indent=2)+'\n')
def status(value):
    (out/'STATUS').write_text(value+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
def run(command,name,timeout=1200):
    start=time.monotonic()
    with (out/(name+'.stdout')).open('w') as stdout,(out/(name+'.stderr')).open('w') as stderr:
        result=subprocess.run(command,stdout=stdout,stderr=stderr,timeout=timeout)
    (out/(name+'.execution.json')).write_text(json.dumps({'command':command,'exitCode':result.returncode,'wallSeconds':time.monotonic()-start,'shard':shardId},indent=2)+'\n')
    assert result.returncode==0,(name,result.returncode)
    return [json.loads(line) for line in (out/(name+'.stdout')).read_text().splitlines() if line.startswith('{')]
status('BUILDING')
for arm in ('native','published'):
    run(['/root/.cargo/bin/cargo','build','--locked','--release','--manifest-path',f'rust/{arm}/Cargo.toml'],'build-'+arm)
    f=pathlib.Path(f'rust/{arm}/target/release/libjmodels_kernels.so')
    (out/('native-'+arm+'.sha256')).write_text(hashlib.sha256(f.read_bytes()).hexdigest()+'\n')
base=['/opt/jdk/bin/java','--add-modules','jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xms2g','-Xmx10g']
assert not any(a.startswith('-Dmodels.') or a.startswith('-Dvectors.') for a in base)
run([*base,'-cp','lib/native/*:route-proof.jar','com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof','eligibility','unused','/work/rust/native/target/release/libjmodels_kernels.so','data/tasks.json'],'eligibility-allocation')
models=[m for m in config['models'] if shard.get('model') in (None,m['key'])]
for model in models:
    key=model['key'];file=key+'.gguf'
    status('DOWNLOAD_'+key)
    run(['curl','-fL','--retry','3',model['downloadUri'],'-o',file],'download-'+key)
    h=hashlib.sha256()
    with open(file,'rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    assert h.hexdigest()==model['sha256'],key
    def execute(arm,phase,name,extra=[]):
        native='published' if arm=='published' else 'native'
        return run(['/usr/bin/time','-v',*base,'-cp',f'lib/{arm}/*:route-proof.jar',
            'com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof',phase,file,
            f'/work/rust/{native}/target/release/libjmodels_kernels.so','data/tasks.json',*extra],name,timeout=2400)
    if shardId in ('x86-small','arm-small'):
        for diagnostic in range(2):
            status(f'JIT_DIAGNOSTIC_{key}_{diagnostic}')
            run([*base,'-XX:+UnlockDiagnosticVMOptions','-XX:+LogCompilation',
                f'-XX:LogFile=/work/out/jit-diagnostic-{diagnostic}.xml',
                '-cp','lib/native/*:route-proof.jar',
                'com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof','timing',file,
                '/work/rust/native/target/release/libjmodels_kernels.so','data/tasks.json',str(diagnostic)],
                f'{key}-jit-diagnostic-{diagnostic}',timeout=2400)
    for arm in arms:
        status(f'ROUTING_{key}_{arm}')
        try:
            execute(arm,'routing',f'{key}-{arm}-routing',['true' if arm=='native' else 'false'])
        except AssertionError as error:
            if arm!='published':raise
            records=[json.loads(line) for line in (out/f'{key}-{arm}-routing.stdout').read_text().splitlines() if line.startswith('{')]
            route=next((r for r in records if r['kind']=='initialRoute'),None)
            assert route is not None and route['nativeExpected'] is False and route['recurrenceCalls']==0
            failures.append({'model':key,'arm':arm,'gate':'published-grouped-numeric-exactness','error':str(error)})
            (out/'failed-gates.json').write_text(json.dumps(failures,indent=2)+'\n')
    if shard['phase']=='correctness':
        records={};summary=[]
        for arm in arms:
            status(f'COHORT_{key}_{arm}')
            rows=execute(arm,'cohort',f'{key}-{arm}-cohort')
            requests=[r for r in rows if r['kind']=='request'];assert len(requests)==148
            assert all(len(r['fullLogitSha256'])==9 for r in requests)
            records[arm]={r['id']:r for r in requests};assert len(records[arm])==148
            changes=[]
            for id,r in records[arm].items():
                before=records['baseline'][id]
                changed={k:{'baseline':before[k],'candidate':r[k]} for k in ['fullLogitSha256','inputTokens','greedyTokens','prediction'] if r[k]!=before[k]}
                if changed:changes.append({'id':id,'changes':changed})
            summary.append({'model':key,'arm':arm,'requests':148,'changes':changes,
                'correctLabels':sum(r['prediction']==r['expected'] for r in requests),
                'perDataset':{dataset:{'correct':sum(r['prediction']==r['expected'] for r in requests if r['dataset']==dataset),'total':sum(r['dataset']==dataset for r in requests)} for dataset in sorted({r['dataset'] for r in requests})}})
            (out/(key+'-comparison.json')).write_text(json.dumps(summary,indent=2)+'\n')
            if arm=='native':assert not changes,(key,arm,'numeric contract changed')
    else:
        assert shard['phase']=='timing'
        for pair in shard['pairs']:
            order=arms[pair%len(arms):]+arms[:pair%len(arms)]
            if pair%2:order.reverse()
            for arm in order:
                status(f'TIMING_{key}_{pair}_{arm}')
                rows=execute(arm,'timing',f'{key}-pair{pair}-{arm}',[str(pair)])
                requests=[r for r in rows if r['kind']=='request']
                assert len(requests)==35,(arm,pair,len(requests))
                assert [r['repeat'] for r in requests]==list(range(-32,3))
                assert all(len(r['fullLogitSha256'])==9 for r in requests)
status('COMPLETE_WITH_FAILED_PUBLISHED_GATES' if failures else 'COMPLETE')
