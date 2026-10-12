# Runtime cleanup and qualification record

This is a work-in-progress record for the Vectors → Models → ModelJars release chain.
It distinguishes corrected source, executed checks, and release qualification. A passing
component test does not certify a whole model or establish that Rust is necessary.

## Confirmed defects and corrections

| Finding | Correction | Evidence and remaining gate |
| --- | --- | --- |
| BF16 reductions depend on batch layout and compiled execution, breaking real Qwen/Bonsai parity. | Fixed reduction order, two-query weight reuse, bounded existing worker dispatch, and a measured SVE-specific reduction layout. | Earlier Vectors candidate `d92aceb`; local exactness and allocation checks pass for that revision. Qwen and Bonsai fixture gates pass on x86/ARM; final corrected-harness timing remains in progress. |
| Nested callbacks can republish the same persistent worker pool; separate pools can form an A → B → A wait cycle. | Shared callback context executes nested submissions inline; failure paths restore the context. | Same-pool and cross-pool regression checks, pool reuse and failure propagation tests. |
| Native Q4_K batching changes floating-point rounding. | Preserve the scalar block correction and accumulation contract in batched execution. | Models `98093147`; native tests and primary CI pass. |
| Rust attention creates overlapping exclusive slices across workers. | Give each worker exclusive views of only its assigned heads. | Retained failing Miri control, passing corrected Miri test, tensor cases and real Granite continuations; native CI passes. |
| DeltaNet has wrong single-row state-slot addressing, grouped arithmetic differences, and overlapping worker slices. | Corrected sequential recurrence candidate; remove the approximate chunked implementation and revise the ABI. | Component and independent publisher recurrence checks pass. Final default selection requires complete model and resource gates. |
| Historical Q5_1 embedding comparison never called Rust in either arm. | Mark the historical comparison invalid; inject/count the actual provider and test optimized Java alternatives. | Actual MiniLM embeddings match exactly on both hosts. Causal-model evidence rejects blanket native routing: x86 single-token native remains a candidate; Java handles prefill and ARM. An ARM Java per-row decoder allocation regression is fixed in source and being remeasured; no JVM-necessity claim is justified. |
| MobileMoE fixture CI could appear successful while omitting its independent oracle. | Require every fixture test to execute; generate reference logits from checksum-bound publisher source before the suite. | First enforcement run correctly failed on one skipped oracle test; replacement run executes all seven tests with zero failures, errors or skips. |

Additional corrected source includes a single-row shared-prefix FFN tail incorrectly sent to a
batch-only plan, a native diagnostic test that still expected the removed capability's old
wording, and callback fixtures that now reject repeated tool invocations immediately. The
single-row defect was reproduced with a 33-token extension and verified across full caches,
logits, and the next generated token.

## Completed validation

- Vectors `d92aceb`: complete build, formatting and compliance passed (846 tasks), including
  independent Maven/Gradle consumer graphs. Its XML inventory contains 4,367 tests, zero failures
  or errors, and 24 S3 skips; the 24 S3 tests were independently run against a live bucket.
  Later activation and executor changes require their own final validation.
- Earlier Models integration build: 366 tasks; 2,588 tests, no failures or errors, 34 explicitly
  reported environment/fixture skips. This count is the ordinary build, not the subsequent
  configured fixture suites.
- All 24 live-S3 tests passed with a dedicated temporary bucket; cleanup receipts are retained.
- The supported Spring AI compatibility profile executed all 35 tool-calling tests, including
  its legacy internal-loop test, with no skips. That test is supported code, not dead code.
- The native-shim inventory covers 15 operation groups, 24 active CPU capability bits and one reserved retired bit, 20 CPU exports
  and three CUDA exports. Compliance validates inventory ownership, dispatch anchors, hashed
  evidence and declared proof fields. Fourteen mutation tests pass.
- Real Needle fixtures execute parser, layout, numerical-reference, tokenizer and tool-call
  checks. Additional configured model/adaptor suites are being collected separately.

The [native-shim registry](../native-shims/registry.json) deliberately retains unresolved
statuses. Its structural validator cannot prove a causal JVM limitation.
`verifyNativeShimCampaignComplete` must continue to fail until all required proof is present
or the corresponding implementation has been qualified for retirement.

