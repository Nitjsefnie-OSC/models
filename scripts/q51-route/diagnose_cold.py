"""Capture cold allocation separately from the frozen route performance cohort."""
import argparse,importlib.util,json,pathlib,subprocess
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('route',HERE/'run.py');route=importlib.util.module_from_spec(spec);spec.loader.exec_module(route)
def main():
 p=argparse.ArgumentParser();p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--arm',choices=['hybrid','native'],required=True);p.add_argument('--profile',action='store_true');a=p.parse_args();out=a.out.resolve()
 state=json.loads((out/'route-prepared.json').read_text());tasks=json.loads(pathlib.Path(state['tasks']).read_text());selected=[]
 for dataset in ['jevbench','snips']:selected.append(next(t for t in tasks if t['dataset']==dataset))
 target=out/'cold-tasks.json';target.write_text(json.dumps(selected)+'\n')
 command=route.command(out,a.arm,'cohort',0);index=command.index(state['tasks']);command[index]=str(target)
 label=f'cold-{a.arm}-'+('jfr' if a.profile else 'plain');jfr=out/(label+'.jfr')
 if a.profile:
  index=command.index('-cp');command[index:index]=['-XX:FlightRecorderOptions=stackdepth=128',f'-XX:StartFlightRecording=filename={jfr},settings=profile,dumponexit=true']
 route.reference.run(out,label,command,900)
 rows=[r for r in route.rows(out/(label+'.stdout')) if r.get('kind')=='request'];assert len(rows)==2
 signature=[(r['id'],r['inputTokens'],r['fullLogitSha256']) for r in rows]
 reference=out/'cold-signature.json'
 if reference.exists():assert json.loads(reference.read_text())==json.loads(json.dumps(signature)),'instrumentation or route changed full logits'
 else:route.save(reference,signature)
 if a.profile:
  with (out/(label+'-allocations.json')).open('w') as f:subprocess.run(['jfr','print','--json','--events','jdk.ObjectAllocationSample',str(jfr)],stdout=f,check=True)
 route.save(out/(label+'-scope.json'),{'scope':'Allocation attribution only. No latency/resource qualification from this diagnostic; instrumented elapsed times excluded.','arm':a.arm,'profiled':a.profile,'requests':2,'fullLogitSnapshots':18,'sameTokensAndFullLogits':True})
if __name__=='__main__':main()
