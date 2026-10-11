"""Launch the declared replacement phase after all eight invalid hosts have terminated."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
previous = json.loads((root / 'public-qa-fleet.json').read_text())
ids = [s['instanceId'] for s in previous['shards']]
state = json.loads(subprocess.check_output(['aws', 'ec2', 'describe-instances', '--region', 'us-east-1', '--instance-ids', *ids]))
assert all(i['State']['Name'] == 'terminated' for r in state['Reservations'] for i in r['Instances'])
quota = json.loads(subprocess.check_output(['aws', 'service-quotas', 'get-service-quota', '--service-code', 'ec2', '--quota-code', 'L-1216C47A', '--region', 'us-east-1']))['Quota']['Value']
assert quota >= 192
payload_uri = 's3://models-qual-077051030817/payload/bonsai-public-qa-v2-50489734.tar.gz'
payload_hash = '1b4fcc0c5287721fa06ed4ed7598826ec880a98f0ed209ef908a53958224aa24'
fleet = {k: v for k, v in previous.items() if k != 'shards'}
fleet.update(createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), payloadUri=payload_uri,
    payloadSha256=payload_hash, harnessRevision='50489734', tokenizationMode='trusted-frozen-chatml-v2', shards=[])
receipt = root / 'public-qa-fleet-v2.json'
assert not receipt.exists(), 'never relaunch an existing fleet receipt'
try:
    for shard in range(8):
        uri = f's3://models-qual-077051030817/results/bonsai-public-qa-v2-50489734/shard-{shard}/'
        body = '#!/bin/bash\n' + f'PAYLOAD_URI={payload_uri}\nPAYLOAD_SHA256={payload_hash}\nRESULT_URI={uri}\nFIRST_CASE={shard * 25}\nCASE_COUNT=25\n' + (root / 'public-qa-worker.sh').read_text().split('\n', 1)[1]
        user_data = Path(f'/tmp/bonsai-public-qa-v2-worker-{shard}.sh')
        user_data.write_text(body)
        deadline = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=9000)).isoformat()
        command = list(previous['shards'][shard]['launchCommand'])
        command[command.index('--user-data') + 1] = 'file://' + str(user_data)
        command[command.index('--tag-specifications') + 1] = f'ResourceType=instance,Tags=[{{Key=Name,Value=bonsai-public-qa-v2-50489734-{shard}}},{{Key=project,Value=models-qualification}},{{Key=delete-after,Value={deadline}}}]'
        instance = json.loads(subprocess.check_output(command))['Instances'][0]
        fleet['shards'].append({'shard': shard, 'first': shard * 25, 'count': 25, 'resultUri': uri,
            'userDataPath': str(user_data), 'userDataSha256': hashlib.sha256(body.encode()).hexdigest(),
            'instanceId': instance['InstanceId'], 'deadline': deadline, 'launchCommand': command,
            'privateDnsName': instance['PrivateDnsName'], 'availabilityZone': instance['Placement']['AvailabilityZone']})
        receipt.write_text(json.dumps(fleet, indent=2) + '\n')
        print(shard, instance['InstanceId'], flush=True)
except BaseException:
    if fleet['shards']:
        subprocess.run(['aws', 'ec2', 'terminate-instances', '--region', 'us-east-1', '--instance-ids', *[s['instanceId'] for s in fleet['shards']]], check=True)
    raise
