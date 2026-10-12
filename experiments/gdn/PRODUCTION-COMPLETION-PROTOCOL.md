# DeltaNet production completion

The user has explicitly authorized completing the decision, validating the winner, making it the
production default and removing the unused implementation. A diagnostic report is not completion.
Earlier frozen experiment commits and failed evidence remain unchanged.

The remaining comparison uses the ownership-corrected, arithmetic-aligned sequential native
implementation and current/serial-tiled/staged-tiled Java recurrence. All arms share one native
projection context, weights, prompts and shipping-source activation implementation. No Java agent
or diagnostic transcendental call counters may be present in decisive measurements.

First freeze a screening matrix: 4 and 16 workers, native polling 0 and 5 milliseconds, all four
recurrence arms, two counterbalanced timing repeats after three warmups, and the same eight public
cases (four JevBench, four SNIPS). This matrix selects configurations for confirmation; it cannot
qualify a production route. Record output/state equality, executed counters, loading, request
latency and process peak memory. Run x86 and ARM under immutable payloads, live pricing/quota checks,
a two-hour per-host deadline and $3.50 combined ceiling per two-host phase.

Then confirm the selected implementation against the current implementation on the full 148-case
two-dataset cohort, independent process pairs, both Qwen sizes, prefill/decode continuations,
ragged grouped sessions, startup/memory and existing numerical/quality/performance gates. Include
the published baseline to expose regressions introduced by the activation correction. Any failed
case stays in the cohort and must be fixed. Check independent oracle continuations, not only
candidate agreement. Do not infer a hardware routing threshold from one host.

Completion requires production dispatch tests demonstrating the actual selected default, removal
of the unused alternative (after ABI/caller review), full required builds and supported-host CI,
and article/report updates describing the resulting implementation. The user has authorized these
steps; no additional permission checkpoint is introduced for them. Publication follows the
existing Vectors -> Models -> ModelJars release procedures and their checks.

## Screening payload correction

The first payload requested polling zero, which the existing Java API rejects (valid range is
1–60000 milliseconds). This is a setup failure, not a performance result. Its source activation
checks reproduce the old drift and pass the correction on both architectures; the completed
x86 repeated-reference check also passes. ARM was stopped before the known-invalid timing step.
Payload v2 uses the prospectively declared valid values 1 and 5 milliseconds and validates the
range before starting. No completed timing measurements were available when this was corrected.

## Artifact confirmation

The source-only x86 screening matrix completes without a numeric mismatch. Native/staged-Java
paired median ratios are 0.99694 (4 workers, 1 ms), 0.99159 (4, 5 ms), 1.0166 (16, 1 ms), and
0.9823 (16, 5 ms). These small, configuration-dependent differences require confirmation; they
are not a default-selection verdict. ARM screening remains in progress.

Two actual production candidates now exist: the corrected sequential native default, with the
chunked recurrence removed, and the register-tiled Java default, with native recurrence exports,
bindings, obsolete scratch and test-only production conveniences removed. Freeze each source
revision and compare its actual JARs with current Java plus corrected activations and the
published activation baseline. The latter is known to drift; retain all differences for diagnosis.

Correctness confirmation runs all 148 cases on both Qwen sizes, smallest first, including eight
greedy continuation steps per case and hashes of every full logit vector. A separate observation
run delegates every capability decision unchanged and checks the actual prefix and grouped
calls. Group sizes 1, 3 and 8 use public prompts with unequal suffix lengths and compare all
logits with independent requests. A native artifact must take both recurrence routes; a Java
artifact must take neither. No diagnostic proxy or Java agent is installed in timing processes.

The workload context budget remains 2048, declared through BackendConfiguration for every arm.
Dispatch, native and Java worker counts, and polling use library defaults without tuning system
properties. This is qualification of default execution routes under a declared workload budget,
not a claim that an unrestricted model-maximum context has the same startup or memory cost.

