# Final three-artifact confirmation with fixed complete warmup

Freeze this protocol and every source/artifact hash before launch. Earlier v5 and
Q4-only observations remain intact. Their four-warmup small-model allocation
results have an unresolved compilation-state confound: baseline forks 2, 3, 4,
and 8 allocate about 87 MB per measured request while the same Java classes in
the SIMD arm allocate 181–290 MB; other small-model forks and every 4B pair have
identical allocation counts. These forks are not discarded or reclassified as
passing memory measurements.

The final arms are corrected current Java recurrence (`baseline`), combined
production native recurrence/quantized-projection/activation candidate (`native`),
and the published artifact (`published`). The source and class differences of
the corrected-Java control are explicitly recorded in the payload manifest.
Baseline and native use identical final Vectors artifacts and matching arithmetic.
Published retains its actual published arithmetic. Serial/staged Java rejection
is documented in v5; those arms need not be repeated in this final comparison.

## Fixed process workload and telemetry

Each model/arm executes ten independent JVM processes. Every process performs
exactly 32 warmup requests and three measured requests. Warmups first visit the
existing 30 timing prompts (the first fifteen JevBench and first fifteen SNIPS,
interleaved in their already declared order), followed by two identical repeats
of the first prompt. The first warmup is separately retained as the cold request.
The last two warmups are explicit allocation/compilation anchors. Measured prompts
remain `(pair * 3 + n) % 30`, n=0..2. No case, fork, warmup count, or stopping point
changes after observing a result. All 35 full-logit/greedy records are retained. Each logit array is cloned before
the backend can reuse its output buffer; label predictions use the preserved
prefill vector. This identical harness copy allocation is included in all arms.
Older harness hashes retained aliased arrays, so their per-step logit claims and
label predictions are superseded; their recorded immediate greedy tokens remain valid.

Per request, record prefill/decode/whole latency, total JVM-thread allocation,
CompilationMXBean cumulative compilation milliseconds before/after, and total
loaded class count before/after. Read telemetry outside the timed interval and
outside the allocation window where possible. Retain both anchor allocation
counts and whether they are identical; report ongoing compilation/class loading.
Do not claim a resolved steady-state memory benefit if distinct allocation regimes
or ongoing compilation still confound the measured requests. Do not discard a
fork to repair an interval. No forced compilation, diagnostic proxy, Java agent,
or custom Models/Vectors tuning property is allowed in decisive timing runs.

Context budget remains 2048; all library worker counts, dispatch, polling, and
native routes use their actual artifact defaults. Each process uses the existing
JVM heap settings and `/usr/bin/time -v` RSS measurement. Fresh model load and the
cold request remain separate from warm metrics.

## Work partition and analysis

The full workload is partitioned to finish within a 90-minute bound per host:

| Host shard | Model | Process pair indices |
| --- | --- | --- |
| x86-small | Qwen3.5 0.8B | 0–9 |
| x86-large | Qwen3.5 4B | 0–9 |
| arm-small | Qwen3.5 0.8B | 0–9 |
| arm-large-a | Qwen3.5 4B | 0–4 |
| arm-large-b | Qwen3.5 4B | 5–9 |

Every three-arm process set stays on one owned host. Arm order rotates using the
global pair index and reverses on alternate sets. No process runs concurrently
with another measured arm on that host. Record shard/instance IDs with every
result; assert each architecture/model/arm/pair appears exactly once.

Average the three measured requests within each process and calculate matched
candidate/reference ratios within its host/pair. Report host-stratified geometric
means and the aggregate equally weighted ten-pair geometric mean. For the ARM
large-model aggregate, percentile-bootstrap pair ratios within each host stratum,
retaining its original five-pair contribution (20,000 resamples, seed 716309).
Do not pool requests as independent samples or pair measurements across hosts.
Retain per-host results and every raw ratio. All existing precision/no-regression
rules remain: two-percentage-point interval-width target; an interval crossing one
is unresolved and a lower bound above one is a detected slowdown to investigate.

## Required completion checks

All final corrected-baseline/native logits, input tokens, greedy continuations,
and label decisions must match under the existing exactness contract. Published
differences remain explicitly listed and require the separate frozen full-cohort
quality evaluation. This timing workload does not replace the 148-case correctness
cohort, grouped/default routing, publisher recurrence, ownership, release CI, or
independent comparator checks in `ORACLE-PROTOCOL-RECONCILIATION.md`.

Use the pinned model downloads and hashes, JDK and Rust inputs, immutable source
payload, live price/quota checks, minute evidence uploads, and owned-resource
termination receipts. Five timing hosts plus two correctness hosts, 90 minutes each, $12 aggregate
compute/storage ceiling. Quota accounting includes all other account instances.
Every missing/failed pair leaves the phase incomplete; budget and deadline limits
never turn it into a passing result.

