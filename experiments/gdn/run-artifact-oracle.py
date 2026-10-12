"""Independent greedy continuation checks; preserve full responses and owned-server exit."""
import hashlib,json,os,pathlib,signal,socket,subprocess,sys,time,urllib.request

key,model,binary=sys.argv[1:]
out=pathlib.Path('/work/out');target=out/(key+'-oracle');target.mkdir(exist_ok=False)
tasks=json.loads(pathlib.Path('data/tasks.json').read_text())
selected=[t for dataset in ['jevbench','snips'] for t in [r for r in tasks if r['dataset']==dataset][:4]]
historical=next(t for t in tasks if t['id']=='original-routing-06-0')
if historical not in selected:selected.append(historical)
assert len(selected)==9
records={}
for arm in ['baseline','java','native','published']:
    records[arm]={r['id']:r for line in (out/f'{key}-{arm}-cohort.stdout').read_text().splitlines()
        if line.startswith('{') and (r:=json.loads(line))['kind']=='request'}
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
command=[binary,'-m',model,'--host','127.0.0.1','--port',str(port),'-t','4','-tb','4',
    '-c','2048','-np','1','-ngl','0','--no-op-offload','-fa','off','-ctk','f32','-ctv','f32']
(target/'provenance.json').write_text(json.dumps({'command':command,
    'binarySha256':hashlib.sha256(pathlib.Path(binary).read_bytes()).hexdigest(),
    'version':subprocess.check_output([binary,'--version'],stderr=subprocess.STDOUT,text=True),
    'sourceRevision':json.loads(pathlib.Path('confirmation.json').read_text())['oracleRevision'],
    'selectedIds':[t['id'] for t in selected],'maximumSeconds':1200},indent=2)+'\n')
deadline=time.monotonic()+1200
def request(endpoint,body=None):
    remaining=deadline-time.monotonic()
    if remaining<=0:raise TimeoutError('oracle deadline')
    req=urllib.request.Request(f'http://127.0.0.1:{port}/'+endpoint,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=min(remaining,120)) as response:return json.load(response)
with (target/'server.stdout').open('w') as stdout,(target/'server.stderr').open('w') as stderr:
    process=subprocess.Popen(command,stdout=stdout,stderr=stderr,start_new_session=True)
    try:
        while True:
            if process.poll() is not None:raise RuntimeError('oracle server exited before ready')
            try:
                if request('health').get('status')=='ok':break
            except (OSError,TimeoutError):
                if time.monotonic()>=deadline:raise
            time.sleep(1)
        results=[]
        for task in selected:
            id=task['id'];reference=records['baseline'][id]
            tokenized=request('tokenize',{'content':task['prompt'],'add_special':False,'parse_special':True})
            (target/(id+'.tokenization.json')).write_text(json.dumps(tokenized,indent=2)+'\n')
            body={'prompt':reference['inputTokens'],'n_predict':8,'temperature':0,'samplers':['temperature'],
                'repeat_penalty':1.0,'cache_prompt':False,'return_tokens':True,'n_probs':16,'seed':0}
            (target/(id+'.request.json')).write_text(json.dumps(body,indent=2)+'\n')
            response=request('completion',body)
            (target/(id+'.response.json')).write_text(json.dumps(response,indent=2)+'\n')
            result={'id':id,'tokenizationExact':tokenized['tokens']==reference['inputTokens'],
                'oracleTokens':response.get('tokens'),
                'arms':{arm:{'tokens':rows[id]['greedyTokens'],
                    'exact':response.get('tokens')==rows[id]['greedyTokens']} for arm,rows in records.items()},
                'scope':'independent token check; full-logit equality is checked separately between Models artifacts'}
            results.append(result)
            (target/'results.json').write_text(json.dumps(results,indent=2)+'\n')
            print(json.dumps(result),flush=True)
        assert all(r['tokenizationExact'] and all(r['arms'][a]['exact'] for a in ['baseline','java','native']) for r in results), 'independent oracle mismatch'
        (target/'STATUS').write_text('COMPLETE\n')
    except BaseException:
        (target/'STATUS').write_text('FAILED\n');raise
    finally:
        if process.poll() is None:
            os.killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL);process.wait()
        (target/'termination.json').write_text(json.dumps({'pid':process.pid,'exitCode':process.returncode,
            'processExited':process.poll() is not None},indent=2)+'\n')
