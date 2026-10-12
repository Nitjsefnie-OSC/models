"""Launch one isolated fixture worker with a40-minute/$1 cap and retained receipts."""
from pathlib import Path
import json,subprocess,datetime,hashlib
root=Path.cwd();out=root/'experiments/activated-fixture';meta=json.loads((out/'payload.json').read_text());receipt=out/'host.json';assert not receipt.exists()
def aws(*a):return json.loads(subprocess.check_output(['aws',*a,'--output','json']))
quota=aws('service-quotas','get-service-quota','--region','us-east-1','--service-code','ec2','--quota-code','L-1216C47A')['Quota']['Value']
active=aws('ec2','describe-instances','--region','us-east-1','--filters','Name=instance-state-name,Values=running,pending,shutting-down,stopping');hosts=[i for r in active['Reservations'] for i in r['Instances'] if i.get('InstanceLifecycle')!='spot'];types=sorted({i['InstanceType'] for i in hosts});cores={i['InstanceType']:i['VCpuInfo']['DefaultVCpus'] for i in aws('ec2','describe-instance-types','--region','us-east-1','--instance-types',*types)['InstanceTypes']};used=sum(cores[i['InstanceType']] for i in hosts);assert used+16<=quota,(used,quota)
now=datetime.datetime.now(datetime.timezone.utc);deadline=min(now+datetime.timedelta(minutes=40),datetime.datetime(2026,10,12,22,30,tzinfo=datetime.timezone.utc));assert (deadline-now).total_seconds()>=2100
body=f'''#!/bin/bash
set -euo pipefail
export RESULT_URI=s3://models-qual-077051030817/results/activated-fixture-d2d0/
mkdir -p /work/out /opt/jdk
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
nohup bash -c 'sleep $(( {int(deadline.timestamp())} - $(date +%s) )); shutdown -h now' >/work/out/deadline.log 2>&1 &
finish() {{
 rc=$?
 if [ "$rc" -ne 0 ]; then echo "BOOTSTRAP_OR_RUN_FAILED $rc" > /work/out/STATUS; fi
 if command -v aws >/dev/null; then aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors || true; fi
 shutdown -h now
}}
trap finish EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip python3 curl ca-certificates time
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
nohup bash -c 'while sleep 30; do aws s3 sync /work/out "$RESULT_URI" --only-show-errors; done' >/work/out/upload.log 2>&1 &
cd /work
aws s3 cp {meta['payloadUri']} payload.tar.gz --only-show-errors
printf '%s  payload.tar.gz\\n' {meta['payloadSha256']} | sha256sum --check
 tar xzf payload.tar.gz
python3 - <<'CHECK'
import pathlib,json,hashlib
r=pathlib.Path('/work');m=json.loads((r/'manifest.json').read_text())
for name,sha in m['files'].items():assert hashlib.sha256((r/name).read_bytes()).hexdigest()==sha,name
CHECK
cp manifest.json out/manifest.json
curl -fsSL https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz -o /tmp/jdk.tgz
printf '%s  /tmp/jdk.tgz\\n' 69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f | sha256sum --check
tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1
python3 - <<'DOWNLOAD'
import json,subprocess
m=json.load(open('model.json'));subprocess.run(['curl','-fL','--retry','3',m['downloadUri'],'-o','model.gguf'],check=True)
subprocess.run(['sha256sum','--check'],input=m['sha256']+'  model.gguf\\n',text=True,check=True)
DOWNLOAD
/opt/jdk/bin/java -version 2>out/java-version.txt
hostname >out/hostname.txt
lscpu >out/lscpu.txt
sha256sum model.gguf adapter/* >out/model-adapter.sha256
 echo RUNNING_REAL_FIXTURES >out/STATUS
python3 run.py
'''
worker=out/'worker.sh';worker.write_text(body);assert len(body.encode())<16384
fleet=json.loads((root/'benchmark-results/2026-10-12-bonsai-final/fleet.json').read_text());command=list(next(s['launchCommand'] for s in fleet['shards'] if s['shard']==3));command[command.index('--user-data')+1]='file://'+str(worker);command[command.index('--tag-specifications')+1]=f'ResourceType=instance,Tags=[{{Key=Name,Value=activated-fixture-d2d0}},{{Key=project,Value=models-qualification}},{{Key=delete-after,Value={deadline.isoformat()}}}]'
i=json.loads(subprocess.check_output(command))['Instances'][0]
receipt.write_text(json.dumps({'instanceId':i['InstanceId'],'region':'us-east-1','privateDnsName':i['PrivateDnsName'],'deadline':deadline.isoformat(),'maximumUsd':1,'maximumSeconds':2400,'usedVcpusBeforeLaunch':used,'resultUri':'s3://models-qual-077051030817/results/activated-fixture-d2d0/','command':command,'workerSha256':hashlib.sha256(body.encode()).hexdigest(),'payloadSha256':meta['payloadSha256']},indent=2)+'\n');print(i['InstanceId'])
