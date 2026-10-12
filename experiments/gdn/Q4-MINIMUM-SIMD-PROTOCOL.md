# Preserve Q4_K batch rounding without scalar minimum accumulation

The earlier correction aligns batched prefill with single-row decode by separately accumulating
the minimum correction with one scalar FMA per weight block. This experiment preserves that
floating-point order and vectorizes only the exact integer sum of eight groups. The six-bit
minimum multiplied by the sum of 32 signed bytes, summed over eight groups, fits in i32.
No floating-point reduction, activation quantization, weights, or routing changes.

Compare native production 12796ebf with the source candidate on one isolated x86 m6a.4xlarge.
Both arms use the same actual Models JARs, exact activation Vectors a5511d6, worker defaults,
2048 context, weights and two public datasets as production confirmation v5. No ARM arithmetic
changes: the modified function is x86-only. Both artifact arms must pass actual routing and
all 148 cases on both Qwen models with exact full logits, eight-token continuations, prefixes,
and ragged groups. Ten matched JVM process pairs alternate native/SIMD then SIMD/native.
Three measured warm requests per process yield thirty warm requests per arm per model.
Use the existing paired geometric-mean/bootstrap analysis and unchanged no-regression rules.
An inconclusive or slower result does not authorize adoption. This one-variable comparison
isolates correction overhead and cannot replace the broader external-oracle investigation.

One-hour deadline and $1 maximum compute budget; launch only when the live account quota has
room. Keep source, payload and every failure immutable; observe termination after collection.
