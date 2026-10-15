# Independent oracle diagnosis

> Historical measurements and decisions from the recorded revisions below, not a current release verdict. Raw experiments, failed controls, scripts and captures are preserved in the [verified external archive](ARCHIVE.md). Current status is in [runtime cleanup](../../docs/findings/runtime-cleanup-2026-10-12.md).

These are diagnostic findings, not a replacement verdict for the failed exact-greedy
checks. The original failures remain in their original result directories.

## Normalization contract

The pinned upstream Qwen3.5 implementation in Transformers
`812a950d7037ba93bb624b154030e9068afdce4d`, captured with SHA-256
`496958ed2dc0bbf3d2ca0c0368cee9129742f08a79104c0951f505ac29d5a4e0`, defines
`l2norm` as `x * rsqrt(sum(x*x) + 1e-6)` (lines 280–283). Both its chunked and
recurrent DeltaNet functions invoke that normalization (lines 329–331 and
449–451); the model calls those functions with normalization enabled (620, 633).
The Models implementation uses this additive epsilon convention. The pinned
llama.cpp CPU L2 normalization instead uses `1 / max(sqrt(sum), eps)`.

The difference is measured, not merely inferred from source. On x86, replacing
only the oracle epsilon convention decreases the first-layer post-recurrence
projection input maximum discrepancy from 2.65e-4 to 1.91e-6 on 0.8B and from
2.87e-4 to 6.56e-6 on 4B. The corresponding projection output discrepancy falls
from 0.001315 to 5.96e-7 and from 0.000815 to 1.19e-6. There is no basis to alter
Models to copy the oracle's different normalization convention.

This change does not explain every full-model difference. Small changes can alter
activation quantization at rounding boundaries: on x86 0.8B, the additive oracle
has nine different Q8_K integers at the first FFN input even though the float
input maximum discrepancy is 8.37e-6. Full-logit and first-diverging-token margins
are retained in the complete-analysis JSON files. Those data do not, by
themselves, establish that all later discrepancies are expected rounding effects.

## ARM projection tail defect in the external comparator

The ARM oracle's first-layer alpha and beta projections agree with Models on the
first 120 prompt tokens, but have large errors on the last two of 122 tokens.
These weights are Q8_0 matrices with 16 rows and 1024 columns. Models ARM and x86
outputs are bit-identical for these projections; the x86 oracle also agrees
within ordinary float accumulation error.

An independent scalar replay reads the original GGUF weight bytes and recorded
activations. It uses Python integer dot products, explicit float32 quantization,
and double accumulation; it does not invoke either library. Against Models, the
maximum errors are 1.44e-6 (beta) and 1.02e-6 (alpha). Against the ARM oracle's
last two tokens, they are 4.823 and 4.634. For example, beta token 120, row 3 is
5.252203642 in the scalar replay, 5.252203941 in Models, and 0.429472595 in the
ARM oracle. Replaying the oracle's own captured activations and its quantizer
division convention preserves the large discrepancy.

Exact weight bytes, GGUF offsets, weight/input hashes, token/row indices, and both
quantized and unquantized scalar calculations are in
`oracle-arm-q8-scalar-replay-v2/`. The checked GGUF SHA-256 is
`bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517`.
The first failed replay stopped at a shape assertion before computing results;
the corrected parser verifies every contiguous stride before reading arrays.

Oracle logs identify the ARM `q8_0_4x8` repack route for these matrices. Its
full groups of four tokens use GEMM, and the final two use GEMV. This is the
localized suspect, not yet a confirmed source-level cause. A separately frozen
ARM factorial experiment compares repack enabled/disabled and original/additive
epsilon on the same host, same nine requests, both pinned models, and microbatch
32. Existing public `llama_model_params.use_extra_bufts` controls repacking; no
production Models behavior is changed by this diagnostic.

The controlled ARM factorial now confirms the repack route is responsible for
the large tail error. Disabling the existing `use_extra_bufts` flag lowers the
beta/alpha maximum discrepancies to 0.001175/0.002359. An independent scalar
replay of that disabled route, using its own captured activations and quantizer
division convention, agrees within 1.44e-6/1.02e-6 across all 122 tokens, including
the tail. These residual differences against Models are accounted for by
quantizer rounding: Models uses `127 / amax`, whereas the pinned ARM oracle's
non-repacked quantizer uses `1 / (amax / 127)`, with an intervening float32 rounding.
The disabled route is therefore a validated comparator configuration for these
projections; it does not imply full-model token identity. Raw verification is in
`oracle-arm-no-repack-scalar-replay/`.

## ARM quantizer contraction boundary

The smaller ARM QKV discrepancies have a separate, numerically demonstrated
cause. GGML's scalar `nearest_int` adds 12582912 to the scaled activation and
extracts mantissa bits. A fused multiply/add rounds once; Models explicitly
rounds the multiplication before that addition. On the recorded first-layer
input, the two contracts produce exactly three different integers, at prompt
tokens 59, 75, and 112. These are precisely the only tokens whose QKV output
discrepancy exceeds 1e-4.

Using actual Q5_K weight bytes, the delta from each changed integer predicts all
6,144 output rows within 1.22e-6. The largest observed difference is 0.003019333;
the independently predicted difference is 0.003018503. The other 119 tokens have
a maximum difference of 2.86e-6. This establishes the fused-versus-separate
quantizer arithmetic explanation for these captured projection errors; compiler
instruction selection itself was inferred from that agreement and source, not
claimed from disassembly. `oracle-arm-q8k-fma-replay-v2/` retains the exact raw
weight blocks, changed activation indices, input/output hashes, and predicted
per-row deltas. An initial exploratory decoder used the wrong tensor layout and
produced nonfinite predictions; its `INVALID` receipt is retained separately and
it is not evidence for this finding.

