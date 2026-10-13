# Q5_1 allocations on 128-bit hosts

Status: corrected source and focused numerical tests are complete; supported-host
component measurements and fresh whole-model qualification remain pending.
Neither correction has been released.

## Observed defect

The prior Q5_1 control on GitHub's Neoverse-N2 host allocated about 1.4–2.1 TB
of Java objects per request and took 112–162 seconds during an instrumented
model diagnostic. These are total allocated bytes, not resident memory. They
are not the decisive paired timing cohort. The earlier diagnostic timed out;
its failed execution and partial output remain retained.

A subsequent bounded 90-second JFR recording attributes 81.25% of sampled
allocation weight to `PinnedReduction` and 18.61% to `Q5_1Dequantizer`.
Stacks show the Vector API's `Float256` fallback operations in the reduction.
The actual host reports `MaxVectorSize=16` bytes. Both implementations requested
256-bit vectors regardless of the host width. The fixed-width reduction is also
present in the current published source; this defect is not explained by the
later scratch-buffer change.

Receipts are outside the source repositories under
`audit-artifacts/2026-10-13-activation/q51-jfr-38339537465/prior/`:
the JFR recording, decoded allocation/execution samples and `attribution.json`.
The preceding failed window remains under `q51-jfr-38337915186`.
[JFR workflow](https://github.com/integrallis/models/actions/runs/38339537465).
A sampled allocation share is an estimate, not an exact per-method counter.

## Corrections

`PinnedReduction` now keeps the same eight logical lanes using two physical
128-bit vectors on narrow hosts. Every FMA, accumulator merge, lane reduction,
and scalar tail retains its previous order. Wider hosts retain the original
256-bit arithmetic. Fresh-JVM tests exercise both width limits, cold and warmed
execution, offsets, tails, finite values, signed zeros, subnormals and nonfinite
inputs. Its isolated correction passed 21 focused tests.

`Q5_1Dequantizer` uses four native 128-bit integer lanes on narrow hosts. Each
iteration reads exactly four packed bytes, expands the fifth-bit plane and
applies separate multiplication and addition. The final group stays within the
24-byte block. The original 256-bit decoding loop remains active on wider hosts.
Its correction passed 36 focused tests, including fresh-JVM width checks,
scalar-formula agreement, exact parallel projections and BERT parity.

Both routes are selected from the host's preferred vector width, not a new
user-tuning switch. Hardware selection preserves the numerical contract; forcing
unsupported vector shapes is unnecessary for a fixed logical reduction order.

## Evidence still required

Local component diagnostics with a forced 128-bit JVM show the decoder correction
removing 679,936 allocated bytes per 4,096-weight call and reducing diagnostic
latency from roughly 190–284 microseconds to 2.23–2.37 microseconds. This is a
local Intel diagnostic, not an ARM measurement or a model-quality result.
The tested scalar alternative was slower than the 128-bit decoder. A different
masked-load candidate regressed the 256-bit path and was rejected. All these
experiments remain under `audit-artifacts/q51-width-diagnostic/`, outside the
project codebase.

Independent decoder and reduction comparisons on the actual x86 and ARM hosts
must pass before accepting their performance claims. The composed implementation
then needs the full Q5_1 logits, task-quality, allocation, startup, memory and
latency gates. This hardware defect does not erase the separate historical
scratch-allocation regression or its failed ARM cohort.
