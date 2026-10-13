# Convolution cold-allocation regression

The unreleased exact-activation candidate has a **confirmed regression**: approximately22 MB allocated on the first convolution call versus5 MB by published Vectors. Warm allocation being zero does not remove this cold cost. This remains a release blocker until the production replacement passes its supported-host and affected-model checks.

## Production replacement under validation

Vectors runtime source `e710f2bdf074b244d0b8f2b424bb5411f03c8874` produces core SHA256 `925460aebba2767b9f215600fafb1d0e2ad21e6f88391e23d8a0a1ae568de69d`. It restores published-width vector convolution accumulation and applies an exact scalar exponential to each completed vector group. The helper uses Math.exp only where a complete float-domain catalogue proves its float cast equals StrictMath.exp. All ambiguous buckets and inputs outside the certified interval use StrictMath directly. This is a fixed numerical implementation; it does not select a path from benchmark outcomes or warmup state.

The isolated hardened prototype measured about4.18 MB first-call allocation, versus5.10 MB published and22 MB regressed, and zero warmed bytes per call. Those local diagnostic counters excluded basic Panama initialization. The new supported-host gate includes first VectorUtil/Panama/helper initialization. Prototype timing was variable and is not a passing latency verdict.

The production helper, generated catalogue and meaningful alias/special-value tests were independently reviewed. Focused numerical tests passed, including12 fresh-process width/FMA/scalar persistent-history configurations. Actual production-JAR exhaustive proof and local allocation receipts are being retained separately. No prototype result is relabeled as a production measurement.

## Frozen checks in progress

- [Supported-host component allocation and latency](https://github.com/integrallis/vectors/actions/runs/38332030050): source `118c0637495042c7187cfe787ba9499b4c88eafd`; eight hosts covering Linux x86/ARM and pinned/current JDK25;32 matched published/regressed/candidate process cohorts per host. All artifacts build with pinned compiler25.0.3 before selecting measurement runtime. No result-dependent sampling or exclusions. Cold allocation must be no greater than published in every pair; warmed latency retains the upper-ratio-one and confidence-width0.02 gates.
- [Actual Bonsai weights and fresh-session logits](https://github.com/integrallis/models/actions/runs/38332084523): unchanged three-test suite across both architectures and both JDK versions, exact production source above.
- [Normal Vectors CI](https://github.com/integrallis/vectors/actions/runs/38332036587): the new production source and complete repository checks.

Affected Bonsai whole-model paired measurements and the complete200-case public QA rerun remain required. The preceding QA run38328345931 was incomplete because the harness allowed only35 minutes for a five-case paired block; it did not establish a quality verdict. Its outputs and explicit timeout log are retained in `benchmark-results/2026-10-13-bonsai-final-public-qa/`.

No new release has been published. Ordinary Models CI still resolves the published dependency until the upstream fix is qualified and published in dependency order.
