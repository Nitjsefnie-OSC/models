# Real affected-family consistency fixtures

This bounded diagnostic starts with the smallest catalog `qwen35moe` artifact,
Ornith, on one supported host at a time. The larger pinned `qwen3next` artifact
is prepared for an explicit subsequent dispatch after the smaller case has been
inspected. Both model files are memory-mapped; expert matrices remain quantized
segment views. A standard runner can demand-page weights larger than RAM. This
is not a meaningful host for performance or resident-memory qualification.

Before inference, the harness checks the exact runtime source revisions, full
backend/core JAR hashes, GGUF byte count/SHA and actual architecture/expert
metadata. It uses the first unchanged JevBench and SNIPS prompts already frozen
in the paired harness. Each Java and Java+Rust process checks three fresh sessions
per prompt, every logit at three steps, implicit reset, and serial versus chunked
prefill. Snapshots are cloned immediately. Native and Java outputs are retained
independently: cross-backend equality and publisher task quality are not asserted
by this consistency fixture. No model-quality qualification is claimed.

Each backend process is bounded to 45 minutes, the model download to 30 minutes,
and each job to 135 minutes. The workflow removes only unused tools from its
fresh ephemeral runner, requires model size plus 6 GiB free disk before download,
and retains completed phase receipts even if a subsequent phase fails. GGUF files
are never uploaded as results. A missing, skipped or incomplete assertion group
fails the final completion check. All runtime results stay outside the repository.
