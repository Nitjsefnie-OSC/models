# Final-build Bonsai public QA — running

[Run 38328345931](https://github.com/integrallis/models/actions/runs/38328345931) uses frozen harness `6a85daa6`, Models `4f3d3e9a`, and Vectors core `13f0bd51`. It reruns all 200 original SQuAD2/BoolQ cases through the ordinary generation loop against the original publisher executable and shared libraries. Prompts, token oracle, generation settings, context, scorer and descriptive quality limits are unchanged. Each five-case pair stays on one host; ten x86 workers handle 20 cases each. The new cohort uses four threads/processors and retains its own provenance. No old timing or quality result is pooled.

All ten preparation artifacts verify the same Java/native binaries, and all 200 prompt-token preflights pass. Answer generation remains running; preparation is not qualification. The archive contains the complete frozen workflow, harness, data and scoring code. It contains no signed URL or credentials.
