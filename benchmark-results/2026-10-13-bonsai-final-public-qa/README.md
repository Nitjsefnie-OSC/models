# Incomplete final-artifact public QA run

**NOT QUALIFIED.** Run38328345931 hit the orchestration step limit: eight five-case paired blocks exceeded35 minutes. Published/reference and Models arms were individually making progress; one five-case arm took roughly17–28 minutes on the slow hosts. The remaining two jobs were canceled because the required complete200-case cohort could no longer pass. All downloaded partial/complete outputs and the explicit GitHub timeout log are preserved in `incomplete-run-38328345931.tar.gz`; no partial score qualifies the artifact.

The next run must repartition all200 unchanged cases into smaller fixed blocks, allow enough total runtime, retain every arm output, and use the new convolution-fix artifact. This is an orchestration correction, not a change to prompts, sampling, quality thresholds or case selection. Earlier launch details below remain historical.

# Final-build Bonsai public QA — running

[Run 38328345931](https://github.com/integrallis/models/actions/runs/38328345931) uses frozen harness `6a85daa6`, Models `4f3d3e9a`, and Vectors core `13f0bd51`. It reruns all 200 original SQuAD2/BoolQ cases through the ordinary generation loop against the original publisher executable and shared libraries. Prompts, token oracle, generation settings, context, scorer and descriptive quality limits are unchanged. Each five-case pair stays on one host; ten x86 workers handle 20 cases each. The new cohort uses four threads/processors and retains its own provenance. No old timing or quality result is pooled.

All ten preparation artifacts verify the same Java/native binaries, and all 200 prompt-token preflights pass. Answer generation remains running; preparation is not qualification. The archive contains the complete frozen workflow, harness, data and scoring code. It contains no signed URL or credentials.
