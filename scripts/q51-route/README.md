# Q5_1 final route selection

Frozen to Models `5344b5a2` and Vectors `b0aadc8d` for scoped Q51 route qualification. The candidate
is the actual Gradle-built hybrid runtime. Two controls replace only the native
provider's Q5_1 eligibility method: blanket native, or Java for every Q5_1 batch.
Other native operations, artifact dependencies, inputs and worker counts remain
identical. No internal route switch is added to production. All controls invoke
the real public backend; a separate counted-provider call verifies the route.

The original derived Q5_1 weights and 148-case full-logit cohort are unchanged.
All three routes must match all nine independently owned vectors, greedy tokens
and predictions exactly. Twelve fresh-process triplets repeat all six orders
twice on each supported host; every process uses exactly 32 warm requests and
three measured requests. Each correctness arm and complete timing triplet is
uploaded separately. Every failure and pair remains in the analysis.

The six previous metrics and their limits remain, with first-request allocation
and latency plus whole-request latency explicitly included. All confidence
intervals retain the two-percentage-point precision requirement. Architecture
and batch-dependent selection needs measured evidence across the relevant route
contrasts; a speed benefit cannot silently waive allocation, RSS or startup.
An unchanged-route contrast may be statistically unresolved and remains so.
This phase selects among current routes; it is not a relabeled comparison with
published artifacts or a cure for the earlier wider-ARM scratch regression.
The latter needs source-causal resolution and matching-host final proof.

Reference extraction and quantization reuse the checksum-bound source payload
through Q51_SCRATCH_REFERENCE_URL; model work remains remote. Preparation checks
the final Models revision, full Vectors checksum and all actual JAR entries.
The exact tensor inventory must be qwen2 with 170 Q5_1 matrices and 121 F32 tensors; BF16/PTQ are excluded. Runtime diagnostics must report fused grouped attention DISABLED. The two Models classes and one Vectors class changed since the earlier preparation therefore do not execute for this fixture. This is explicitly scoped proof, not a final release freeze or qualification of those excluded methods.
