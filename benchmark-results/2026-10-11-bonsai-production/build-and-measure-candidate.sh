#!/bin/bash
set -euo pipefail
test "$(cat /work/out/STATUS)" = BASELINE_COMPLETE
mkdir -p /work/candidate-out /work/candidate-src /work/candidate/lib
exec > >(tee /work/candidate-out/build.log) 2>&1
cd /work
tar xzf /home/ubuntu/bonsai-candidate-1b7f3b37.tar.gz
python3 - <<'PY'
import hashlib, json
from pathlib import Path
p=Path('/work/candidate-inputs'); receipt=json.loads((p/'candidate-inputs.json').read_text())
for name,sha in receipt['files'].items():
    assert hashlib.sha256((p/name).read_bytes()).hexdigest()==sha,name
PY
tar xf candidate-inputs/source.tar -C candidate-src
cp candidate-inputs/candidate-inputs.json candidate-out/
cp payload/lib/*.jar candidate/lib/
cp candidate-inputs/backend-native-0.3.58.jar candidate/lib/
export CARGO_HOME=/work/cargo RUSTUP_HOME=/work/rustup
curl -fsSL https://sh.rustup.rs -o /work/rustup-init.sh
sh /work/rustup-init.sh -y --no-modify-path --default-toolchain 1.96.0 --profile minimal
export PATH=/work/cargo/bin:/opt/jdk/bin:$PATH
cd /work/candidate-src
cargo build --release --manifest-path backend-native/src/main/rust/model-kernels/Cargo.toml
sha256sum backend-native/src/main/rust/model-kernels/target/release/libjmodels_kernels.so > /work/candidate-out/native-sha256.txt
cargo --version > /work/candidate-out/rust-version.txt
javac --add-modules jdk.incubator.vector -cp '/work/candidate/lib/*' benchmark-results/2026-10-11-bonsai-production/PtqProjectionBenchmark.java
ps aux > /work/candidate-out/processes-before.txt
export RESULT_URI=s3://models-qual-077051030817/results/bonsai-production-20261011T191541Z/candidate-1b7f3b37/
python3 /work/candidate-inputs/run-candidate.py
