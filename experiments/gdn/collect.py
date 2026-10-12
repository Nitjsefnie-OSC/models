"""Collect evidence and ownership-scoped fleet receipts; never changes remote execution."""
import datetime,json,pathlib,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2]
out=root/'benchmark-results/2026-10-11-gdn-proof'
hosts=[];synced=set()
excluded={arg.split('=',1)[1] for arg in sys.argv if arg.startswith('--exclude-phase=')}
for receipt in sorted(out.glob('hosts*.json')):
    for host in json.loads(receipt.read_text())['hosts']:
        host=dict(host,receipt=receipt.name);hosts.append(host)
        phase=host['resultUri'].split('/results/')[1].split('/')[0]
        if phase.removeprefix('gdn-proof-') in excluded:continue
        folder={'gdn-proof-v1':'remote','gdn-proof-jmh-v1':'remote-jmh','gdn-proof-trace-v1':'remote-trace'}.get(phase,'remote-'+phase.removeprefix('gdn-proof-'))
        if '--active-only' in sys.argv and (out/'termination-receipts'/f"{host['instanceId']}.json").exists():continue
        destination=out/folder/host.get('shard',host['arch']);destination.mkdir(parents=True,exist_ok=True)
        if '--status-only' not in sys.argv:
            subprocess.run(['aws','s3','sync',host['resultUri'],str(destination),'--only-show-errors'],check=True)
            synced.add(host['instanceId'])
        # Status transitions can have equal byte lengths. Fetch explicitly so a prior local
        # timestamp cannot leave the displayed phase behind the current remote status.
        status=destination/'STATUS'
        if status.exists() or '--status-only' in sys.argv:
            result=subprocess.run(['aws','s3','cp',host['resultUri']+'STATUS',str(status),'--only-show-errors'],capture_output=True,text=True)
            if result.returncode and '(404)' not in result.stderr:
                raise RuntimeError(result.stderr)
            if result.returncode:print(host['instanceId'],'status not uploaded yet',flush=True)
instances=json.loads(subprocess.check_output(['aws','ec2','describe-instances','--region','us-east-1','--instance-ids',*[h['instanceId'] for h in hosts],'--output','json']))
now=datetime.datetime.now(datetime.timezone.utc).isoformat();states=[]
for reservation in instances['Reservations']:
    for i in reservation['Instances']:
        state={'instanceId':i['InstanceId'],'state':i['State']['Name'],'launchTime':i['LaunchTime'],
               'transitionReason':i.get('StateTransitionReason'),'observedAt':now}
        states.append(state)
        if state['state']=='terminated' and state['instanceId'] in synced:
            target=out/'termination-receipts'/f"{i['InstanceId']}.json";target.parent.mkdir(exist_ok=True)
            if not target.exists():target.write_text(json.dumps(state,indent=2)+'\n')
(out/'fleet-status.json').write_text(json.dumps(states,indent=2)+'\n')
for s in states:print(s['instanceId'],s['state'])
