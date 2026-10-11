#!/bin/bash
set -euo pipefail
: "${PAYLOAD_URI:?}" "${PAYLOAD_SHA256:?}" "${RESULT_URI:?}"
mkdir -p /work/out /opt/jdk
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
nohup bash -c 'sleep 5400; shutdown -h now' >/work/out/deadline.log 2>&1 &
finish() {
  rc=$?
  if [ "$rc" -ne 0 ]; then echo "BOOTSTRAP_OR_RUN_FAILED $rc" > /work/out/STATUS; fi
  if command -v aws >/dev/null; then
    aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors || true
  fi
  shutdown -h now
}
trap finish EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip python3
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
cd /work
aws s3 cp "$PAYLOAD_URI" payload.tar.gz --only-show-errors
printf '%s  payload.tar.gz\n' "$PAYLOAD_SHA256" | sha256sum --check --status
tar xzf payload.tar.gz
sha256sum --check --status inputs.sha256
cp inputs.sha256 out/inputs.sha256
cp arm/provenance.json out/provenance.json
curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/aarch64/jdk/hotspot/normal/eclipse -o /tmp/jdk.tgz
sha256sum /tmp/jdk.tgz > out/jdk.sha256
tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1
/opt/jdk/bin/java -version 2>out/java-version.txt
curl -fL --retry 3 https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PTQ1_0.gguf -o bonsai2.gguf
printf '%s  bonsai2.gguf\n' 53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3 | sha256sum --check
lscpu > out/lscpu.txt
uname -a > out/uname.txt
hostname > out/hostname.txt
ps aux > out/processes-before.txt
echo RUNNING_ARM_Q8 > out/STATUS
export RESULT_URI
python3 arm/run-arm-q8.py