The two correctness hosts each execute both models and all three arms on their
architecture, with 148 requests and nine independently preserved logit vectors
per request. They verify baseline/native exactness, default/grouped routing and
record published deltas plus per-dataset accuracy. They preserve rather than
rerun or overwrite the defective-comparator assertions discussed in the appendix.
Final Vectors source d92aceb includes the nested closed-executor correction.

## v8 completion handling after v7 published failure

v7 corrected Java and native routing passed on the first host; the historical
published artifact failed grouped numeric equality before timing began. That
failed receipt remains failed. v8 asserts and emits each initial route count
before grouped checks. It records historical published grouped failures and
continues remaining measurements, ending COMPLETE_WITH_FAILED_PUBLISHED_GATES.
Current baseline/native failures still abort; no exactness threshold changes.
The analyzer exposes every published failure and never labels all gates passed.

## v9 immutable eligibility cache

v5 ARM native overhead was exactly496 heap bytes per recurrence invocation
on stable processes (107136 bytes across18layers×12calls;142848 across24×12).
Source inspection found per-call construction of the immutable runtime-capability
diagnostic and its formatted dispatch strings. v9 resolves the unchanged
capability/FMA predicate once per provider. A separate component JVM on each
worker records five fixed100000-call allocation samples after10000warmups,
comparing actual cached eligibility with fresh capability-snapshot construction.
It does not measure model performance or share a JVM with timing. v8 observations
remain intact; all final model timing/correctness use the integrated v9 JARs.

The two small-model workers each run two prospectively fixed separate native
JVMs with LogCompilation before decisive measurement, preserving compilation XML
and the same35 request/allocation records. Those diagnostic observations are
excluded from performance estimates. v8 showed stable anchors and little ongoing
compilation while corrected-artifact allocations varied96–294MB across forks;
therefore warmup alone is not claimed to have solved the allocation regression.

## Final allocation-correction refresh (v10, frozen before launch)

The v9 three-arm causal experiment remains the evidence for corrected Java recurrence versus native recurrence. Its allocation/RSS failures remain failures. The v10 refresh changes only Vectors to ae337eeb (array-only exact SwiGLU boundary, same fdlibm arithmetic) and the reviewed Models d52bf310 single-session GDN FFM array adapter. The latter eliminates component allocation while retaining every recorded state/output bit. No cache is introduced.

The final product comparison uses two timed arms: the combined native candidate and immutable published artifacts. It preserves 32 fixed warmup requests, three measured requests, ten paired fresh processes, all samples and all original metrics/precision targets. Both architectures' 4B pairs are split into five-pair host strata; ARM 0.8B has one ten-pair host. No pairing across hosts and no independent-request pooling. The baseline Java-recurrence artifact is retained for separate, untimed 148-case correctness on ARM 0.8B and each architecture's 4B first shard. Every one of nine retained vectors per case is independently cloned, and every final native vector must match corrected Java exactly. Final records are also compared to v9 native records on the same architecture/model, preserving numerical equality across the allocation-only change. Published numerical/task-quality differences remain separately reported failures or descriptive losses, never relabeled as passes. X86 0.8B activation wholemodel evidence is supplied by the separate boundary experiment; the GDN adapter component and final exactness remain separately scoped.

Five 16-vCPU hosts, at most70 minutes each and at most$8 total, with absolute termination no later than22:30UTC. Source/artifact manifests and all prior phase receipts are retained. Capacity is coordinated with BF16 and public QA; incomplete strata are reported incomplete.

### v11 actual integration artifact freeze

The v10 payload was never launched. Once final integration JARs became available, v11 freezes all four actual Models0bc5de31 artifacts and their matching Rust source, together with Vectors ae337eeb. This includes Q5-specific dispatch/singleton changes and avoids inferring runtime equivalence from source overlays. The Java recurrence control is rebuilt by changing only the final provider's supportsGatedDeltaNet() result. Workloads, arms, samples, gates, sharding and budget are unchanged from the preceding frozen amendment.

### Operational deadline amendment, final v11 ARM4B correctness shard

At 2026-10-12T21:26:41Z the owned `arm-large-a` instance
`i-01f4ffea2c7da6657` received a replacement shutdown watchdog with absolute
2026-10-12T22:30:00Z deadline. Parent authorization covers this operational
extension within the original four-hour campaign and $8 phase ceiling.
The original 22:12:32Z receipt is retained in
`hosts-production-final-v11-before-deadline-amendment.json`; the replacement
command, process IDs and timestamps are in
`production-final-v11-arm-large-a-deadline-amendment.json`.
The complete 148-case correctness cohort takes longer than the 30-request timing
set used for the initial duration estimate. Source, artifacts, cases, arms,
process pairs, measurement protocol and all gates are unchanged.

### Evidence export scope clarification

The first export manifest at `/tmp/gdn-final-evidence-20261012-2128` described
its contents as containing no model weights. That wording was too broad: it
includes small captured weight blocks and tensors needed for independent scalar
replay, but no full model artifacts. The original manifest remains an immutable
receipt; subsequent manifests use this precise scope.
