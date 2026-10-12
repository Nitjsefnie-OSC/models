# Appendix: independent comparator contract and retained failed assertions

This appendix reconciles two conflicting experiment artifacts. It does not change
the results of any completed run, declare the release qualified, relax a numeric
tolerance, or authorize a performance/quality regression.

## What was specified, and what was asserted later

`ORACLE-PROTOCOL.md`, introduced in `ff48311e`, specifies an independent eight-token
greedy continuation cross-check using identical explicit token arrays. It calls
this a numerical cross-check rather than complete independent qualification. It
explicitly says that full-logit equivalence cannot be inferred between runtimes
with different arithmetic contracts, and that a mismatch is an unresolved oracle
difference to diagnose rather than automatically a candidate regression when the
current Java implementation also produces it.

`run-artifact-oracle.py`, added in `6ac1f351`, expands the selected cases to nine
and adds an unconditional assertion that baseline, Java, and native all produce
the external runtime's exact greedy tokens. That assertion is stronger than the
documented contract. It does not establish that the external runtime implements
the publisher's arithmetic or that it has no platform-specific defect.

The existing failed assertions remain **FAILED**. Their responses, token arrays,
source revisions, executable hashes, environments, and termination receipts must
remain available. A new comparator configuration receives a new run identifier
and its own verdict. It must never overwrite or retroactively pass an old run.

## Independently established discrepancies

| Discrepancy | Independent evidence | Consequence |
| --- | --- | --- |
| Qwen3.5 query/key normalization | Pinned publisher implementation uses `rsqrt(sum + 1e-6)`; Models follows it, whereas pinned llama.cpp uses `1/max(sqrt(sum),eps)`. Changing only oracle epsilon reduces x86 first-layer post-recurrence projection discrepancy from 0.001315 to 5.96e-7 on 0.8B. | Models must not be changed to copy a comparator convention that disagrees with the publisher. The additive oracle is a separately labeled diagnostic variant. |
| ARM Q8_0 repacked projection tail | Exact original weight bytes and recorded activations, evaluated by independent scalar integer dot products, agree with Models within 1.44e-6. The repacked ARM oracle differs by up to 4.823, exclusively at the last two of 122 tokens. Disabling public `use_extra_bufts` removes that large error on the same host. | The defective repack route cannot be treated as ground truth. Use and record the existing no-repack configuration for corrected comparison. |
| Q8_0 inverse-scale rounding | Models computes `127/amax`; the oracle's ordinary ARM quantizer computes `1/(amax/127)`, with an extra float32 rounding. Each implementation agrees with scalar replay of its own contract within 1.44e-6. | Differences of roughly 0.001–0.002 in these captured projections are explained by a quantizer discontinuity, not a recurrence or state bug. |
| Q8_K nearest-integer contraction | Fused versus separate multiplication before the nearest-integer operation changes exactly three recorded activation integers. Decoding the actual Q5_K weight bytes predicts all 6,144 output-row deltas at those tokens within 1.22e-6. | The captured ARM QKV difference near 0.003 is explained numerically. The specific compiler instruction selection is inferred from source and measured arithmetic agreement, not claimed from disassembly. |

Evidence and exact scope are in
`benchmark-results/2026-10-11-gdn-proof/ORACLE-DIAGNOSIS.md` and its linked replay
directories. Invalid exploratory calculations remain marked invalid and are not
used as support. The pinned publisher recurrence check is separate: its existing
18 captured samples pass the pre-existing 2e-6 tolerance; that tolerance is not
being changed here.

## What remains unresolved

Corrected comparator configuration does not make every whole-model continuation
identical. In the completed same-host ARM factorial, removing repack yields 9/9
exact eight-token continuations on 4B under the oracle's original epsilon, and
8/9 under publisher-aligned epsilon; 0.8B yields 5/9 and 4/9. Earlier x86 additive
comparisons give 8/9 on 4B and 2/9 on 0.8B. These are reported observations, not
passing independent exact-token verdicts.

The narrow x86 all-layer trace shows divergence already growing across the first
linear-attention/FFN layers, before the first full-attention layer. This excludes
the simple hypothesis that all divergence first appears at the full-attention or
rotary-position transition. It does not prove every later discrepancy is benign.
Independent scalar replay now accounts for the first-layer FFN gate/up differences
of 0.001512/0.000794 through the nine changed input quantization integers and scale
changes, leaving residuals of 2.80e-6/9.82e-7 at each token's largest differing row.
This does not establish equivalence across the remaining layers. Capture callbacks
can affect graph scheduling; their results are
diagnostic and cannot substitute for uninstrumented production validation.

## Required checks before adoption and release

1. **Final artifact versus corrected Java.** Run the actual selected combined
   binaries, with matching activation and quantization arithmetic, on both
   supported host architectures. Verify full logits, recurrent states, greedy
   continuations, grouped/nonzero state slots, cancellation/reuse paths, and
   observed default routing under the existing exactness requirements. Component
   or earlier-source results do not certify a different final artifact.
2. **Published behavior and task quality.** Report every published-output change
   separately from internal Java/native equivalence. Re-run the frozen workloads
   and production contribution/quality gates. An arithmetic correction or a
   comparator defect does not authorize a task-quality regression.
3. **Publisher recurrence and ownership.** Retain the pinned publisher reference,
   pre-existing 2e-6 tolerance, captured real-model fixtures, grouped-state tests,
   write-alias rejection, and Miri ownership checks. Passing output comparisons
   alone does not establish memory safety.
4. **Independent comparator.** Record pinned source, model hashes, explicit prompt
   tokens, host, threads, cache types, flash-attention mode, repack setting, and
   epsilon convention. Preserve complete outputs and explain known numeric
   differences. Keep remaining mismatches explicitly unresolved; do not turn
   diagnostic continuation counts into independent model qualification.
5. **Production performance and packaging.** Apply the frozen paired latency,
   prefill, decode, memory, startup, and supported-host gates to the final selected
   artifact. Verify ABI 7 consistency, packaged native binaries, Java fallback
   compatibility, dependency chain, and normal release CI. Source integration may
   precede the final result; release may not be reported as qualified before the
   required production checks pass.

This restores the documented distinction between an external diagnostic and a
candidate regression test. It is not a claim that all external differences have
been explained or that failed measurements may be ignored.
