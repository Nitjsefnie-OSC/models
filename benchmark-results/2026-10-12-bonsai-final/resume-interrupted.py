"""Rerun both arms for cases lost to Spot interruption, on bounded On-Demand hosts."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parent
fleet_path = root / 'fleet.json'
fleet = json.loads(fleet_path.read_text())

def aws(*args):
    return json.loads(subprocess.check_output(['aws', *args, '--output', 'json']))

quota = aws('service-quotas', 'get-service-quota', '--region', 'us-east-1',
            '--service-code', 'ec2', '--quota-code', 'L-1216C47A')['Quota']['Value']
for number, original, first, count in [(8, 0, 10, 15), (9, 2, 66, 9)]:
    if any(s['shard'] == number for s in fleet['shards']):
        continue
    state = aws('ec2', 'describe-instances', '--region', 'us-east-1', '--instance-ids',
                next(s['instanceId'] for s in fleet['shards'] if s['shard'] == original))
    instance = state['Reservations'][0]['Instances'][0]
    assert instance['State']['Name'] == 'terminated'
    assert instance['StateReason']['Code'] == 'Server.SpotInstanceTermination'
    active = aws('ec2', 'describe-instances', '--region', 'us-east-1', '--filters',
                 'Name=instance-state-name,Values=running,pending,shutting-down,stopping')
    hosts = [i for r in active['Reservations'] for i in r['Instances']
             if i.get('InstanceLifecycle') != 'spot']
    types = sorted({i['InstanceType'] for i in hosts})
    cores = {t['InstanceType']: t['VCpuInfo']['DefaultVCpus'] for t in
             aws('ec2', 'describe-instance-types', '--region', 'us-east-1',
                 '--instance-types', *types)['InstanceTypes']} if types else {}
    used = sum(cores[i['InstanceType']] for i in hosts)
    if used + 16 > quota:
        print('No On-Demand capacity:', used, flush=True)
        break
    now = datetime.datetime.now(datetime.timezone.utc)
    deadline = min(now + datetime.timedelta(minutes=55),
                   datetime.datetime(2026, 10, 12, 22, 30, tzinfo=datetime.timezone.utc))
    assert (deadline - now).total_seconds() >= count * 180 + 180, 'insufficient bounded runtime'
    # Original eight 90-minute reservations plus both 55-minute recoveries cost < $10
    # after crediting only the two already verified complete workers' unused 30 minutes.
    worst_compute_usd = (8 * 1.5 + 2 * 55 / 60 - 2 * .5) * fleet['hourlyRate']
    assert worst_compute_usd + .5 < fleet['maximumCombinedUsd']
    uri = fleet['shards'][0]['resultUri'].rsplit('/shard-', 1)[0] + f'/shard-{number}/'
    body = (root / 'shard-3.sh').read_text()
    body = re.sub(r'^RESULT_URI=.*$', f'RESULT_URI={uri}', body, flags=re.M)
    body = re.sub(r'^FIRST_CASE=.*$', f'FIRST_CASE={first}', body, flags=re.M)
    body = re.sub(r'^CASE_COUNT=.*$', f'CASE_COUNT={count}', body, flags=re.M)
    body = re.sub(r'sleep \$\(\( \d+ - \$\(date \+%s\) \)\)',
                  f'sleep $(( {int(deadline.timestamp())} - $(date +%s) ))', body)
    user_data = root / f'shard-{number}.sh'
    user_data.write_text(body)
    command = list(next(s['launchCommand'] for s in fleet['shards'] if s['shard'] == 3))
    command[command.index('--user-data') + 1] = 'file://' + str(user_data)
    command[command.index('--tag-specifications') + 1] = (
        f'ResourceType=instance,Tags=[{{Key=Name,Value=bonsai-final-qa-recovery-{number}}},'
        f'{{Key=project,Value=models-qualification}},{{Key=delete-after,Value={deadline.isoformat()}}}]')
    launched = json.loads(subprocess.check_output(command))['Instances'][0]
    fleet['shards'].append({'shard': number, 'region': 'us-east-1', 'market': 'ondemand',
        'first': first, 'count': count, 'resultUri': uri, 'instanceId': launched['InstanceId'],
        'privateDnsName': launched['PrivateDnsName'], 'deadline': deadline.isoformat(),
        'launchCommand': command, 'userDataSha256': hashlib.sha256(body.encode()).hexdigest(),
        'usedVcpusBeforeLaunch': used, 'replacesUnpairedCasesFromShard': original,
        'maximumAdditionalComputeUsd': 2 * 55 / 60 * fleet['hourlyRate']})
    fleet_path.write_text(json.dumps(fleet, indent=2) + '\n')
    print(number, launched['InstanceId'], flush=True)
