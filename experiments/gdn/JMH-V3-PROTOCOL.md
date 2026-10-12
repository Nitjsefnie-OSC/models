# Four-column-vector accumulation

Keep the v2 kernel protocol, real 4B head geometry, five token shapes, three process forks,
five one-second warmups and ten one-second measured iterations, four workers and pre/post-JIT
exactness gates. Two hosts, AMD/Graviton, 45 minutes each, $1.25 combined limit.

Hypothesis: four independent SIMD accumulators expose more instruction-level parallelism and
reuse adjacent state columns, while preserving each column's original row order and rounding.
Test serial and parallel tiled candidates against both prior register candidates, current Java
and the native control with matched scheduling. No arithmetic reassociation or relaxed tolerance.
Local nonuniform/tail tests pass including scalar, reduced-width, fused/unfused/mixed FMA modes.
A short local smoke run overlapped correctness work and is excluded from timing evidence; an
uncontended short rerun only motivates this sustained experiment, not an acceptance claim.

The unchanged whole-model reference is demonstrably unstable during early execution on x86.
This kernel experiment does not resolve that defect or authorize a production replacement.
