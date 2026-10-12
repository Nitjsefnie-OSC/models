# Whole-model comparison with one shared native context

## Correct the control before using its timing

V4 used one Rust context for projections and another for native recurrence. Both own polling
worker pools; this can bias application timing through contention. Stop those workers, preserve
all evidence, and mark every earlier whole-model native comparison using that arrangement invalid
for performance decisions. V5 was packaged/uploaded but never launched and is superseded. This
does not invalidate isolated JMH, where there are no simultaneous projection calls, nor the
reproduced numerical instability and deterministic control.

V6 uses a single RustGgufBatchedMatrixKernel provider/context for every projection and for the
native recurrence control. The wrapper calls that provider's existing public recurrence method.
All four arms use the exact same aligned/sequential/matched-thread native binary for surrounding
operations; only recurrence selection changes. Assert no separate recurrence context was opened,
log the shared-context marker, and retain positive recurrence call counts. Initialize a custom
Java polling pool lazily, so the staged/serial arms do not create an unused pool.

Arms: current Java, four-accumulator Java serial, the same arithmetic on Vectors' existing staged
executor, and aligned native recurrence on the shared context. Include serial explicitly because
worker coordination can change the application winner. These are experimental controls, not
proposed user-facing switches. Preserve the deterministic activation control for every arm and
all earlier artifact/JVM/source pinning. Full logits must match exactly for all candidate arms.

## Two separately bounded phases on both architectures

Settings: AMD m6a.4xlarge and Graviton m7g.4xlarge, 16 processors/workers, the same pinned Qwen4B
artifact and the unchanged public datasets/prompts. Native integration parks Vectors workers;
honor that existing coordination policy. Keep every phase/process separate in the report.

1. **Correctness:** three warmups per arm, then every one of the 120 JevBench and 28 selected
   SNIPS cases, all four arms: 604 observations. No timing acceptance claim from this fixed-order
   phase. Stop on any exactness failure and retain the Java replay.
2. **Timing:** the same frozen 26-case subset, three warmups/arm, baseline/candidate exactness for
   every case, then five counterbalanced repetitions of every case/arm: 636 observations. This
   remains diagnostic performance under an instrumented stable baseline; production generation,
   startup, memory and independent-oracle qualification are still separate requirements.

Each phase is frozen as its own payload and has two-hour per-host deadlines and a $3.50 combined
compute/storage limit. Live prices/quota, minute uploads and termination receipts are mandatory.
Splitting phases retains the whole workload without relying on an infeasible combined ARM run.
Do not combine the invalid two-context times with the corrected shared-context measurements.
