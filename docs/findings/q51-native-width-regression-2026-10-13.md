# Q5_1 allocations on 128-bit hosts

Status: corrected source, focused numerical tests and the ARM component gates
are complete. X86 timing precision and fresh whole-model qualification remain
open. Neither correction has been released.

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

## Supported-host measurements

Local component diagnostics with a forced 128-bit JVM show the decoder correction
removing 679,936 allocated bytes per 4,096-weight call and reducing diagnostic
latency from roughly 190–284 microseconds to 2.23–2.37 microseconds. This is a
local Intel diagnostic, not an ARM measurement or a model-quality result.
The tested scalar alternative was slower than the 128-bit decoder. A different
masked-load candidate regressed the 256-bit path and was rejected. All these
experiments remain under `audit-artifacts/q51-width-diagnostic/`, outside the
project codebase.

The [fixed supported-host run](https://github.com/integrallis/models/actions/runs/38340726518)
completed twelve counterbalanced fresh-process pairs per kernel, separately for
four nonuniform shapes (896, 1,536, 4,096 and 4,864 elements). Every measured
block checksum and final output-array hash matched its control.

On the actual Neoverse-N2 host, reduction latency ratios were 0.0285–0.0375
(candidate/baseline, roughly 27–35 times faster); decoder ratios were
0.0131–0.0144 (roughly 69–77 times faster). Both corrected kernels allocated zero
bytes in every measured block. All eight ARM latency intervals met the original
non-regression and precision limits, as did allocation. For the 4,096-element
shape, reduction was 0.028850 [0.027998, 0.029616] and decoding was
0.013187 [0.013094, 0.013292]. These are public-kernel measurements on synthetic
arrays, not whole-model qualification.

On x86, warm allocation remained exactly equal between arms, but all eight
latency confidence intervals crossed equality. The short 1,000-call blocks did
not resolve non-regression or precision; they are retained as unresolved.
The [separate fixed follow-up](https://github.com/integrallis/models/actions/runs/38341071968)
used counts prospectively frozen from all completed x86 observations to target
500 ms per block. Every new block had to last at least 200 ms. Pair counts, order,
acceptance limits and five blocks per shape stayed fixed, with no optional
stopping or pooling of the earlier observations. All twelve pairs completed:
outputs were exact and allocation equal, but all eight latency intervals still
crossed equality and failed precision. Ratios ranged from 0.9897 to 1.0132;
the 4,096-element dot ratio was 1.0074 [0.9561, 1.0603]. Those frozen gates
remain unresolved. There is no further repeated timing cohort planned for an
unchanged wide implementation; generated-code correspondence will instead
check whether the public dispatch changes its compiled hot path. That evidence
cannot turn a failed statistical gate into a pass.

Raw component records and summaries remain outside the repositories at
`audit-artifacts/2026-10-13-activation/pinned-width-38340726518/`; the longer
follow-up has its own run identity. The earlier masked-load and scalar controls
remain rejected diagnostic evidence, never production alternatives.

## Generated x86 code correspondence

The [separate compiler diagnostic](https://github.com/integrallis/models/actions/runs/38342470170)
compiles the same public controls with the same JDK and retains all generated
C2 code. Full output hashes agree. It does not enable LogCompilation, but its
assembly-print instrumentation still means it is diagnostic evidence, not a
measurement of the uninstrumented timing forks.

The public dot method and its wide vector body have the same instruction
sequence after explicitly normalizing process-specific pointers, relative
constant-pool addresses and in-method branch destinations. Their code sizes
are 1,448 and 1,168 bytes respectively; the additional hardware selection does
not survive in the x86 hot implementation. The decoder likewise removes that
selection and retains its 4,568-byte, 1,012-instruction implementation and all
arithmetic counts. One unrolled body has different register assignment and
instruction scheduling, so the decoder is not claimed to be byte-identical.
Neither diagnostic contains a new allocation/fallback route in the wide hot
implementation. The failed statistical gates above remain unresolved.

Raw assembly, source/class hashes, numerical records and the explicit
normalization comparison remain under
`audit-artifacts/2026-10-13-activation/pinned-width-compiler-38342470170/`.
The preceding capture's parser failure from asynchronous VM/Java stdout
interleaving is retained under `pinned-width-compiler-38342317952`; the corrected
capture writes numerical records to a separate file.

## Remaining release gates

The composed implementation still needs the full Q5_1 logits, task-quality,
allocation, startup, memory and latency gates. This hardware defect does not
erase the separate historical scratch-allocation regression or its failed ARM
cohort. A new scratch-only comparison must state that both controls share the
verified width repairs; it cannot be relabeled as a comparison against the old
published runtime.

The composed Models source is `00782f64092a14fccb437a4e85bbd866b6c4b11a`.
Its production-source difference from the earlier frozen runtime is limited to
these two classes. The current macro preparation injects a checksum-bound
Vectors candidate while Models release metadata still declares the published
Vectors dependency. Final release dependency pins/manifests therefore need
explicit class-equivalence checks and consumer validation; an override run alone
is not validation of released dependency metadata.
