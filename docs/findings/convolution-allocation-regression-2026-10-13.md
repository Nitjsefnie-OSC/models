# Convolution cold-allocation regression

The unreleased exact-activation candidate has a **confirmed regression**: approximately 22 MB allocated on the first convolution call versus 5 MB by published Vectors. Warm allocation being zero does not remove this cold cost. This remains a release blocker. The first production replacement was rejected for a separate warm-latency regression; a deterministic vector replacement is being investigated.

## Rejected scalar replacement

Vectors runtime source `e710f2bdf074b244d0b8f2b424bb5411f03c8874` produces core SHA256 `925460aebba2767b9f215600fafb1d0e2ad21e6f88391e23d8a0a1ae568de69d`. It restores published-width vector convolution accumulation and applies an exact scalar exponential to each completed vector group. The helper uses Math.exp only where a complete float-domain catalogue proves its float cast equals StrictMath.exp. All ambiguous buckets and inputs outside the certified interval use StrictMath directly. This is a fixed numerical implementation; it does not select a path from benchmark outcomes or warmup state.

The isolated hardened prototype measured about 4.18 MB first-call allocation, versus 5.10 MB published and 22 MB regressed, and zero warmed bytes per call. Those local diagnostic counters excluded basic Panama initialization. The new supported-host gate includes first VectorUtil/Panama/helper initialization. Prototype timing was variable and is not a passing latency verdict.

The production helper, generated catalogue and meaningful alias/special-value tests were independently reviewed. Focused numerical tests passed, including 12 fresh-process width/FMA/scalar persistent-history configurations. Actual production-JAR exhaustive proof and local allocation receipts are being retained separately. No prototype result is relabeled as a production measurement.

## Check outcomes

- **Canceled and rejected for measured slowdown.** [Supported-host component allocation and latency](https://github.com/integrallis/vectors/actions/runs/38332030050): source `118c0637495042c7187cfe787ba9499b4c88eafd`; eight hosts covering Linux x86/ARM and pinned/current JDK25; 32 matched published/regressed/candidate process cohorts per host. All artifacts build with pinned compiler 25.0.3 before selecting measurement runtime. No result-dependent sampling or exclusions. Cold allocation must be no greater than published in every pair; warmed latency retains the upper-ratio-one and confidence width 0.02 gates.
- **All four jobs passed; correctness only.** [Actual Bonsai weights and fresh-session logits](https://github.com/integrallis/models/actions/runs/38332084523): unchanged three-test suite across both architectures and both JDK versions, exact production source above.
- [Normal Vectors CI](https://github.com/integrallis/vectors/actions/runs/38332036587): the new production source and complete repository checks.

Affected Bonsai whole-model paired measurements and the complete 200-case public QA rerun remain required. The preceding QA run 38328345931 was incomplete because the harness allowed only 35 minutes for a five-case paired block; it did not establish a quality verdict. Its outputs and explicit timeout log are retained in `benchmark-results/2026-10-13-bonsai-final-public-qa/`.

No new release has been published. Ordinary Models CI still resolves the published dependency until the upstream fix is qualified and published in dependency order.

## Rejection and external preservation

The first completed ARM checkpoints measured 180–182 µs warm convolution versus about 44 µs published, a roughly 4.1-fold slowdown. Retained x86 observations measured 101–102 µs versus 14.7–15.7 µs, roughly 6.5–6.9-fold slower. Cold allocation decreased, but this candidate cannot ship. The cohort was canceled after the regression was clear; no complete confidence-bound verdict is claimed. The affected whole-model cohort was also canceled before complete timing pairs.

All 2,451 component receipts are preserved outside the project repositories under `audit-artifacts/2026-10-13-rejected-experiments/vectors/convolution-925460-run-38332030050`, with a SHA256 manifest. Failed experimental source/result payloads have been removed from the final project trees; concise decision and provenance notes remain.

The published convolution API does not promise exact float casts of StrictMath.exp. Published tests require bounded activation error and exact history; cold/hot full-logit stability is still mandatory. The canonical exponential requirement was an unreleased implementation choice. A deterministic vector alternative must preserve the published numerical contract, unchanged model reference tests, and every performance/resource gate; newer exact-oracle assertions may only be revised with explicit numerical justification, never silently.
