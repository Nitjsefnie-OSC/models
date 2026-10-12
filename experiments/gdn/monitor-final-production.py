"""Bounded read-only collection of final owned GDN phases and termination receipts."""
import concurrent.futures,datetime,json,pathlib,subprocess,time
root=pathlib.Path(__file__).resolve().parents[2];out=root/'benchmark-results/2026-10-11-gdn-proof';seen={};deadline=datetime.datetime.fromisoformat('2026-10-12T22:31:00+00:00')
hosts=json.loads((out/'hosts-production-final-v11.json').read_text())['hosts']
hosts += [h for h in json.loads((out/'hosts-production-final-v9.json').read_text())['hosts']if h.get('shard')=='arm-correctness']
def collect(h):
 phase=h['resultUri'].split('/results/')[1].split('/')[0].removeprefix('gdn-proof-');dest=out/('remote-'+phase)/h['shard']
 subprocess.run(['aws','s3','sync',h['resultUri'],str(dest),'--only-show-errors'],check=True)
 subprocess.run(['aws','s3','cp',h['resultUri']+'STATUS',str(dest/'STATUS'),'--only-show-errors'],check=True)
 return h['instanceId'],(dest/'STATUS').read_text().strip()
while datetime.datetime.now(datetime.timezone.utc)<deadline:
 with concurrent.futures.ThreadPoolExecutor(max_workers=6)as ex:
  for id,status in ex.map(collect,hosts):
   if seen.get(id)!=status:print(datetime.datetime.now(datetime.timezone.utc).isoformat(),id,status,flush=True);seen[id]=status
 raw=json.loads(subprocess.check_output(['aws','ec2','describe-instances','--region','us-east-1','--instance-ids',*[h['instanceId']for h in hosts],'--output','json']))
 states=[]
 for reservation in raw['Reservations']:
  for item in reservation['Instances']:
   state={'instanceId':item['InstanceId'],'state':item['State']['Name'],'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'launchTime':item['LaunchTime'],'transitionReason':item.get('StateTransitionReason')};states.append(state)
   if state['state']=='terminated':
    p=out/'termination-receipts'/f"{state['instanceId']}.json";p.parent.mkdir(exist_ok=True)
    if not p.exists():p.write_text(json.dumps(state,indent=2)+'\n');print('TERMINATED',state['instanceId'],flush=True)
 (out/'production-final-v11-monitor-fleet.json').write_text(json.dumps(states,indent=2)+'\n')
 for script,args in [('analyze-production-refresh.py',[out/'remote-production-final-v11',out/'production-final-v11-analysis.json','--partial']),('analyze-production-refresh-correctness.py',[out/'remote-production-final-v11',out/'remote-production-final-v9',out/'production-final-v11-correctness.json'])]:
  subprocess.run(['python3',str(root/'experiments/gdn'/script),*[str(x)for x in args]],check=True,stdout=subprocess.DEVNULL)
 if all(s['state']=='terminated'for s in states):print('ALL_OWNED_FINAL_HOSTS_TERMINATED',flush=True);break
 time.sleep(45)
