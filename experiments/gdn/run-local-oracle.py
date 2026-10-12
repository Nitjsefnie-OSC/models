"""Bounded, owned loopback oracle; retain requests, responses and negative results."""
import hashlib,json,os,pathlib,signal,socket,subprocess,time,urllib.request

root=pathlib.Path(__file__).resolve().parents[2]
out=root/'benchmark-results/2026-10-11-gdn-proof'
target=out/'local-oracle-v1'
target.mkdir(exist_ok=False)
inputs=[json.loads(line) for line in (out/'oracle-inputs.jsonl').read_text().splitlines()]
assert len(inputs)==2
baseline={row['id']:row['greedyTokens'] for line in (out/'local-ownership-model-v2.stdout').read_text().splitlines()
    if line.startswith('{') and (row:=json.loads(line))['route']==0}
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
binary=pathlib.Path('/usr/local/bin/llama-server').resolve()
model=pathlib.Path.home()/'.jvllm/models/Qwen3.5-4B-Q4_K_M.gguf'
command=[str(binary),'-m',str(model),'--host','127.0.0.1','--port',str(port),
    '-t','4','-tb','4','-c','2048','-np','1','-ngl','0','--no-op-offload',
    '-fa','off','-ctk','f32','-ctv','f32']
provenance={'command':command,'binarySHA256':hashlib.sha256(binary.read_bytes()).hexdigest(),
    'version':subprocess.check_output([str(binary),'--version'],stderr=subprocess.STDOUT,text=True),
    'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    'inputSHA256':hashlib.sha256((out/'oracle-inputs.jsonl').read_bytes()).hexdigest(),
    'modelSHA256':'00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4',
    'maximumSeconds':600}
(target/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
deadline=time.monotonic()+600
def request(endpoint,body=None):
    remaining=deadline-time.monotonic()
    if remaining<=0:raise TimeoutError('oracle deadline')
    data=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request(f'http://127.0.0.1:{port}/'+endpoint,data=data,
        headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=min(remaining,120)) as response:return json.load(response)
with (target/'server.stdout').open('w') as stdout,(target/'server.stderr').open('w') as stderr:
    process=subprocess.Popen(command,stdout=stdout,stderr=stderr,start_new_session=True)
    (target/'pid').write_text(str(process.pid)+'\n')
    try:
        while True:
            if process.poll() is not None:raise RuntimeError('oracle server exited before ready')
            try:
                if request('health').get('status')=='ok':break
            except (OSError,TimeoutError):
                if time.monotonic()>=deadline:raise
            time.sleep(1)
        results=[]
        for row in inputs:
            tokenized=request('tokenize',{'content':row['prompt'],'add_special':False,'parse_special':True})
            (target/(row['id']+'.tokenization.json')).write_text(json.dumps(tokenized,indent=2)+'\n')
            body={'prompt':row['tokens'],'n_predict':8,'temperature':0,'samplers':['temperature'],
                'repeat_penalty':1.0,'cache_prompt':False,'return_tokens':True,'n_probs':16,'seed':0}
            (target/(row['id']+'.request.json')).write_text(json.dumps(body,indent=2)+'\n')
            response=request('completion',body)
            (target/(row['id']+'.response.json')).write_text(json.dumps(response,indent=2)+'\n')
            result={'id':row['id'],'tokenizationExact':tokenized['tokens']==row['tokens'],
                'modelsTokens':baseline[row['id']],'oracleTokens':response.get('tokens'),
                'continuationExact':response.get('tokens')==baseline[row['id']],
                'scope':'independent greedy-token check only; no performance or full-logit qualification'}
            results.append(result)
            (target/'results.json').write_text(json.dumps(results,indent=2)+'\n')
            print(json.dumps(result),flush=True)
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
