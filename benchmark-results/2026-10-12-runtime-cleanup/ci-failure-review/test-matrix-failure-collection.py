from pathlib import Path
import tempfile,subprocess,os,json
src=Path('projects/models-final-integration/scripts/run-purejava-determinism-matrix.sh'); results=[]
with tempfile.TemporaryDirectory() as d:
 r=Path(d);(r/'scripts').mkdir();(r/'scripts/audit.sh').write_text(src.read_text());(r/'fakebin').mkdir();j=r/'fakebin/java';j.write_text('#!/bin/sh\necho "    java.specification.version = 25" >&2\n');j.chmod(0o755)
 g=r/'gradlew';g.write_text('#!/bin/sh\nexit 0\n');g.chmod(0o755)
 cli=r/'models-bench/build/install/models-bench/bin/models-bench';cli.parent.mkdir(parents=True);cli.write_text('#!/bin/sh\nfor arg do last="$arg"; done\necho "$last" >> "$AUDIT_CALLS"\ncase "$last" in *panama-default.json) exit "$DEFAULT_EXIT";; esac\nexit 0\n');cli.chmod(0o755);(r/'model.gguf').touch()
 for fail in ['0','1']:
  calls=r/('calls'+fail);env={**os.environ,'PATH':str(r/'fakebin')+':'+os.environ['PATH'],'AUDIT_PROMPT':'test','AUDIT_CALLS':str(calls),'DEFAULT_EXIT':fail};p=subprocess.run(['bash',str(r/'scripts/audit.sh'),str(r/'model.gguf'),'fixture'],env=env,capture_output=True,text=True);executed=calls.read_text().splitlines();assert len(executed)==5;assert p.returncode==int(fail);results.append({'firstAuditExit':int(fail),'matrixExit':p.returncode,'executedAudits':5})
print(json.dumps(results,indent=2))
