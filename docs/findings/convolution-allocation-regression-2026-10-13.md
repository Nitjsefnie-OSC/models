# Convolution activation regressions

**Release is blocked.** Fixing cold/hot activation drift introduced a first-call allocation regression. Replacements have removed that allocation increase but have not yet preserved Linux x86 throughput. No candidate described here is qualified for release.

## Contract and measurement boundary

The published convolution API does not promise exact float casts of `StrictMath.exp`. Its tests require bounded activation error (the support suite permits `2e-6`) and exact history. Exact cold/hot output stability and the unchanged real-model full-logit/reference assertions remain mandatory. A later exact-exponential oracle was an unreleased implementation constraint; revising that oracle requires independently proving the published contract, not relaxing model quality.

Local attribution excluding common Panama initialization measured about 22 MB on the first call for the exact-vector correction versus 5 MB published. The subsequent fresh-process harness includes all first VectorUtil/Panama/helper initialization, reports warmed allocations separately, counterbalances candidate/control order, and keeps identical numerical, latency and resource limits.

## Candidate decisions

| Runtime / core SHA prefix | Measured result | Decision |
| --- | --- | --- |
| Exact-vector correction / `13f0bd51` | Cold allocation approximately 22 MB versus 5 MB published, excluding common initialization. | Rejected resource regression. |
| Certified scalar exact exponential, source `e710f2bd` / `925460ae` | Lower cold allocation, but Linux ARM warm latency 180–182 µs versus about 44 µs; x86 101–102 µs versus 14.7–15.7 µs. | Rejected latency regression; incomplete cohort canceled. |
| Scalar float polynomial, source `825c912e` / `279e006d` | Independent numerical proof passes; all four real Bonsai configurations and affected dense Qwen tests pass. Linux screening setup used an incorrect FMA switch and was canceled before timing. | No supported-host performance verdict. Superseded by vectorizable formulation. |
| Bounded-scratch polynomial, source `8761a8ed` / `e526731b` | First Linux x86 pair: 24.223 µs versus 12.279 µs; cold allocation 6,486,480 versus 7,492,032 bytes. First ARM pair: 40.256 µs versus 42.414 µs; cold 6,916,832 versus 8,081,088 bytes. Both warm allocations zero. | Rejected x86 latency regression; lower allocation and an ARM improvement cannot qualify all supported hosts. |

The last implementation separates raw float-bit exponent construction from polynomial arithmetic using two bounded 256-float arrays per thread. Local generated code confirms packed arithmetic, but that mechanism alone does not establish acceptable Linux speed. Its independent actual-JAR checks pass both 580,911,106-input FMA-mode sweeps and 80 million random inputs, including cold/hot, exceptional values and multi-tile inputs. Local alias/history and existing public tests pass. These findings do not erase the x86 regression.

## Immutable run receipts

- [Rejected scalar component cohort](https://github.com/integrallis/vectors/actions/runs/38332030050). All 2,451 retained files, artifact identities and descriptive partial observations are archived outside repositories at `audit-artifacts/2026-10-13-rejected-experiments/vectors/convolution-925460-run-38332030050`.
- [Scalar exact candidate Bonsai correctness](https://github.com/integrallis/models/actions/runs/38332084523): all four jobs pass. Correctness only.
- [Float-polynomial Bonsai correctness](https://github.com/integrallis/models/actions/runs/38334850182): 12 tests, zero failures, errors or skips across all four configurations. External archive `audit-artifacts/2026-10-13-bonsai-correctness-38334850182` has raw XML and SHA manifests.
- [Affected Qwen correctness](https://github.com/integrallis/models/actions/runs/38334801060): 120 tests, zero failures or skips. Real Qwen3.5 0.8B/4B fixtures; synthetic MoE/Next coverage is explicitly narrower than real-weight qualification.
- [Bounded-scratch Linux screen](https://github.com/integrallis/vectors/actions/runs/38335490960): x86 fails its predeclared first-pair screen; ARM completes its diagnostic observations. External receipts at `audit-artifacts/convolution-screen-38335490960`. Four diagnostic pairs cannot replace the full release confidence protocol.

The canceled screening setup used `-XX:-UseFMA`, which disables the intrinsic while the library still selects fused arithmetic, inducing software `Math.fma`. That is not the library's unfused mode. The harness now uses its actual `vectors.useVectorFMA=false` and `vectors.useScalarFMA=false` properties. No timing result was taken from the invalid setup.

## Remaining release gates

Qualify a replacement on supported hosts, then run affected whole-model numerical, latency, throughput, startup, allocation and resident-memory checks. Bonsai requires the unchanged 200-case public-QA comparison. The previous QA run timed out before completion; its partial outputs are preserved outside repositories and do not establish a quality verdict.

Publish only after the final exact source passes normal CI and release validation, then update the Models dependency through the dependency-ordered release plan. No new release has been published, and no failed control, canceled cohort or later source change is represented as qualified.
