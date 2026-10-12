"""Diagnose oracle batch sensitivity without altering a qualification gate or production source."""
import hashlib,json,os,pathlib,signal,socket,subprocess,time,urllib.request
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof'
source=out/'remote-production-correctness-v3/x86/qwen-0.8b-oracle'
checks=json.loads((source/'results.json').read_text());binary=pathlib.Path('/usr/local/bin/llama-server').resolve()
for batch in [512,32,1]:
    target=out/f'local-oracle-batch-{batch}';target.mkdir(exist_ok=False)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    command=[str(binary),'-m',str(pathlib.Path.home()/'.jvllm/models/Qwen3.5-0.8B-Q4_K_M.gguf'),'--host','127.0.0.1','--port',str(port),'-t','4','-tb','4','-c','2048','-np','1','-ngl','0','--no-op-offload','-fa','off','-ctk','f32','-ctv','f32','-ub',str(batch)]
    (target/'provenance.json').write_text(json.dumps({'command':command,'binarySHA256':hashlib.sha256(binary.read_bytes()).hexdigest(),'version':subprocess.check_output([str(binary),'--version'],stderr=subprocess.STDOUT,text=True)},indent=2)+'\n')
    deadline=time.monotonic()+600
    def request(endpoint,body=None):
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('oracle deadline')
        req=urllib.request.Request(f'http://127.0.0.1:{port}/'+endpoint,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=min(remaining,120)) as response:return json.load(response)
    with (target/'server.stdout').open('w') as stdout,(target/'server.stderr').open('w') as stderr:
        process=subprocess.Popen(command,stdout=stdout,stderr=stderr,start_new_session=True)
        try:
            while True:
                if process.poll() is not None:raise RuntimeError('oracle server exited')
                try:
                    if request('health').get('status')=='ok':break
                except (OSError,TimeoutError):
                    if time.monotonic()>=deadline:raise
                time.sleep(1)
            results=[]
            for r in checks:
                id=r['id'];body=json.loads((source/(id+'.request.json')).read_text())
                (target/(id+'.request.json')).write_text(json.dumps(body,indent=2)+'\n')
                response=request('completion',body);(target/(id+'.response.json')).write_text(json.dumps(response,indent=2)+'\n')
                result={'id':id,'tokens':response.get('tokens'),'linuxOracleTokens':r['oracleTokens'],'modelsTokens':r['arms']['baseline']['tokens']}
                results.append(result);(target/'results.json').write_text(json.dumps(results,indent=2)+'\n');print(batch,id,result['tokens'],flush=True)
            (target/'STATUS').write_text('COMPLETE\n')
        finally:
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
            (target/'termination.json').write_text(json.dumps({'pid':process.pid,'exitCode':process.returncode,'processExited':process.poll() is not None},indent=2)+'\n')