The subsequent Q5-integrated Models build passes all 393 tasks in its completed task graph,
including compliance and independent consumer checks. Its ordinary test inventory contains
2,595 tests, zero failures/errors and 34 declared skips. The subsequent FFM allocation patch
is validated separately before another final release check.

## Independent comparator findings

The ARM llama.cpp comparator's repacked Q8_0 tail path produced incorrect projections for
the final two prompt tokens. Independent scalar evaluation of the recorded weights and
activations agrees with Models. Disabling the comparator's repacking removes that defect.
Other demonstrated differences include the publisher's additive normalization epsilon,
quantizer reciprocal rounding, and fused versus separate integer-rounding arithmetic.
These findings do not establish that every remaining cross-engine continuation difference
is harmless. Original failed reports remain retained, and exact candidate/reference checks
and task-quality requirements remain separate from cross-engine diagnostics.

## Benchmark ownership correction

Review found that two model benchmark helpers retained references to a backend-owned logit buffer
that the next forward pass overwrote. Earlier assertions about all intermediate logit snapshots
and first-token classification from those helpers are invalid. Recorded greedy continuations and
immediately computed final-logit hashes remain distinguishable from those claims. Both helpers
now clone at capture, with a regression check that deliberately reuses and overwrites a buffer;
the final comparisons rerun on the corrected harness. Independent tensor serializers and the
real Qwen/Bonsai fixture helpers already captured safely and are unaffected.

## Performance decisions still under qualification

The x86 Q4_K integer-minimum SIMD candidate preserves measured greedy continuations and final logits and reduces
whole-request time in both tested Qwen sizes. Its first comparison has insufficient precision
for several gates. A small-model Java-allocation difference comes from four baseline JVMs
entering a different allocation regime; it cannot be silently discarded. The final v11 protocol subsequently completed all 30 paired processes with a fixed
32-request warmup; it retains the earlier allocation-regime observation separately.

Rejected activation candidates and rejected grouped Q5_0 combinations remain experimental
evidence. They are not production alternatives. No source revision is considered qualified
merely because another revision passed a similarly named benchmark.

## Historical v9 failures and subsequent results

The earlier deterministic activation implementation allocated vector/array objects in
actual model execution. The earlier small-model native path also incurred per-call FFM
argument-view allocation. Both were measured defects. The subsequent method-boundary
change reduced activation allocation, and prebound method-handle filters removed the
measured FFM allocation. All 30 final v11 pairs have lower steady allocation than the
published runtime, but RSS and several startup/precision gates still fail.

Historical v9 corrected native/Java comparisons retained the full 148×9 vectors on x86.
Its published-runtime scores differed: 86 versus 89 for the small model and 128 versus
130 for the larger model. The factorial isolates activation and projection/Java artifact
bundles; it does not individually attribute every correction. Final revision-specific
cohorts and independent-oracle limitations are recorded in
[the complete GDN report](../../benchmark-results/2026-10-11-gdn-proof/FINAL-GDN-FINDINGS.md).

Bonsai's initial whole-model triplets allocated roughly 2.3–2.5 times the published
runtime. Candidate/control equality did not identify the responsible shared component.
Later actual-model JFR identified dominant BF16 helper call stacks; an isolated code-form
change reduces their allocation on both hosts while preserving captured full logits.
That experiment still needs its own complete performance/resource qualification.
The independent 200-case public QA comparison is now complete: all raw answers match
the pinned publisher, without failures or truncations, on the explicitly frozen revision.

## Publication boundary

No release has been published as part of this record yet. Publication must proceed upstream
first: validate and publish Vectors, verify its Central artifacts, update and validate Models,
publish and verify Models, then update ModelJars metadata/runtime dependencies, publish its
artifacts and CLI, and verify local installations. Final versions, immutable revisions, CI
links, artifact checksums and any unresolved gates will be recorded before closing this report.

## Model intake and branches

The [intake inventory](qualification-intake-2026-10-12.md) links a machine-readable snapshot of
28 requested candidate groups and their exact qualification records. Unfinished families remain
explicitly unfinished; previously qualified small models cannot stand in for requested larger
variants or new modalities.

Branch inspection found no merged feature branches safe to delete. Missing ModelJars worktree
metadata was pruned. Dirty original checkouts and detached experiment worktrees retain work or
failed evidence and have not been discarded. Integration work is collected in the existing
Vectors, Models, and ModelJars PR branches; proof branches remain until their evidence is
integrated and their unique changes can be accounted for.

