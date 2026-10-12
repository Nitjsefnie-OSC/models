# DeltaNet proof experiment

Approved scope: investigate inactive DeltaNet and the retained historical speedup before deciding
on deletion or production routing. Target one best production implementation; retain dispatch
only where measured input or hardware differences require it. No production route changes here.

Base: Models `8d1bcc914459ba2a3b13627a893f269138626bc1`, Vectors dependency unchanged.

## Frozen first-stage questions

1. Do native sequential and chunked recurrence reproduce current Java output/state bit for bit?
2. Can Java preserve each element's arithmetic order while reusing state across tokens?
3. Does a Java translation of the native chunked algorithm share its benefits and differences?

Compare current Java, Java head-major recurrence, Java fused state passes, the same fused Java
work partitioned across a persistent executor, Java chunked recurrence, native sequential,
native sequential aligned with Java normalization/exp precision, and native existing automatic
sequential/chunked recurrence. Explicit route counts must prove
each arm ran. All arrays and initial state are identical across component arms. Reset state
outside the timed region. Test zero and nonzero state, token lengths 1, 7, 8, 18, 64, 128, 512;
dimensions 128 and 256, repeated runs and continuation. Synthetic tensors diagnose arithmetic;
they do not establish model quality or qualification.

Capture real recurrence inputs through a benchmark-only kernel wrapper. Hold projections,
attention, tokenizer, batching and model bytes constant. Compare full logits, greedy tokens,
recurrent state and first unequal tensor element. Do not loosen the exact Java contract to
accept a faster arm. Native differences must be localized before a recommendation.

Local runs are correctness/preflight evidence. Performance decisions require counterbalanced
same-host repetitions on x86 and ARM, kernel and whole-model timings, original public JevBench
cohort plus a second public decision dataset. Freeze payload hashes, dependency hashes, weights,
dataset revisions, JVM, compiler, hardware and thread counts beside results. Separate cold and
warm measurements and retain all failed candidates. Runtime budgets and live cloud prices must
be recorded before launching measurement hosts.

Production adoption, deletion, ABI publication and release remain separate review decisions.

## Measurement payload v1

Kernel matrix: both dimensions, all listed token lengths, eight value heads/four key heads;
five warmups and nine counterbalanced observations per arm, state reset outside timing.
Run with one and four workers; Java parallel uses the same worker count as Rust. These are
diagnostic shapes, not a substitute for actual model shapes in the whole-model arm.

Whole-model: pinned Qwen3.5-4B Q4_K_M, SHA-256
`00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4`.
Native projections/attention unchanged across all arms; only recurrence is replaced by the
wrapper. Count and time every executed recurrence. Three warmups per arm, full-logit checks on
all 120 historical JevBench cases and 28 prospectively selected SNIPS validation cases.
Then five counterbalanced repetitions on the fixed timing subset described in data/README.md.
All arms retain predictions and label logits, including rejected arithmetic-changing candidates.
Stop on a byte difference in a Java candidate intended to be exact. Record differences in the
other arms without presenting them as passing the correctness gate.

Two hosts: x86 m6a.4xlarge and ARM m7g.4xlarge, four available JVM processors, native workers
explicitly set. Temurin 25.0.3+9 and Rust 1.96.0 pinned. Maximum two hours per host; combined
compute/storage budget $3.50, validated against live prices before launch. Auto-terminate at
completion or deadline, upload progress every minute, retain termination receipts.
