#!/bin/bash
# Bounded single-host experiment. Caller prepends the immutable payload URL/digest.
set -euo pipefail
: "${PAYLOAD_URI:?}" "${PAYLOAD_SHA256:?}" "${RESULT_URI:?}"
mkdir -p /work/out /opt/jdk
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
# The instance was created with instance-initiated-shutdown-behavior=terminate.
nohup bash -c 'sleep 14400; shutdown -h now' >/work/out/deadline.log 2>&1 &
finish() {
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "SETUP_OR_BASELINE_FAILED $rc" > /work/out/STATUS
    aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors || true
    shutdown -h now
  fi
}
trap finish EXIT
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq unzip python3 git cmake build-essential libcurl4-openssl-dev
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse -o /tmp/jdk.tgz
tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1
/opt/jdk/bin/java -version
cd /work
aws s3 cp "$PAYLOAD_URI" payload.tar.gz --only-show-errors
printf '%s  payload.tar.gz\n' "$PAYLOAD_SHA256" | sha256sum --check --status
tar xzf payload.tar.gz
cp payload/classpath.json out/classpath.json
git init prism
git -C prism remote add origin https://github.com/PrismML-Eng/llama.cpp.git
git -C prism fetch --depth 1 origin e8fd209c975c80aa7fb36a8423571ff3b1b7e955
git -C prism checkout --detach FETCH_HEAD
cmake -S prism -B prism/build -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=OFF -DGGML_VULKAN=OFF -DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF
cmake --build prism/build --target llama-server -j 16
cp prism/build/CMakeCache.txt out/reference-CMakeCache.txt
curl -fL --retry 3 https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PTQ1_0.gguf -o bonsai2.gguf
printf '%s  bonsai2.gguf\n' 53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3 | sha256sum --check
stat -c '%s' bonsai2.gguf > out/model-bytes.txt
lscpu > out/lscpu.txt
uname -a > out/uname.txt
ps aux > out/processes-before.txt
echo BASELINE_RUNNING > out/STATUS
aws s3 cp out "$RESULT_URI" --recursive --only-show-errors
export RESULT_URI
python3 payload/run-baseline.py
# Keep this same host available for the paired follow-up until its fixed deadline.
echo 'Baseline complete; fixed four-hour termination deadline remains active.'
