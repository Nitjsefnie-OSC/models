# Initialize the shared Q5_1 decoder only when needed

Source fix `9027551b` retains a shared stateless decoder but moves construction into a lazy
holder. The prior per-row allocation fix accidentally initialized that decoder's Vector API
constants even while loading models with no Q5_1 tensors. The fix changes initialization timing,
not arithmetic, supported formats, or inference routing.

Separate class-load diagnostic processes prove the distinction while loading the real Qwen3.5
0.8B fixture: the prior artifact loads `Q5_1Dequantizer`; the fixed artifact loads neither the
decoder nor its holder. Full trace hashes and matching lines are in `class-load-proof.json`.
The two diagnostic processes are excluded from the timing experiment.

The immutable worker then ran 100 counterbalanced pairs of fresh JVM model loads on the same
x86 host. Both arms use final Models `0bc5de31`, Vectors `ae337eeb`, identical native binaries,
model, and JVM settings. The fixed arm replaces only `GgufTensorValues` and adds its holder class.
All 200 loads succeeded. Fixed/prior model-load time is **1.00074**, 95% process-block bootstrap
interval **[0.99649,1.00519]**. This stays within the 1% startup envelope, but **does not explain
or remove** the separate final/published startup regression recorded in the activation startup
experiment. Avoiding unnecessary initialization and proving a startup speedup are different claims.

The source passed 32 decoder and exact parallel projection tests with no skips, plus the tensor
format access smoke. The final Q5-specific follow-up exercises the lazy holder through real Q5_1
weights. Existing frozen whole-model measurements retain their original source identities.

`summary.json`, `loads.jsonl`, and the worker/host/payload receipts retain this result. Raw stdout,
stderr, class-load traces, commands, and version receipts are stored under
`s3://models-qual-077051030817/results/activation-startup-lazy-v2/x86/`; their hashes are recorded
here. The worker had a 15-minute hard cap and was verified terminated after successful completion.
An earlier bootstrap-only attempt used the wrong S3 payload prefix, failed before any test, and
was retained separately; the correction used the existing worker IAM policy without broadening it.
