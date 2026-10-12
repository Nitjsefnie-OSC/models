"""Isolate the external oracle's L2 epsilon convention; never changes Models or a gate."""
import hashlib,json,os,pathlib,shutil,signal,socket,subprocess,time,urllib.request
out=pathlib.Path('/work/out');config=json.loads(pathlib.Path('diagnostic.json').read_text());commands=[]
def run(name,command,timeout=900):
    (out/'STATUS').write_text(name+'\n');start=time.monotonic()
    with (out/(name+'.log')).open('w') as log:r=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
    commands.append({'name':name,'command':command,'exitCode':r.returncode,'wallSeconds':time.monotonic()-start})
    (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
    assert r.returncode==0,name
pathlib.Path('oracle-source').mkdir()
run('extract',['tar','xf','oracle-source.tar','-C','oracle-source'])
run('configure',['cmake','-S','oracle-source','-B','oracle-build','-DCMAKE_BUILD_TYPE=Release',
    '-DBUILD_SHARED_LIBS=OFF','-DLLAMA_BUILD_TESTS=OFF','-DLLAMA_BUILD_EXAMPLES=OFF',
    '-DLLAMA_BUILD_APP=OFF','-DLLAMA_BUILD_UI=OFF','-DLLAMA_OPENSSL=OFF'])
run('build-baseline',['cmake','--build','oracle-build','--target','llama-server','-j','8'])
shutil.copy('oracle-build/bin/llama-server','llama-server-baseline')
path=pathlib.Path('oracle-source/ggml/src/ggml-cpu/ops.cpp');source=path.read_text()
old='const float scale = 1.0f/fmaxf(sqrtf(sum), eps);';new='const float scale = 1.0f/sqrtf(sum + eps);'
assert source.count(old)==1
path.write_text(source.replace(old,new))
(out/'source-change.json').write_text(json.dumps({'baseRevision':config['oracleRevision'],
    'file':str(path),'before':old,'after':new,'sourceBeforeSha256':hashlib.sha256(source.encode()).hexdigest(),
    'sourceAfterSha256':hashlib.sha256(path.read_bytes()).hexdigest(),
    'scope':'Diagnostic oracle variant only; not a publisher reference or a qualification pass'},indent=2)+'\n')
run('build-additive-epsilon',['cmake','--build','oracle-build','--target','llama-server','-j','8'])
shutil.copy('oracle-build/bin/llama-server','llama-server-additive-epsilon')
run('download',['curl','-fL','--retry','3',config['model']['downloadUri'],'-o','model.gguf'])
assert hashlib.sha256(pathlib.Path('model.gguf').read_bytes()).hexdigest()==config['model']['sha256']
checks=json.loads(pathlib.Path('requests/results.json').read_text());results=[]
for arm in ('baseline','additive-epsilon'):
  for batch in (512,1):
    label=f'{arm}-batch{batch}';target=out/label;target.mkdir();(out/'STATUS').write_text(label+'\n')
    binary=pathlib.Path('llama-server-'+arm).resolve()
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    command=[str(binary),'-m','model.gguf','--host','127.0.0.1','--port',str(port),'-t','4','-tb','4',
        '-c','2048','-np','1','-ngl','0','--no-op-offload','-fa','off','-ctk','f32','-ctv','f32','-ub',str(batch)]
    (target/'provenance.json').write_text(json.dumps({'command':command,'binarySha256':hashlib.sha256(binary.read_bytes()).hexdigest()},indent=2)+'\n')
    deadline=time.monotonic()+600
    def request(endpoint,body=None):
      remaining=deadline-time.monotonic();assert remaining>0,'server deadline'
      req=urllib.request.Request(f'http://127.0.0.1:{port}/'+endpoint,
          data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json'})
      with urllib.request.urlopen(req,timeout=min(remaining,120)) as response:return json.load(response)
    with (target/'server.stdout').open('w') as stdout,(target/'server.stderr').open('w') as stderr:
      process=subprocess.Popen(command,stdout=stdout,stderr=stderr,start_new_session=True)
      try:
        while True:
          assert process.poll() is None,'server exited'
          try:
            if request('health').get('status')=='ok':break
          except (OSError,TimeoutError):
            if time.monotonic()>=deadline:raise
          time.sleep(1)
        for check in checks:
          id=check['id'];body=json.loads(pathlib.Path('requests',id+'.request.json').read_text())
          response=request('completion',body);assert isinstance(response.get('tokens'),list)
          (target/(id+'.response.json')).write_text(json.dumps(response,indent=2)+'\n')
          results.append({'arm':arm,'microbatch':batch,'id':id,'oracleTokens':response['tokens'],
              'modelsTokens':check['arms']['baseline']['tokens'],
              'matchesModels':response['tokens']==check['arms']['baseline']['tokens']})
          (out/'comparison.json').write_text(json.dumps(results,indent=2)+'\n')
      finally:
        if process.poll() is None:
          os.killpg(process.pid,signal.SIGTERM)
          try:process.wait(timeout=15)
          except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        (target/'termination.json').write_text(json.dumps({'pid':process.pid,'exitCode':process.returncode,'processExited':process.poll() is not None},indent=2)+'\n')
    subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
assert len(results)==36
(out/'STATUS').write_text('COMPLETE\n')
