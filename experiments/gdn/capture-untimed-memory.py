"""Read memory diagnostics from an already-running untimed correctness JVM only."""
import datetime,json,pathlib,subprocess,sys
arm=sys.argv[1];assert arm in ['native','published'];matches=[]
for p in pathlib.Path('/proc').iterdir():
 if not p.name.isdigit():continue
 try:args=(p/'cmdline').read_bytes().split(b'\0');args=[x.decode()for x in args if x]
 except (FileNotFoundError,PermissionError,UnicodeError):continue
 if not args or pathlib.Path(args[0]).name!='java':continue
 klass='com.integrallis.models.backend.purejava.qwen35.ProductionRouteProof'
 if klass not in args:continue
 k=args.index(klass)
 if args[k+1]!='cohort' or args[k+2]!='qwen-4b.gguf' or f'lib/{arm}/*:route-proof.jar'not in args:continue
 matches.append((p,args))
assert len(matches)==1,('expected one untimed 4B cohort',arm,[(str(p),a)for p,a in matches])
p,args=matches[0];timestamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');out=pathlib.Path('/work/out')/f'memory-diagnostic-{arm}-{timestamp}';out.mkdir()
for name in ['smaps','smaps_rollup','status','maps']:(out/name).write_bytes((p/name).read_bytes())
command=['/opt/jdk/bin/jcmd',p.name,'GC.heap_info'];result=subprocess.run(command,text=True,capture_output=True,timeout=15);assert result.returncode==0,result.stderr
(out/'heap-info.txt').write_text(result.stdout)
(out/'capture.json').write_text(json.dumps({'capturedAt':timestamp,'pid':int(p.name),'arm':arm,'command':args,'diagnosticCommand':command,'scope':'Read-only /proc and heap-info diagnostic of untimed correctness JVM; no timing JVM attached and no explicit GC requested.'},indent=2)+'\n')
print(out)
