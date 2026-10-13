# CI failure review — 12 October 2026

The candidate upstream correction now passes all five Huatuo determinism configurations and
all four expanded Bonsai configurations. **Ordinary dependency-pinned Models CI remains red:**
run [38319088829](https://github.com/integrallis/models/actions/runs/38319088829) at
`4f3d3e9a548bf3781ab3d002ff37df0875760e49` passes all nine pure-Java large-model jobs,
including Fin-R1, but fails both native Bonsai jobs. It still resolves published Vectors 0.1.29.
The green candidate runs below use an explicitly recorded unpublished core; they do not establish
that a corrected dependency has shipped or that the remaining performance/resource gates pass.

The following table preserves the earlier failure findings and their evidence. The 21:55 UTC
snapshot had passing Models build, model integration, six native-platform jobs, framework matrix,
ownership and Apple bridge checks, with both Bonsai jobs failing. Subsequent results are recorded
separately below; earlier failures have not been relabeled.

| Failure | Finding | Resolution / remaining work |
| --- | --- | --- |
| Models CI [38277441351](https://github.com/integrallis/models/actions/runs/38277441351) | `native-kernels.yml` duplicated the pinned PTX setup owned by the shared action. | Shared setup was consolidated; latest CI 38311553927 passes the guard. |
| Model integration [38153550367](https://github.com/integrallis/models/actions/runs/38153550367) | PTX compilation lacked Rust standard-library Cargo.lock / rust-src. | Shared PTX toolchain installation; latest model integration 38311553651 passes. |
| Native 38299566115 | Apple ARM test incorrectly expected GDN eligibility. | Host-aware expectation fixed in 51346353; all six native jobs now pass. |
| CI 38302233404 / native 38302233341 | A diagnostic assertion still expected the removed grouped Q5_0 route. | f11eb69d tests the current removed-capability contract; latest native matrix passes. |
| Large-model [38177312332](https://github.com/integrallis/models/actions/runs/38177312332), 38311159869 and latest 38311553699 | Native Bonsai transformed-batch logits differ from sequential logits with the published Vectors dependency. Latest x86 maximum difference 0.12134552001953125; ARM 0.09154176712036133. | The unchanged literal test and complete Bonsai fixture pass with actual Models d2d0fb4f plus corrected Vectors core SHA 79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7. CI still consumes published Vectors 0.1.29. Release remains blocked by candidate performance/resource gates; do not suppress this test or pretend the dependency has shipped. |
| Scheduled [38209858410](https://github.com/integrallis/models/actions/runs/38209858410) | HuatuoGPT default Panama exact logits differ between the first and later requests. Generated tokens match, which does not satisfy the exact-logit contract. | Candidate default-Panama reproduction passed in 144 seconds with the same pinned model, prompt and exact-logit assertion. The same-host published-core control also passes with identical hashes: the local experiment does not reproduce or explain the original CI failure. GitHub workflow 38314297480 tested updated Models with the published core; it failed all four vector variants and passed scalar. The matrix now collects all five configurations even after a failure, and still exits unsuccessfully. |

Raw logs, run inventory, the failed Huatuo artifact and candidate receipts are retained under
`benchmark-results/2026-10-12-runtime-cleanup/ci-failure-review/`.
The inventory is not a claim that every historical log has been individually revalidated.
A fresh green run establishes a fixed failure only for that run's revision and tested scope.

The audit script change was checked with both a fully passing stub matrix and a first-audit
failure: all five configurations execute, with exit codes 0 and 1 respectively. This checks
failure collection only; it does not replace real inference validation.

## Huatuo: published-core failure and completed candidate correction

[38314297480](https://github.com/integrallis/models/actions/runs/38314297480) completed
with failures in default Panama, serial Panama, disabled-FMA Panama and 128-bit Panama.
Scalar passed. All five original reports remain retained. Both local default-core variants
passed, so that local control does not reproduce the GitHub environment's defect.

The subsequent manual diagnostic
[38316803282](https://github.com/integrallis/models/actions/runs/38316803282) **completed all
five independent jobs successfully**: default Panama, serial Panama, disabled-FMA Panama,
128-bit Panama and scalar. The raw reports independently verify identical full-logit hashes
at each of four generated steps across three trials within each configuration. The pinned
Huatuo GGUF SHA-256 is `4643521a184cb26df0f7c57da9aead0c632b286a9aff103c9f9dca4dc059abd7`.
This is repeatability for the recorded prompt/configurations, not a task-quality result or
an assertion that different vector widths/FMA settings produce identical logits. These jobs
ran on different recorded x86 hosts with Temurin 25.0.4.1; they are not paired performance data.

The diagnostic used Models `91d5fa0787b99bf12831fb97da40d9c91e296da5` and Vectors source
`6893a587d7f1d8fd2cd58e85310ef87579b42b25`. Every job records the same built core SHA-256:
`e3e7b0ab79d0630bcad65c4cbe9fd356cf589dd159eba8e6c0aa402db24bd33c`.
That JAR came first on the diagnostic classpath; the distribution's published 0.1.29 JAR,
SHA-256 `21eeae4fcaa56fa26ed45d99610142f794cdec0e1d852894e637263f5bd0b40c`, remained later.
The release pin and exact-logit assertions were unchanged.

The retained local `huatuo-matrix-results.json` and `huatuo-scalar/` receipts describe different,
earlier attempts. Published-control and disabled-FMA attempts exited successfully; serial,
128-bit and scalar attempts hit their 600-second limit with exit 124. Those timed-out attempts
have no completed determinism verdict and are not evidence against the completed GitHub runs.

## Bonsai: ordinary failure and expanded candidate success

The examined ordinary large-model workflow
[38319088829](https://github.com/integrallis/models/actions/runs/38319088829) has nine successful
pure-Java jobs and two failed native Bonsai jobs at Models `4f3d3e9a`. Its conditional upstream
correction job was skipped, as expected for this ordinary dependency-pinned run. Passing the
pure-Java Bonsai fixture does not replace its native batch/session assertions.

The expanded upstream diagnostic
[38323006687](https://github.com/integrallis/models/actions/runs/38323006687), Models
`6b372df631f3ea3b2da3d86409231e86026cb253`, completed all four configurations successfully:
Ubuntu x86 and ARM, each with pinned JDK 25.0.3 and the workflow's JDK 25 selection. Each
configuration's retained JUnit XML records **three tests, zero failures, zero errors and zero
skips**: the two existing native fixture tests and the fresh-session test. The workflow's
unselected ordinary jobs are skipped; those skips are distinct from the zero skipped tests
inside the four executed candidate configurations.

All four provenance records identify Vectors source
`4e8d1374e71788adef07be395560771c12d10f73` and the same `e3e7b0ab…` core SHA-256 shown above.
The original published-core JAR was explicitly replaced for these diagnostic test classpaths.
The fixture checks now pass on the candidate; ordinary CI is not repaired until the qualified
upstream release is published, Models' pin and locks are updated, and the unchanged ordinary
jobs pass using normal dependency resolution. These correctness results do not waive the
candidate's remaining performance, allocation, RSS, startup or model-qualification requirements.

## Retained and checked evidence

The scoped Huatuo import retains the five raw reports and five dependency provenance files,
the local attempt exit summary, and the scalar command/log/timeout receipt. They contain no
model weights, binaries or credential material found during the scoped inspection.
[verification-summary.json](../../benchmark-results/2026-10-12-runtime-cleanup/ci-failure-review/huatuo-candidate-github-38316803282/verification-summary.json)
records SHA-256 hashes and sizes for the original files, the verified repeated-logit checks,
and fetched GitHub job conclusions/source revisions. It also records hashes and parsed counts
for the already retained [expanded Bonsai historical summary and archive index](../../benchmark-results/2026-10-12-bonsai-native-hotfix/README.md).
Raw reports and prior failed/timed-out receipts remain byte-preserved; superseded Bonsai raw payloads are externalized with per-file hashes and the original Git revision in that archive index.
