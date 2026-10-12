#!/bin/bash
PAYLOAD_URI=s3://models-qual-077051030817/payload/bonsai-final-qa-2163f9eb.tar.gz
PAYLOAD_SHA256=d522b1bea147b4b687ed23c5e8180af319d8adec04cca2b3e0cb5e5fb2428f88
RESULT_URI=s3://models-qual-077051030817/results/bonsai-final-qa-2163f9eb/shard-6/
FIRST_CASE=150
CASE_COUNT=25
# Bootstrap supplies immutable payload, shard interval and private result URI.
set -euo pipefail
: "${PAYLOAD_URI:?}" "${PAYLOAD_SHA256:?}" "${RESULT_URI:?}" "${FIRST_CASE:?}" "${CASE_COUNT:?}"
export RESULT_URI FIRST_CASE CASE_COUNT
mkdir -p /work/out /opt/jdk
exec > >(tee -a /work/out/bootstrap.log /dev/console) 2>&1
nohup bash -c 'sleep $(( 1791842631 - $(date +%s) )); shutdown -h now' >/work/out/deadline.log 2>&1 &
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
apt-get install -y -qq unzip python3 libgomp1 build-essential curl ca-certificates time
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
unzip -qo /tmp/awscli.zip -d /tmp
/tmp/aws/install
cd /work
aws s3 cp "$PAYLOAD_URI" payload.tar.gz --only-show-errors
printf '%s  payload.tar.gz\n' "$PAYLOAD_SHA256" | sha256sum --check --status
tar xzf payload.tar.gz
sha256sum --check --status inputs.sha256
cp inputs.sha256 out/inputs.sha256
aws s3 cp out "$RESULT_URI" --recursive --only-show-errors
nohup bash -c 'while sleep 60; do aws s3 cp /work/out "$RESULT_URI" --recursive --only-show-errors; done' >out/upload.log 2>&1 &
curl -fsSL https://sh.rustup.rs -o /tmp/rustup.sh
sh /tmp/rustup.sh -y --profile minimal --default-toolchain 1.96.0
/root/.cargo/bin/cargo build --locked --release --manifest-path /work/rust/Cargo.toml >out/native-build.log 2>&1
cp /work/rust/target/release/libjmodels_kernels.so /work/qa/libjmodels_kernels.so
/root/.cargo/bin/rustc --version >out/rust-version.txt
cp qa/payload-provenance.json out/payload-provenance.json
cp prism/build/CMakeCache.txt out/reference-CMakeCache.txt
curl -fsSL https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz -o /tmp/jdk.tgz
printf '%s  /tmp/jdk.tgz\n' 69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f | sha256sum --check
sha256sum /tmp/jdk.tgz > out/jdk.sha256
tar xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1
/opt/jdk/bin/java -version 2>out/java-version.txt
curl -fL --retry 3 https://huggingface.co/prism-ml/Ternary-Bonsai-2-27B-gguf/resolve/b072e1d3b35a0a630cece372c2127528e0994386/Ternary-Bonsai-2-27B-PTQ1_0.gguf -o bonsai2.gguf
printf '%s  bonsai2.gguf\n' 53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3 | sha256sum --check
stat -c '%s' bonsai2.gguf > out/model-bytes.txt
lscpu > out/lscpu.txt
uname -a > out/uname.txt
hostname > out/hostname.txt
ps aux > out/processes-before.txt
ldd prism/build/bin/llama-server > out/reference-ldd.txt
prism/build/bin/llama-server --version > out/reference-version.txt 2>&1
sha256sum /work/qa/libjmodels_kernels.so /work/prism/build/bin/llama-server > out/native.sha256
echo RUNNING_PAIRED_PUBLIC_QA > out/STATUS
aws s3 cp out "$RESULT_URI" --recursive --only-show-errors
export RESULT_URI FIRST_CASE CASE_COUNT
python3 qa/run-public-qa-shard.py
