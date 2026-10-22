# Reproducing the Laya oracle

Two scripts, one pinned environment. Neither is on the measured path: Java runs the model, and
these exist only because the runbook requires an external oracle and, for this decision head, the
reference implementation is the only one that exists.

```sh
cd experiments/laya-modern-bert
uv venv --python 3.12 .venv
. .venv/bin/activate
uv pip install "torch==2.14.1" "laya==0.4.13"

python oracle/oracle.py                      # writes oracle/REFERENCE.json
python oracle/intermediates.py has_invoice   # writes oracle/INTERMEDIATES-has_invoice.txt
```

Pins, and why each one:

| pin | reason |
| --- | --- |
| checkpoint revision `7b928d828b7b0e022f929d9bd2e44165aa270148` | the revision `ggml-org/Laya-GGUF` was converted from, per its `.src_sha`. The `laya` package's own `PINNED_REVISIONS` names a *different* revision, so the package default would produce an oracle for weights the artifact does not contain |
| `laya==0.4.13` | the version `REFERENCE.json` records |
| float32, CPU, no autocast | this is a correctness artifact; no timing is claimed from it, and the checkpoint's `amp_dtype: bf16` is its training setting, not this |

## This does not run on an x86_64 Mac, and that is a wheel fact, not a preference

Measured 2026-10-22, not assumed: `torch 2.14.1` publishes wheels for `macosx_14_0_arm64`,
`manylinux_2_28_aarch64`, `manylinux_2_28_x86_64` and `win_amd64` — and **no macOS x86_64 wheel at
all**. On this dev box (`uname -m` = `x86_64`) the newest installable torch is 2.2.2, the last
Intel-Mac build, and the `transformers` that `laya` pulls in requires torch >= 2.6. The failure is
explicit:

```
[transformers] Disabling PyTorch because PyTorch >= 2.6 is required but found 2.2.2
NameError: name 'nn' is not defined        # transformers/integrations/accelerate.py
```

So `laya` cannot build the model here at any pin, which is why `REFERENCE.json` records
`2.14.1+cu130`: it was produced on the Linux host, and so must anything that extends it. Run both
scripts on the VPS (`manylinux_2_28_x86_64`), not on the laptop. Attempting it here wastes a
checkpoint download and ends in the trace above.

Also pin `numpy<2`: the torch wheels are built against NumPy 1.x and import-warn loudly under
NumPy 2.

## What the artifacts are for

`REFERENCE.json` is the gate for `ModernBertDecisionForwardPassIntegrationTest`: per case, the token
ids, the option markers, the parallel-layout position and option ids, the raw per-option logits, the
temperature and the probabilities.

`INTERMEDIATES-<case>.txt` is the instrument for localising a discrepancy. Set
`ModernBertDecisionForwardPass.TRACE` to a list on the Java side and the two print the same
per-stage fingerprint — mean, standard deviation, L2 norm and first element of the hidden state —
so the first disagreeing stage can be read off instead of inferred from the final logit.