## Subsequent corrections and verification

The activation method-boundary correction keeps the exact arithmetic while reducing measured
small-model allocation to 0.458 of the previous deterministic implementation (paired interval
0.403–0.554). The native DeltaNet call now prebinds array-to-segment argument conversion;
component measurements fell from 504 bytes per invocation to zero, with matching state and
output hashes. Whole-model allocation and RSS remain separate gates; all 30 final paired
measurements improve steady allocation but still show higher RSS. The earlier failed runs
above remain evidence of the rejected revisions.

A further executor range check reproduced negative row indices and skipped stage work at
Integer.MAX_VALUE. The isolated overflow correction passes 21 focused tests, four bounded
artifact probes, and preserves partition hashes across 70 ordinary configurations. Its
qualification scope is range correctness, not a fresh whole-model timing result.

At Models revision b99ac282, CI 38308197103 and all native platform jobs in 38308197090 passed.
Model integration 38308197116 first failed to resolve a Maven POM; both exact repository URLs
subsequently returned identical valid content, and an unchanged rerun passed all integration
steps. The initial failure and resolution receipt are retained. Large-model CI 38308197041 passes nine jobs but fails native Bonsai's transformed-batch
full-logit parity on x86 and ARM with published Vectors 0.1.29. The unchanged literal CI method and complete Qwen/Bonsai fixtures pass with Models
`d2d0fb4f` and overflow-corrected Vectors core SHA
`79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7`.
This is not a green released dependency chain. The real activated-adapter fixtures fail:
both call the weather tool twice; LangChain4j catches the assertion and loops until the
900-second bound, while Spring AI propagates the error after 18 seconds. The follow-up
LangChain4j fixture caps sequential tool calls at one; it does not repair model behavior.

The lazy shared Q5 decoder avoids initializing its Vector API constants for unrelated
formats. Its 32 decoder tests pass; 100 paired loads remain within one percent of the eager
candidate, but do not remove the measured published-runtime startup gap.

Both interrupted Bonsai Spot hosts retain their raw partial results. Recovery repeats both
arms for each missing case on the replacement host; no cross-host latency pairing is used.
The source and dataset remain frozen, and missing cases are selected solely by interrupted
coverage, before scoring. The resulting record remains a qualification of that frozen
revision, not a renewal for subsequent runtime changes.

## Final evidence collected after that snapshot

The complete Vectors activation-boundary build (`ae337eeb`) passes all 846 tasks in
54m54s, including consumer resolution and compliance. Its on-disk XML inventory contains
4,368 tests, no failures/errors and 24 separately declared S3 skips. The later overflow
change has its own 21-test reproduction and successful GitHub release dry-run.

The [CI failure review](ci-failure-review-2026-10-12.md) identifies the distinct shared
toolchain, expectation and real-logit failures. HuatuoGPT passes locally with both the
published and candidate core, with identical hashes, so those runs cannot explain its
scheduled CI failure. The full GitHub determinism matrix is running on the updated branch.

[Restored BERT](../../benchmark-results/2026-10-12-bert-restored-final/README.md) retains
all 82 independent embeddings exactly on both hosts. Ten paired process measurements per
host remove the earlier large RSS regression, but confidence intervals do not prove the
strict memory/startup gate. No completed qualification is claimed from those intervals.

The Q5_1 public convenience calls allocated an ignored row plus unused Q8 scratch. Both
allocations are removed without changing Q4_1; exact shape/lifetime tests pass. The causal
Qwen caller already supplies owned scratch, so its frozen measurements are separately
identified rather than relabeled as measurements of the changed artifact.

Bonsai allocation profiling identifies BF16 vector helper call stacks, and a separately
identified scalar-activation control changes their observed allocation behavior while
retaining complete logits. This is caller/compiler sensitivity; sampled allocation
stacks alone do not identify one exclusive cause. The fixed-scalar ARM DeltaNet control
retains all 148×9 snapshots but is rejected: five paired processes reduce cold allocation
by 79.46% and RSS by 14.83%, while increasing warm request time by 25.31%. An isolated
BF16 code-structure experiment is being measured; it has not been selected for production.
