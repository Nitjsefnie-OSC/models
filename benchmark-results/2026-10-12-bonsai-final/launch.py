"""Launch only missing shards while capacity remains; immutable payload and owned receipts."""
import datetime,hashlib,json,pathlib,subprocess
out=pathlib.Path(__file__).resolve().parent
root=out.parents[1]
meta=json.loads((out/'payload.json').read_text());receipt=out/'fleet.json'
old=json.loads((root/'benchmark-results/2026-10-11-bonsai-production/public-qa-fleet-v2.json').read_text())
def aws(*a):return json.loads(subprocess.check_output(['aws',*a,'--output','json']))
quota=aws('service-quotas','get-service-quota','--region','us-east-1','--service-code','ec2','--quota-code','L-1216C47A')['Quota']['Value']
filters=[{'Type':'TERM_MATCH','Field':k,'Value':v} for k,v in {'instanceType':'m6a.4xlarge','location':'US East (N. Virginia)','operatingSystem':'Linux','tenancy':'Shared','preInstalledSw':'NA','capacitystatus':'Used'}.items()]
prices=aws('pricing','get-products','--region','us-east-1','--service-code','AmazonEC2','--filters',json.dumps(filters))['PriceList']
rates={float(d['pricePerUnit']['USD']) for p in prices for t in json.loads(p)['terms']['OnDemand'].values() for d in t['priceDimensions'].values() if d['unit']=='Hrs'}
assert len(rates)==1;rate=rates.pop();assert rate*8*1.5+.5<=10
fleet=json.loads(receipt.read_text()) if receipt.exists() else {'payload':meta['payloadUri'],'payloadSha256':meta['payloadSha256'],'maximumSecondsPerHost':5400,'maximumCombinedUsd':10,'hourlyRate':rate,'shards':[]}
assert fleet['payloadSha256']==meta['payloadSha256']
base=(root/'benchmark-results/2026-10-11-bonsai-production/public-qa-worker.sh').read_text()
base=base.replace("sleep 9000; shutdown -h now", "sleep 5400; shutdown -h now")
base=base.replace('mkdir -p /work/out /opt/jdk', 'export RESULT_URI FIRST_CASE CASE_COUNT\nmkdir -p /work/out /opt/jdk')
base=base.replace('unzip python3 libgomp1','unzip python3 libgomp1 build-essential curl ca-certificates time')
jdk=json.loads((root.parent/'models-gdn-proof/experiments/gdn/generated/payload-production-final-v8/provenance.json').read_text())['jdk']['x64']
base=base.replace('https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse',jdk['url'])
base=base.replace('sha256sum /tmp/jdk.tgz > out/jdk.sha256',f"printf '%s  /tmp/jdk.tgz\\n' {jdk['sha256']} | sha256sum --check\nsha256sum /tmp/jdk.tgz > out/jdk.sha256")
base=base.replace('cp inputs.sha256 out/inputs.sha256', '''cp inputs.sha256 out/inputs.sha256
aws s3 cp out "$RESULT_URI" --recursive --only-show-errors
nohup bash -c 'while sleep 60; do aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors; done' >out/upload.log 2>&1 &
curl -fsSL https://sh.rustup.rs -o /tmp/rustup.sh
sh /tmp/rustup.sh -y --profile minimal --default-toolchain 1.96.0
/root/.cargo/bin/cargo build --locked --release --manifest-path /work/rust/Cargo.toml >out/native-build.log 2>&1
cp /work/rust/target/release/libjmodels_kernels.so /work/qa/libjmodels_kernels.so
/root/.cargo/bin/rustc --version >out/rust-version.txt''')
for shard in range(8):
 if any(s['shard']==shard for s in fleet['shards']):continue
 active=aws('ec2','describe-instances','--region','us-east-1','--filters','Name=instance-state-name,Values=running,pending,shutting-down,stopping')['Reservations']
 instances=[i for r in active for i in r['Instances']];types=sorted({i['InstanceType'] for i in instances})
 cores={t['InstanceType']:t['VCpuInfo']['DefaultVCpus'] for t in aws('ec2','describe-instance-types','--region','us-east-1','--instance-types',*types)['InstanceTypes']} if types else {}
 used=sum(cores[i['InstanceType']] for i in instances)
 if used+16>min(quota,192):print('Capacity occupied:',used,flush=True);break
 deadline=datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=5400)
 assert deadline<=datetime.datetime(2026,10,12,22,32,tzinfo=datetime.timezone.utc), 'no shard beyond user window'
 uri=f"s3://models-qual-077051030817/results/{meta['runName']}/shard-{shard}/"
 body='#!/bin/bash\n'+f"PAYLOAD_URI={meta['payloadUri']}\nPAYLOAD_SHA256={meta['payloadSha256']}\nRESULT_URI={uri}\nFIRST_CASE={shard*25}\nCASE_COUNT=25\n"+base.split('\n',1)[1]
 userdata=out/f'shard-{shard}.sh';userdata.write_text(body);assert len(body.encode())<16384
 command=list(old['shards'][shard]['launchCommand']);command[command.index('--user-data')+1]='file://'+str(userdata)
 command[command.index('--tag-specifications')+1]=f"ResourceType=instance,Tags=[{{Key=Name,Value={meta['runName']}-{shard}}},{{Key=project,Value=models-qualification}},{{Key=delete-after,Value={deadline.isoformat()}}}]"
 instance=json.loads(subprocess.check_output(command))['Instances'][0]
 fleet['shards'].append({'shard':shard,'first':shard*25,'count':25,'resultUri':uri,'instanceId':instance['InstanceId'],'deadline':deadline.isoformat(),'launchCommand':command,'userDataSha256':hashlib.sha256(body.encode()).hexdigest(),'usedVcpusBeforeLaunch':used})
 receipt.write_text(json.dumps(fleet,indent=2)+'\n');print(shard,instance['InstanceId'],flush=True)
