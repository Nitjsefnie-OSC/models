# CI failure review — 12 October 2026

Status at 21:55 UTC: Models PR 266 is **not green**. Its latest build, model integration,
all six native platforms, framework matrix, ownership check and Apple bridge pass.
Both native Bonsai real-weight jobs still fail. Vectors PR 98 and ModelJars PR 217
have no failed current checks at this snapshot; some Vectors checks remain pending.

| Failure | Finding | Resolution / remaining work |
| --- | --- | --- |
| Models CI [38277441351](https://github.com/integrallis/models/actions/runs/38277441351) | `native-kernels.yml` duplicated the pinned PTX setup owned by the shared action. | Shared setup was consolidated; latest CI 38311553927 passes the guard. |
| Model integration [38153550367](https://github.com/integrallis/models/actions/runs/38153550367) | PTX compilation lacked Rust standard-library Cargo.lock / rust-src. | Shared PTX toolchain installation; latest model integration 38311553651 passes. |
| Native 38299566115 | Apple ARM test incorrectly expected GDN eligibility. | Host-aware expectation fixed in 51346353; all six native jobs now pass. |
| CI 38302233404 / native 38302233341 | A diagnostic assertion still expected the removed grouped Q5_0 route. | f11eb69d tests the current removed-capability contract; latest native matrix passes. |
| Large-model [38177312332](https://github.com/integrallis/models/actions/runs/38177312332), 38311159869 and latest 38311553699 | Native Bonsai transformed-batch logits differ from sequential logits with the published Vectors dependency. Latest x86 maximum difference 0.12134552001953125; ARM 0.09154176712036133. | The unchanged literal test and complete Bonsai fixture pass with actual Models d2d0fb4f plus corrected Vectors core SHA 79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7. CI still consumes published Vectors 0.1.29. Release remains blocked by candidate performance/resource gates; do not suppress this test or pretend the dependency has shipped. |
| Scheduled [38209858410](https://github.com/integrallis/models/actions/runs/38209858410) | HuatuoGPT default Panama exact logits differ between the first and later requests. Generated tokens match, which does not satisfy the exact-logit contract. | Candidate reproduction running with the same pinned model, prompt and assertion. Different host/JDK patch is recorded. The matrix now collects all five configurations even after a failure, and still exits unsuccessfully. |

Raw logs, run inventory, the failed Huatuo artifact and candidate receipts are retained under
`benchmark-results/2026-10-12-runtime-cleanup/ci-failure-review/`.
The inventory is not a claim that every historical log has been individually revalidated.
A fresh green run establishes a fixed failure only for that run's revision and tested scope.

The audit script change was checked with both a fully passing stub matrix and a first-audit
failure: all five configurations execute, with exit codes 0 and 1 respectively. This checks
failure collection only; it does not replace real inference validation.
