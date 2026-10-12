#!/bin/bash
set -euo pipefail
export RESULT_URI=s3://models-qual-077051030817/results/activated-fixture-d2d0/
mkdir -p /work/out /opt/jdk
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
nohup bash -c 'sleep $(( 1791843807 - $(date +%s) )); shutdown -h now' >/work/out/deadline.log 2>&1 &
finish() {
 rc=$?
 if [ "$rc" -ne 0 ]; then echo "BOOTSTRAP_OR_RUN_FAILED $rc" > /work/out/STATUS; fi
 if command -v aws >/dev/null; then aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors || true; fi
 shutdown -h now
}
trap finish EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip python3 curl ca-certificates time
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
nohup bash -c 'while sleep 30; do aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors; done' >/work/out/upload.log 2>&1 &
cd /work
aws s3 cp s3://models-qual-077051030817/payload/activated-fixture-d2d0.tar.gz payload.tar.gz --only-show-errors
printf '%s  payload.tar.gz\n' 162ddbd3d0db4476e20f17c1fda1b7547b371e8855133278d22c59de6ac98b42 | sha256sum --check
 tar xzf payload.tar.gz
python3 - <<'CHECK'
import pathlib,json,hashlib
r=pathlib.Path('/work');m=json.loads((r/'manifest.json').read_text())
for name,sha in m['files'].items():assert hashlib.sha256((r/name).read_bytes()).hexdigest()==sha,name
CHECK
cp manifest.json out/manifest.json
curl -fsSL https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz -o /tmp/jdk.tgz
printf '%s  /tmp/jdk.tgz\n' 69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f | sha256sum --check
tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1
python3 - <<'DOWNLOAD'
import json,subprocess
m=json.load(open('model.json'));subprocess.run(['curl','-fL','--retry','3',m['downloadUri'],'-o','model.gguf'],check=True)
subprocess.run(['sha256sum','--check'],input=m['sha256']+'  model.gguf\n',text=True,check=True)
DOWNLOAD
/opt/jdk/bin/java -version 2>out/java-version.txt
hostname >out/hostname.txt
lscpu >out/lscpu.txt
sha256sum model.gguf adapter/* >out/model-adapter.sha256
 echo RUNNING_REAL_FIXTURES >out/STATUS
python3 run.py
