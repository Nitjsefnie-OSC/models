# Sustained DeltaNet kernel measurements

Follow-up to discovery payload v1; short invocation samples were affected by compilation and
cannot establish a winner. This phase uses JMH with five one-second warmup iterations and ten
one-second measured iterations, three independent forks per arm/shape. External scheduling
rotates/reverses the arm order per repetition. Retain all process means and raw iterations.

Geometry: actual Qwen3.5-4B shape, 16 key heads, 32 value heads, key/value dimension 128. Token
counts 1, 18, 32 (default prefill chunk), 128 and 512. Four workers for parallel Java and native;
serial Java remains an explicit control. Reset identical nonzero state outside every timed call.

Arms: existing Java, head-major Java, fused Java, Java fixed executor, Java polling workers,
and native sequential with Java-aligned scalar math. Polling Java uses the native pool's five
millisecond/4,000-round minimum polling strategy, caller participates, persistent scratch.
This tests a scheduling implementation we can write in Java; no JVM limitation is presumed.
Chunked algorithms already failed exact arithmetic and are not acceptance candidates.

All arms must match the Java output/state before timing. Exercise worker dispatch, concurrent
callers, error propagation, recovery, oversubscription and close before starting JMH. Stop on a
failed benchmark; validate all five parameter rows rather than trusting the process exit status.
Collect allocation alongside average latency. Use process means as independent observations;
report uncertainty rather than interpreting noisy percent differences as a decision.

This phase alone is not a replacement qualification: real model, independent oracle, startup,
memory and task gates remain required. Detecting a regression requires investigating it.

Separate phase budget: $1.25 combined compute/storage, maximum 45 minutes per host. Live price
and quota checks apply. Pin the same JVM/Rust releases as discovery, freeze JAR/source hashes,
upload progress each minute and auto-terminate. The payload must not replace an earlier object.
