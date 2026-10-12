# Independent tensor diagnosis

The exact independent greedy-token gate remains failed and unchanged. This diagnostic locates the
first numerical divergence; it does not qualify a release or modify an oracle to manufacture a pass.

Use pinned llama.cpp a935fbffe1a3d31509c325c116454ab5d56b2eb8 and the actual corrected native Models
artifact. Collect all full logit vectors at the eight greedy steps for the existing nine external
oracle cases on both Qwen sizes, and first-layer intermediate tensors for easy-intent-00. Supply the
identical integer input-token arrays to both engines. Preserve all binaries, source patches, hashes,
commands, raw tensors, and failed comparisons. Tensor observation is diagnostic and excluded from
performance measurements.

On each supported host, run the unchanged independent graph at microbatch 512 and 32, and a separately
labelled diagnostic graph using the publisher's additive L2 epsilon convention at microbatch 32.
No other source change is allowed. The normalized-input projection, convolution, recurrence and final
logits are compared in execution order. Only compare logits while the generated token prefixes remain
identical; report the first divergence and both ranking margins. Different prefixes cannot diagnose
a numerical error at the next token.

Models Q4_K and Q6_K projections quantize activations to Q8_K, as does the pinned external oracle.
A proposed float-activation explanation was rejected by source inspection before deployment.
Any remaining explanation must be supported by the tensor measurements.

Each worker has a one-hour hard deadline, with a $2 combined two-host ceiling. Fleet ownership,
immutable payloads, current quota and current pricing follow the campaign runbook.
