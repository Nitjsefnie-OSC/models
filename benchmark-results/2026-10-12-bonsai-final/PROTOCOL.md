# Final Bonsai release regression

Rerun all 200 frozen public-QA cases (100 SQuAD2, 100 BoolQ) through the ordinary
Models generation loop and the same pinned Prism publisher runtime. Preserve the
existing case manifest, prompt-token oracle, sampling, context, scoring, and gates.
Run the two arms sequentially on each host; retain every failure and raw output.
This phase changes only the candidate runtime to the final integrated source and
final Vectors candidate. No earlier score certifies the new runtime.

Eight x86 shards contain 25 consecutive cases each. To avoid delaying correctness
comparisons already occupying the main quota, shards 0 and 2 use the separately
verified Spot quota in Virginia, shard 1 uses Oregon on-demand capacity, and the
remaining five use Virginia on-demand capacity. Each pair stays on the same host;
quality scoring is unchanged. Spot interruption is an incomplete shard, never a pass. Each host has a 90-minute
hard shutdown and the whole phase has a $10 ceiling including storage. Launch only
when the observed account quota has room; collect receipts for each owned host.
Record the exact Java/native artifacts, JDK, host, source, input and harness hashes.
Preserve the original publisher binary and its checksum. This is a quality gate;
it does not establish latency portability or prove a JVM limitation.
