# Runtime cleanup and qualification record

This is a work-in-progress record for the Vectors → Models → ModelJars release chain.
It distinguishes corrected source, executed checks, and release qualification. A passing
component test does not certify a whole model or establish that Rust is necessary.

## Confirmed defects and corrections

| Finding | Correction | Evidence and remaining gate |
| --- | --- | --- |
| BF16 reductions depend on batch layout and compiled execution, breaking real Qwen/Bonsai parity. | Fixed reduction order, two-query weight reuse, bounded existing worker dispatch, and a measured SVE-specific reduction layout. | Final Vectors candidate `d92aceb`; local exactness and allocation checks pass. Qwen and Bonsai fixture gates pass on x86/ARM; final corrected-harness timing remains in progress. |
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
entering a different allocation regime; it cannot be silently discarded. The final protocol
must record compilation and allocation during a stronger fixed warmup.

Rejected activation candidates and rejected grouped Q5_0 combinations remain experimental
evidence. They are not production alternatives. No source revision is considered qualified
merely because another revision passed a similarly named benchmark.

## Final-candidate gates that have failed

The corrected activation implementation passes exactness checks but allocates vector/array
objects in actual model execution. JFR confirms the exponential call boundary as the dominant
source; a candidate keeps that vector loop inside one method. The small-model native path
also has a separate FFM argument-view allocation cost. Neither is excused by latency gains.

Final corrected native DeltaNet equals corrected Java for both tested Qwen sizes across all
148 cases and nine cloned snapshots on x86. Published-runtime task scores differ (small model
86 versus 89; larger model 128 versus 130), and both allocation and resident memory regress.
The factorial comparison isolates activation and projection corrections; these quality and
resource results remain release gates, not harmless differences by assumption.

Bonsai's numerical fixtures pass, but first complete whole-model triplets show allocation at
roughly 2.3–2.5 times the published runtime. Candidate/control equality attributes this to
shared activation or Models changes, not the isolated BF16 optimization. The corrected final
artifact must be measured again. The independent 200-case public QA run is still collecting.

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