A separate timing phase runs ten counterbalanced process sets per model, with all four artifact
arms, one cold request, three warmups and three measured warm requests per process. Preserve
load, prefill, decode and whole-request timing, process peak RSS, full-logit hashes and quality.
Use process pairs as the primary statistical unit; no winner is declared from a pooled median.
Each two-host phase retains the existing two-hour deadline and $3.50 combined ceiling. Incomplete
or inconclusive phases require continued work, not promotion to a completed qualification.

## Production correctness v2 failures and correction

The x86 baseline failed the retained first public case when full prefill was compared with
split prefill or a single grouped suffix. A diagnostic traced the first changed projection to
identical Q4_K weights and input with batch sizes 32 and 1. The AVX2 batched kernel distributed
the minimum correction across SIMD lanes; the single-row kernel reduced it separately. The old
binary-exact fixture concealed the rounding discrepancy. A non-power-of-two-scale regression
fails before the fix. Both candidates now use the established single-row reduction order in
batched projections, retaining weight-block reuse and one float horizontal reduction per row.
The cached small model subsequently has exact full logits in the full/split/group probe.

ARM's first grouped case passed, but the next test had no common prefix: the harness assumed
that the first public prompts shared one. Groups 3 and 8 now use the largest first-token bucket,
with deterministic ordering and no label-based selection. Group 1 retains the originally failing
case. Both failures and the original payload remain preserved.

Confirmation v3 compares the recurrence candidates against old Java plus the same activation
and Q4_K correction. The fourth, published arm retains both original arithmetic implementations.
The baseline source is commit 4337ee45 plus the hashed projection-only patch. All four packaged
Rust crates are compile-checked for both Linux architectures before launch. The source and
binary builds of the independent oracle are pinned to llama.cpp a935fbffe; check the first four
cases from each public dataset plus the historically changed original-routing-06-0 on both model
sizes. Preserve tokenization, full requests/responses, all arm continuations, binary hashes and
server termination. This independent token check complements the full-logit artifact comparison;
it is not a full-logit oracle or a performance result.

## Frozen timing analysis

Each of the ten process pairs measures three warm requests, alternating the first fifteen
JevBench and first fifteen SNIPS cases in their existing order. This gives fifteen measured
requests per dataset per arm. Cold and three warmup requests remain separately labelled.
Record total JVM thread allocation bytes immediately outside the timed request; this measures
Java heap allocation, not native allocations. Peak process RSS includes both heaps.

For each primary metric, average the three warm requests within each process, form matched
candidate/reference ratios per process pair, and report their geometric mean and paired 95%
percentile bootstrap interval (20,000 resamples, seed 716309). Loading, the cold request and peak
RSS each supply one value per process. Preserve each individual request and every paired ratio.
The predeclared precision target is an interval no wider than two percentage points. An interval
crossing one is unresolved; a lower bound above one is a detected slowdown requiring investigation.
An interval below one establishes an improvement in the measured profile. Never treat this
precision target as a permitted slowdown. Retain published numeric differences separately;
any corrected-baseline/candidate continuation or full-logit mismatch rejects that timing run.
The analyzer is checked against known constant paired ratios and rejects a missing process.

## Final artifact comparison v5

This prospective follow-up replaces staged Java with the calling-thread tiled candidate and
replaces spilled scalar exponential evaluation with an exact SIMD implementation in SwiGlu
and causal SiLU. It retains five arms: corrected old Java (`baseline`), serial tiled Java
(`java`), corrected native recurrence (`native`), original published arithmetic (`published`),
and corrected native recurrence with the previous StrictMath activation implementation
(`strictnative`). The last arm isolates the activation change on the same host and recurrence.
All previous failures and source revisions remain immutable. No numerical, quality, or
performance gate changes.

The same two models, 148-case correctness cohort, actual route checks, ten process pairs and
thirty measured warm requests per arm apply. Arm order rotates over five arms and reverses
on alternate process sets. Corrected arms must remain exact, including strictnative. The
independent oracle still fails the phase on a mismatch, but its failure is recorded and the
remaining model's coverage is collected before returning failure. Each two-host phase retains
the two-hour deadline and $3.50 ceiling. Timing and correctness use separate owned hosts so
neither competes for compute with the other.
