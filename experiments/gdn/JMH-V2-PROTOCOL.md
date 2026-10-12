# Register accumulation and matched native threading

This follow-up retains the sustained JMH protocol, real 4B geometry, five token shapes, three
forks, five warmup/ten measured one-second iterations and exact pre/post-compilation checks.
Same $1.25 combined budget and 45-minute maximum per host; new immutable payload/receipts.

Why another experiment: v1 demonstrated that the retained Rust code forces single-token work
onto one thread even when four are configured. A Java polling pool distributes it. This explains
a scheduling difference; it is not evidence of a language advantage. Add an otherwise identical
native control that distributes single-token work across the configured workers too.

Java engineering: hold each value column's memory/readout accumulator in a SIMD register while
visiting state rows in the original order. Recompute the exact rounded decay multiplication in
the second pass and write the updated state once. This avoids repeatedly loading/storing the
memory accumulator and reduces state writes without reassociating the recurrence. Measure serial
and polling-parallel forms. Respect the existing vector-width and FMA policy, including scalar
tails. All input, output and state bits must match; no tolerance change.

Arms: existing Java, earlier Java polling, Java register accumulation (serial and parallel),
native aligned sequential with its retained dispatch, and native aligned with all configured
workers. V1's other candidates remain retained evidence, not erased experiments.

The separate whole-model divergence remains unresolved. Kernel success alone cannot authorize
production replacement. Model diagnosis and two public datasets remain required afterward.
