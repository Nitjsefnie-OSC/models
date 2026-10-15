import collections,datetime,hashlib,json,pathlib,subprocess,time
root=pathlib.Path('/Users/briansam-bodden/Code/java-ai/projects/models');out=root/'benchmark-results/2026-10-11-bonsai-production';receipt=out/'catalog-baseline-fleet.json';assert not receipt.exists()
meta=json.loads(pathlib.Path('/tmp/bonsai-catalog-baseline-payload.json').read_text());worker=root/'scripts/fleet/smoke-worker.sh';worker_sha=hashlib.sha256(worker.read_bytes()).hexdigest();worker_uri='s3://models-qual-077051030817/payload/smoke-worker-b85cd653.sh'
subprocess.run(['aws','s3','cp',str(worker),worker_uri,'--only-show-errors'],check=True)
ids={line.split('\t')[0] for line in (root/'benchmark-results/2026-10-09-catalogue-smoke-0354/ab-arm-0.3.54.tsv').read_text().splitlines()};groups=collections.defaultdict(list)
for j in json.loads((out/'released-catalog-regression-jobs.json').read_text()):
 if j['id'] in ids:groups[j['wl']].append(j)
base=json.loads((out/'host.json').read_text())['launchCommand'];fleet={'modelsVersion':'0.3.58','maximumUsd':3,'maximumSecondsPerInstance':3600,'purpose':'Recheck six previous default smoke failures with released products','payload':meta,'workerSha256':worker_sha,'shards':[]};receipt.write_text(json.dumps(fleet,indent=2)+'\n')
quota=json.loads(subprocess.check_output(['aws','service-quotas','get-service-quota','--region','us-east-1','--service-code','ec2','--quota-code','L-1216C47A']))['Quota']['Value']
for index,(wl,jobs) in enumerate(sorted(groups.items())):
 shard=890+index;uri=f's3://models-qual-077051030817/results/shard-{shard}/'
 existing=json.loads(subprocess.check_output(['aws','s3api','list-objects-v2','--bucket','models-qual-077051030817','--prefix',f'results/shard-{shard}/','--max-keys','1']));assert existing.get('KeyCount',0)==0,'shard must be unused'
 path=out/f'catalog-baseline-shard-{shard}.json';path.write_text(json.dumps(jobs,indent=2)+'\n');subprocess.run(['aws','s3','cp',str(path),f's3://models-qual-077051030817/payload/smoke-shard-{shard}.json','--only-show-errors'],check=True)
 while True:
  reservations=json.loads(subprocess.check_output(['aws','ec2','describe-instances','--region','us-east-1','--filters','Name=instance-state-name,Values=running,pending,shutting-down,stopping']))['Reservations'];active=[i for r in reservations for i in r['Instances']]
  types=sorted({i['InstanceType'] for i in active});cores={}
  if types:cores={t['InstanceType']:t['VCpuInfo']['DefaultVCpus'] for t in json.loads(subprocess.check_output(['aws','ec2','describe-instance-types','--region','us-east-1','--instance-types',*types]))['InstanceTypes']}
  ours={s['instanceId'] for s in fleet['shards']}
  if sum(cores[i['InstanceType']] for i in active)+16<=quota and sum(i['InstanceId'] in ours for i in active)<3:break
  time.sleep(20)
 now=datetime.datetime.now(datetime.timezone.utc);deadline=(now+datetime.timedelta(seconds=3600)).isoformat()
 body=f'''#!/bin/bash
set -euo pipefail
mkdir -p /work/out
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
nohup bash -c 'sleep 3600; shutdown -h now' >/work/out/deadline.log 2>&1 &
finish() {{
 rc=$?
 if [ "$rc" -ne 0 ]; then echo "BOOTSTRAP_FAILED $rc" > /work/out/STATUS; fi
 if command -v aws >/dev/null; then aws s3 cp /work/out {uri} --recursive --only-show-errors || true; fi
 shutdown -h now
}}
trap finish EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip python3
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
aws s3 cp {worker_uri} /work/worker.sh --only-show-errors
printf '%s  /work/worker.sh\\n' {worker_sha} | sha256sum --check --status
export SHARD={shard} SMOKE_PAYLOAD={meta['payload']} SMOKE_PAYLOAD_SHA256={meta['sha256']}
export SMOKE_BACKEND_VERSION=models@0.3.58+{meta['revision']} DEADLINE_SECONDS=3000
bash /work/worker.sh
'''
 user_data=pathlib.Path(f'/tmp/bonsai-catalog-baseline-{shard}.sh');user_data.write_text(body)
 command=list(base);command[command.index('--user-data')+1]='file://'+str(user_data);command[command.index('--block-device-mappings')+1]=json.dumps([{'DeviceName':'/dev/sda1','Ebs':{'VolumeSize':50,'VolumeType':'gp3','DeleteOnTermination':True}}]);command[command.index('--tag-specifications')+1]=f'ResourceType=instance,Tags=[{{Key=Name,Value=bonsai-catalog-baseline-{shard}}},{{Key=project,Value=models-qualification}},{{Key=delete-after,Value={deadline}}}]'
 instance=json.loads(subprocess.check_output(command))['Instances'][0];fleet['shards'].append({'shard':shard,'workload':wl,'modelIds':[j['id'] for j in jobs],'instanceId':instance['InstanceId'],'deadline':deadline,'resultUri':uri,'userDataSha256':hashlib.sha256(body.encode()).hexdigest(),'launchCommand':command});receipt.write_text(json.dumps(fleet,indent=2)+'\n');print(shard,instance['InstanceId'],flush=True)
