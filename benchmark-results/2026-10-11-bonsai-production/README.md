# Bonsai production evidence

Qualification is **pending**. This directory retains source experiments, rejected optimizations,
real-weight checks and the frozen external-QA protocol. It does not claim a new release or marker.

The exact artifact is `prism-ml/Ternary-Bonsai-2-27B-gguf`, revision
`b072e1d3b35a0a630cece372c2127528e0994386`, file
`Ternary-Bonsai-2-27B-PTQ1_0.gguf`, 5,946,648,928 bytes, SHA-256
`53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3`.
The publisher reference is `PrismML-Eng/llama.cpp` at
`e8fd209c975c80aa7fb36a8423571ff3b1b7e955`, CPU only. It is not Ollama.

## Measured kernel and whole-model experiments

One isolated m6a.4xlarge, 16 logical workers unless stated otherwise. Median decode rates are
from three measured short-prompt trials after one warmup, with context 512 and 16 output tokens.
They describe this host and workload, not general production latency. Raw commands, classpath
hashes, output and memory records live beside each result.

| Experiment | Decode tokens/s | Decision |
| --- | ---: | --- |
| Released Java float-activation baseline | 1.364 | Baseline |
| First native float prototype, `1b7f3b37` | 1.260 | Rejected |
| Inlined float tail, `52abf966` | 1.734 | Superseded |
| Row pairs, `2d69fe55` | 1.610 | Rejected |
| Compact trit lookup, `9d49e9e1` | 1.440 | Rejected |
| Sixteen-bit trits and batched prompts, `0383af38` | 1.844 | Best float prototype |
| Same float route, eight pinned physical workers | 1.440 | Rejected |
| Split multiply/add, `6788c475` | 1.757 | Rejected |
| Native PTQ/Q8, `06c75314` | 2.445 | Current numerical contract |
| Paired publisher reference for the Q8 run | 2.625 | Measured comparator |

The initial baseline reference used context 2048 against Models context 512; that exploratory
pair is explicitly excluded from admission. Subsequent short-probe pairs match context 512.
The Q8 run gives a 0.931 decode ratio, but does not replace the paired RAG qualification.

Q8 is a different activation contract from the earlier float route. The production Java fallback
was aligned at `8b5c0b29`; independent Java-oracle and Rust scalar/SIMD tests require bit equality.
The real-weight tests check session isolation, the publisher's short continuation and exact
full-vocabulary sequential/batched parity. See `q8-validation-provenance.json` and its XML files.

`PtqProjectionBenchmark.java` is the historical harness for the archived payloads. Its Java arm
uses each payload's `TensorOps`; the archived comparisons labeled float use the recorded older
Java JAR. Do not substitute a newer Q8 Java JAR and retain the historical float label.

The separate Java Q8 scalar/SIMD pair is complete in `java-q8-paired/`: median decode improves
from 0.133 to 0.575 tokens/s and prefill from 0.142 to 0.653 tokens/s. All three output hashes
match, and actual projection tensors pass bit-exact Java/native and independent-oracle checks.
Both rates exceed the frozen 5% improvement threshold, so the SIMD implementation is retained.
These are pure-Java fallback rates, separate from the faster native route above.
`java-vector-real-fixture.xml` also records the local real-weight continuation/session test.

## Task quality and publication

- Paired RAG source experiment: both arms completed on the initial host, with context 2048,
  general corpus, ChatML non-thinking prompts, 64 output tokens, one warmup and three iterations.
  The artifact-bound publisher policy passes: decode ratio 0.900553, end-to-end latency ratio
  1.132929, 12/27 direct model answers, all 12 correct. See `rag-source-06c75314/verdict.json`.
  The resumed reference uses the repaired SSE parser; the original failed run remains recorded.
  This source result predates the BF16 correction and does not establish final release admission.
