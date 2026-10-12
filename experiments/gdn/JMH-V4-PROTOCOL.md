# Exclusive native ranges and existing Java executor

The earlier native controls create overlapping exclusive buffer views. Preserve their results,
but qualify no native implementation from those observations. The corrected sequential native
comparator limits mutable views to owned heads, honors single-row state-slot mappings, aligns
the grouped exponential, and rejects duplicate grouped state slots before dispatch. Attention
uses the separate exclusive-range patch too. Production code remains unchanged.

Compare current Java, exact tiled Java using Vectors' existing stage executor, and the corrected
native comparator. Four workers, identical host types/JVM/Rust/geometry to the earlier JMH runs.
No second polling Java pool is needed for this candidate. Native and Java arms execute alone.

Prefix shapes: 1, 18, 32, 128, 512 tokens. Group shapes: 1, 3, 8, 16 rows with reversed odd state
slots and untouched gaps. Both use 16 key heads, 32 value heads, dimensions 128. Three process
forks per arm/shape, five one-second warmup and ten one-second measurement iterations, allocation
profiling, state reset outside timing. Three counterbalanced arm orders. Exact pre/post-JIT
output/state checks are mandatory. Prefix and group results stay separate.

Before timing, run the 42 exact prefix checks, invalid-slot rejection tests, 324 ragged group
comparisons and Java edge/lifecycle checks. These are kernel experiments; no full-model,
startup, memory qualification or independent oracle claim follows from a pass.

Two hosts, 45-minute automatic deadlines, maximum $1.25 combined compute/storage using live
pricing/quota validation. Freeze source and hashed payload before launch. Upload every minute,
retain every failure, and confirm owned-instance termination. Expected result count per host:
18 JSON files, 81 arm/shape/fork measurements (45 prefix and 36 group).