## Whole-model follow-up

The four-arm ARM experiment completed with both models and all nine requests.
For original/additive epsilon, repack enabled gives 1/9 and 1/9 exact continuations
on 0.8B and 3/9 and 3/9 on 4B. Repack disabled gives 5/9 and 4/9 on 0.8B and 9/9
and 8/9 on 4B. This is a separately labeled corrected comparator configuration;
the earlier failures remain failures. Its worker was observed terminated.

A narrow all-layer trace on x86 follows normalized inputs and attention/FFN
outputs. On 0.8B with additive epsilon, normalized-input RMS differences rise
from effectively zero at layer 0 to 0.00127 at layer 1, 0.01805 at layer 2, and
0.03723 at layer 3, before the first full-attention layer. They do not first
appear at the transition to full attention. This localizes the growth to the
preceding linear-attention/FFN path; it does not prove every later discrepancy is
harmless or constitute independent whole-model qualification. Capture callbacks
can alter graph scheduling, so the trace is diagnostic, not a performance run.

The x86 first-layer FFN difference is independently accounted for as well. With
publisher-aligned epsilon, the normalized inputs change nine Q8_K integers.
For each of the 122 tokens, scalar decoding of the actual weight row with the
largest observed difference predicts the FFN gate/up projection delta. Maximum
observed differences of 0.001512/0.000794 reduce to residuals of 2.80e-6/9.82e-7
after accounting for quantized input and scale changes. These small residuals are
consistent with the separately observed float accumulation differences; this is
a diagnostic calculation, not a new tolerance or a whole-model qualification.
Raw weight rows, input/output hashes, and all 122 row selections per projection
are retained in `oracle-x86-ffn-input-delta-replay/`.

## Scope of completed evidence

Both v3 workers completed and were observed terminated. All nine requests have
identical explicit input tokens, eight greedy steps, full logits, and first-layer
capture records for the selected diagnostic request. Captured logits are checked
to reproduce the recorded greedy IDs. Comparisons stop after the first different
generated token, since later prefixes would no longer be comparable.

Exact eight-token matches for original/additive epsilon are respectively 3/9 and
2/9 on x86 0.8B, 7/9 and 8/9 on x86 4B, 1/9 and 1/9 on ARM 0.8B, and 3/9 and 3/9
on ARM 4B. These counts remain failed exact-match diagnostics; correcting the
independent comparator requires a newly identified run and result, not rewriting
them. The separate 18-sample pinned Transformers recurrence check passes its
pre-existing 2e-6 tolerance. Component recurrence correctness and independent
whole-model token identity are distinct claims.

## Classification against the publisher contract

These discrepancies have different meanings and must not be grouped as harmless numerical noise.

- **Publisher-mandated architecture, already correct in Models:** additive query/key normalization epsilon. The pinned publisher source adds 1e-6 before reciprocal square root. Copying the comparator's maximum-with-epsilon convention would move Models away from the publisher.
- **Proven external implementation defect:** ARM Q8_0 repack tail. Original-weight integer arithmetic establishes the large error, and the same-host no-repack control removes it. No Models change is indicated.
- **Runtime approximation conventions, not publisher architecture requirements:** Q8_0 inverse scaling and Q8_K multiplication/contraction. The publisher recurrence consumes floating projections; it does not prescribe the extra Q8 activation quantization used by these quantized runtime matrix kernels. GGUF weight layout describes stored weights, not a mandate for one host compiler's contraction of activation scaling. Models uses separate float32 multiplication followed by nearest-integer rounding for Q8_K. This agrees with the uncontracted reading of GGML's scalar reference `nearest_int(iscale*x[j])`; the measured ARM comparator effectively uses a fused operation at the three recorded boundaries. For Q8_0, Models computes 127/amax, whereas GGML's scalar reference uses 1/(amax/127). That reference difference is real and must remain documented. Neither a publisher specification nor the current evidence establishes that the oracle convention is the uniquely correct activation approximation.
- **Confirmed Models bugs already corrected:** native grouped/state-slot ownership defects and inconsistent Q4_K batch/single rounding. These violated internal numerical/ownership contracts independently of external exact-token disagreement, so they required fixes. Exact activation and allocation fixes are also independently motivated; the 148-case labels were not used to tune arithmetic.
- **Unresolved:** later whole-model tensor accumulation and final greedy-token differences after the known comparator corrections. Current Java/native equality is useful regression evidence but is not independent whole-model correctness. No statement here upgrades those results to qualified.

An additional independent check is already present in `oracle-arm-no-repack-scalar-replay/report.json`: it compares both runtimes with original GGUF weights multiplied by unquantized captured float activations. For alpha, Models/direct-float RMSE is 0.0084306704 and corrected-oracle/direct-float RMSE is 0.0084352652; for beta, 0.0033304633 and 0.0033324560. Their maximum absolute errors are the same to the displayed precision (0.039133 and0.016457). The quantizer convention difference is much smaller than their shared activation approximation. Switching Models to the oracle inverse-scale convention is not demonstrated more accurate on these captured projections. This limited observation is not a universal accuracy bound or a new tolerance.

The read-only captured GGML scalar source has SHA-256 `07143d7068936ae46b3c528b2f3d4bbb666e74d88992165716174d243573965d`. In that source, Q8_0 reference quantization is at lines 276–299, nearest-integer rounding at 621–626, and Q8_K reference quantization at 2768–2807. The explicit float parameter boundary is relevant when distinguishing the source expression from compiler contraction. The existing scalar replay files retain the actual inputs/weights and every measured result.
