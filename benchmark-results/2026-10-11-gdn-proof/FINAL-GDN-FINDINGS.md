# DeltaNet qualification findings

> Historical measurements and decisions from the recorded revisions below, not a current release verdict. Raw experiments, failed controls, scripts and captures are preserved in the [verified external archive](ARCHIVE.md). Current status is in [runtime cleanup](../../docs/findings/runtime-cleanup-2026-10-12.md).

The former inactive DeltaNet route was not safe to activate unchanged. Qualification
found and fixed recurrent state ownership/slot/group handling, grouped Q4_K
projection inconsistency, and native-call allocation. The corrected native
recurrence executes in production routing and is faster than the corrected Java
recurrence in the completed causal measurements. It is therefore not dead code.
Unused alternative implementations can be removed only after their measured
rejection and callers are recorded; this report does not authorize deleting the
validated production recurrence.

The final product is **not yet qualified as passing every gate**. Exactness between
corrected Java and native recurrence passes completed full-model comparisons;
independent publisher recurrence checks pass. Published task-quality differences,
remaining external whole-model discrepancies, and peak resident memory regressions
remain explicitly unresolved. A passing build does not settle those gates.

## Concrete fixes and evidence

- Native recurrence state ownership, state-slot/group handling and Q4_K batched
  projection corrections are included in the final runtime. Native routing and
  executed-call probes accompany each architecture/model phase.
- Eligibility is computed once from immutable hardware/runtime capabilities.
  The cached predicate allocates zero bytes in the recorded component probe.
- The single-session FFM adapter converts its seven float arrays through method
  handle filters. On the recorded x86 host this reduces adapter allocation from
  504 bytes per call to zero for both one-token and 32-token shapes; all ten
  complete recurrent-state/output hashes remain exact. No cache was introduced.
- Exact SwiGLU now crosses an array-only method boundary, allowing vector values
  to remain within a compiled kernel. The numerical fdlibm implementation is
  unchanged. Final full-model snapshots separately verify that change.
- Every retained logit snapshot is cloned before the model reuses its output
  buffer. Historical immediate greedy tokens remain valid, but historical
  aliased full-step hashes and prefill-label claims are superseded.

## Causal recurrence comparison

The v9 candidate and corrected Java control use the same arithmetic and Vectors
artifact. On x86 4B, native/current-Java whole-request ratio is 0.8308
(95% interval 0.7971–0.8704), with all recorded full logits exact. Prefill ratio
is 0.7922 (0.7720–0.8121). Decode ratio 0.9282 (0.8364–1.0296) remains unresolved.
The old candidate had memory regressions; these measurements are retained as
causal recurrence evidence, not mislabeled as complete product qualification.
ARM4B old timing was prospectively superseded after eight complete triplets to
measure verified allocation fixes. Its missing two triplets remain missing.

## Final artifact comparison

Final v11 uses actual Models `0bc5de31` class bytes and Vectors `ae337eeb`, with
artifact hashes in the payload manifest and a zero-difference integration class
inventory. The never-launched v10 overlay is retained as such. All five timing
shards use fixed 32-request warmup, three measured requests and ten process pairs
per model/architecture. Four-Billion-parameter model pairs retain two host strata.
Full 148-case corrected-Java/native cohorts are separate from timing. All three
final refresh cohorts (ARM 0.8B, ARM 4B and x86 4B) now pass all 1,332
independently cloned vectors per cohort against both final Java and prior native
records. The activation qualification separately covers final x86 0.8B.

The completed ten-pair x86 4B product comparison has whole-request ratio 0.9406
(0.9021–0.9850), prefill 0.9107 (0.8888–0.9352), and unresolved decode 1.0050
(0.9156–1.1124). Latency intervals do not meet the two-percentage-point precision
target. Peak RSS regresses by 2.28% (1.94–2.61%); load time regresses by 2.63%
(1.71–3.59%). Steady allocated bytes improve slightly, ratio 0.999941
(0.999938–0.999944). These startup/RSS failures remain open.

The completed ten-pair ARM 0.8B product comparison has whole-request ratio
0.9183 (0.8855–0.9423), prefill 0.9197 (0.8861–0.9453), and decode 0.9144
(0.8818–0.9341). Those intervals also miss the precision target. Peak RSS
regresses by 10.44% (8.79–11.94%), load time by 1.31% (0.41–2.24%), and cold
request time by 10.60% (8.77–12.74%). Steady allocated bytes improve slightly,
ratio 0.999754 (0.999748–0.999760).