- External quality: `public-qa-protocol.md` freezes 100 SQuAD 2.0 and 100 BoolQ cases before quality
  outputs. `public-qa-cases.json` records source and prompt hashes. Eight 25-case shards run both
  arms on the same host per shard; answer-quality scores aggregate all cases, timings do not.
  Both arms now complete all 200 cases with identical scores (SQuAD EM 0.77/F1 0.800298;
  BoolQ 0.90), no failures and no truncations. See `public-qa-v2/`. All eight hosts terminated.
  These source results predate the BF16 repair; final-runtime admission remains outstanding.
- `public-qa-fleet.json` records owned instances, fixed deadlines, exact payload and result paths.
  Each worker uploads checkpoints and terminates automatically. The earlier failed bootstrap is
  retained separately; its instance was terminated.
- Completion still requires both task reports and verdicts, a Central release, a released-runtime
  default smoke, ModelJars loading at the tested context, marker publication and Central verification.

The first public-QA fleet is invalid: its String API treated ChatML controls as ordinary text.
It was stopped, with partial outputs retained in `public-qa-invalid-raw/` and explicit termination
receipts. No quality claim uses those results. The corrected harness matches all 200 publisher
token arrays before inference; `public-qa-protocol.md` records the correction and replacement run.

See the [execution plan](../../docs/findings/2026-10-11-bonsai-production.md) for the ordered queue.

## ARM regression and repair experiment

`arm-q8-paired/` is complete on Graviton3: scalar Java, SIMD Java and the initial native
route produce identical continuation hashes. Java SIMD improves decode 4.034x and prefill
4.168x over scalar. The native route regresses against Java SIMD: decode ratio 0.414 and
prefill ratio 0.400. It is rejected for release on ARM until corrected and remeasured.

The separate `42185aa7` NEON experiment is bound in `arm-neon-host.json` and its payload
receipt. Real projection outputs match Java and the independent oracle bit for bit.
The initial native result remains part of the evidence.

The NEON whole-model comparison is complete in `arm-neon-paired/`. All three measured output
hashes match Java SIMD and the original native route, and the publisher continuation/fresh-session
oracle passes. NEON improves decode 6.109x and prefill 6.539x over the original native route;
against Java SIMD the ratios are 2.538x and 2.610x. Both frozen retention gates pass. The measured
source commit is retained in the release branch history. This is same-host short-prompt route
validation, not a claim of ARM RAG qualification. The measurement host terminated after upload.

## BF16 determinism release blocker

[Real-weight CI run 38177312332](https://github.com/integrallis/models/actions/runs/38177312332)
failed full-vocabulary sequential/batched equality on Linux x86 and ARM. The other nine
large-model jobs passed. The same test fails locally with four active processors; those three
failing XML reports are retained here. A separate 16-token probe shows that a second sequential
session also differs from the first. This is cold/compiled arithmetic drift, not a batch-only
state bug.

The first divergence is in the layer-zero BF16 alpha/beta gate projections. PTQ query/key/value
projections remain exact. Vectors `BFloat16Matrix.finishRow` used `reduceLanes(ADD)`, whose
addition order changes after compilation. The corrected source uses Vectors' existing
fixed-order reduction. New fresh-JVM public API tests reproduce the old failure and pass after
the change. In the local 6 GB probe, all logits and session state arrays now match exactly for
sequential → batch-16 → sequential. The temporary tracing patch is archived here and was
removed from the working sources.

Release remains blocked until the full tests pass with released Vectors artifacts on both
architectures. External QA and RAG admission must bind the final runtime, not just the earlier
passing source experiment.

## Released catalog baseline

`catalog-baseline/` rechecks the six previously failing catalog default smokes with Central
Models 0.3.58 and Vectors 0.1.29. All six reproduce the earlier 8/9 result; they are unresolved
baseline failures, not passing tests. The raw answers and evaluator reports are retained for
investigation. All four bounded baseline instances terminated after uploading evidence.

The gated MobileMoE baseline runs through ModelJars' normal authenticated CLI installer in
[CI run 38177270567](https://github.com/ModelJars/modeljars/actions/runs/38177270567).
It passes 9/9 with the released runtime. The workflow retains only reports and dependency
provenance, not the gated weights or credentials. The full released catalog regression remains
required after the dependency update.