The completed ten-pair ARM 4B product comparison has whole-request ratio
0.9887 (0.9794–0.9985) and decode 0.9494 (0.9461–0.9521), both meeting the
precision target. Prefill 0.9938 (0.9835–1.0047) remains unresolved. Peak RSS
regresses by 6.41% (6.23–6.60%) and cold-request latency by 2.39% (0.74–3.66%).
Load time 1.0069 (0.9893–1.0240) remains unresolved. Steady allocated bytes
improve slightly, ratio 0.999941 (0.999938–0.999944).

All thirty final process pairs are complete. Steady Java allocation is lower in
every final process pair. Peak RSS is higher, consistently across the completed pairs.
Read-only snapshots of untimed ARM4B correctness JVMs attribute most observed
resident-byte difference to the Java heap: native 1,539,216 KiB versus published
1,283,224 KiB. Model mappings are identical at 2,676,700 KiB. These are different
requests on separate same-type hosts, so this attribution is descriptive rather
than a paired performance measurement. The first completed timed pair allocates
roughly 951 MB versus 415 MB on its cold request. Cold vector-object allocation
and heap page retention are a hypothesis requiring a controlled diagnostic, not
a demonstrated native memory leak or a waived RSS gate.

A separately frozen ARM 0.8B diagnostic replaces only the exact SwiGLU and
causal-convolution activation implementations with fixed scalar implementations.
All 148 cases and 1,332 independently cloned vectors match the vector candidate
exactly. Across all five completed matched process pairs, cold allocated bytes drop
79.46% and peak RSS drops 14.83%, while steady allocated bytes are unchanged.
Steady whole-request latency increases 25.31%; every pair is slower, ranging
from 21.56% to 35.60%. Cold-request latency improves 7.37% on the geometric
mean, while load latency is approximately unchanged (ratio 1.0028). This is causal evidence for cold
allocation/RSS overhead from those vector activation implementations, but the
scalar control is not a qualified production replacement. All fixed
five-pair diagnostic records are retained and the owned host is terminated;
no cold counter or compilation-state routing is introduced.

The final machine-readable reports contain all declared strata and distinguish
exact numerical comparisons, task-label changes and each measured metric. Worker
completion means data collection completed; it does not turn the offline startup,
RSS, precision or task-quality failures into passes. All owned final and scalar
diagnostic hosts are terminated, with receipts. Earlier incomplete/superseded
phases remain explicitly incomplete rather than being promoted to passes.

## Independent model correctness and task quality

`ORACLE-DIAGNOSIS.md` and its archived raw captures retain the original
failed external assertions. The former reference to `ORACLE-PROTOCOL-RECONCILIATION.md`
was not present in the recorded source inventory and is not claimed as available evidence. They establish a publisher-mandated additive epsilon
already implemented correctly in Models and an independently reproduced ARM
oracle Q8_0 repack tail defect. Captured Q8 quantization/reduction differences
explain additional tensor discrepancies; they do not establish that every later
whole-model discrepancy is benign. The pinned publisher recurrence passes 18
real captures at the predeclared 2e-6 tolerance. Java/native agreement is not
substituted for independent full-graph correctness.

All four architecture/model v9 correctness cohorts are complete and Java/native
exact. Task-label scores are x86 0.8B 86/148 versus published 89/148; x86 4B
128/148 versus 130/148; ARM 0.8B 89/148 versus 90/148; ARM 4B 128/148 versus
129/148. These are failed no-regression quality comparisons, preserved separately
from the exact recurrence checks.

The fixed x86 0.8B quality factorial scores published 89/148, activation-only
88/148, corrected-projection control 88/148, and combined 86/148. The combined
artifact has seven correct-to-wrong, four wrong-to-correct and two wrong-to-wrong
label changes. `QUALITY-FACTORIAL.md` retains the per-label margins and source
scope. There is no case-specific tuning and no claim the failed quality gate
passes merely because the arithmetic changes are independently justified.

## Evidence and cleanup

Original failures, superseded sources, immutable payload hashes, operational
amendments, raw records and ownership-scoped termination receipts are retained.
Only one final host's shutdown deadline was explicitly extended to 22:30 UTC;
its cases, arms, pairs, source and gates did not change. Evidence exports contain
no production runtime source or full model artifacts; small captured weight
blocks/tensors needed for independent replay are intentionally included.
