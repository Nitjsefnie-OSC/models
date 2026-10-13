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
| Heap-copy bounded-scratch polynomial V14, source `99745c7c` / `9403f34d` | Correctly frozen Linux x86 first pair: 18.635 µs versus 12.619 µs (1.477×); ARM four-pair geometric ratio 0.879. Cold ratios 0.875 x86 / 0.864 ARM; both warm allocations zero. | Rejected x86 latency regression. |
| Repeated-squaring polynomial V17, continuing source `101936eb` / `d31fa3e0` | Numerical sweeps and 1,053 tests pass. Linux x86 first pair: 12.206 µs versus 10.875 µs (1.122×); ARM four-pair geometric ratio 0.631. Cold allocation decreases; warm allocation remains zero. | Rejected x86 latency regression; no whole-model qualification. |
| Degree-five Chebyshev repeated-squaring V19, continuing source `ef30f052` / `6102074a` | Final actual-JAR numerical proof and 1,053 focused/public tests pass. Preliminary local four-pair warm ratio versus V17 is 0.884, with individual ratios 0.694–1.047. | Candidate only; final-artifact Linux timing and fresh model qualification pending. |

The V7 implementation separates raw float-bit exponent construction from polynomial arithmetic using two bounded 256-float arrays per thread. Local generated code confirms packed arithmetic, but that mechanism alone does not establish acceptable Linux speed. Its independent actual-JAR checks pass both 580,911,106-input FMA-mode sweeps and 80 million random inputs, including cold/hot, exceptional values and multi-tile inputs. Local alias/history and existing public tests pass. These findings do not erase the x86 regression.

## V17 evidence and limits

V17 replaces exponent-bit scratch preparation with a degree-seven approximation of `exp(-x/16)` followed by four squarings. Its fixed ordinary interval is `[-32, 32]`; exceptional blocks use `StrictMath`. The convolution accumulation and overlap-sensitive history ordering are unchanged. Actual generated code contains packed float arithmetic; the helper has no retained workspace. Source `577aa362` was cherry-picked as continuing revision `101936ebaab3e44c22d8b1ae634f4a7f34e87403`; the clean, formatted JAR SHA256 is `d31fa3e05570d83e8bbaa2bc7c7385b608389218ed947676f9179b053dde7c83`.

The final JAR passed two complete 580,911,106-input sweeps over signed finite inputs with magnitude `[2^-28, 104]`, one per FMA policy, plus eight 10-million-input random checks. The proof explicitly selects ordinary bound 32 and includes first-call ordinary inputs and interval boundaries. Maximum absolute SiLU error is `1.9073486328125e-6`; no `2e-6` support-test tolerance, special-value, signed-zero or cold/hot violation was observed. **Maximum ULP error is 286,298 fused / 286,306 unfused near −32**, where outputs are tiny. This is not V14's four-ULP result or an exact-exponential guarantee. Requested 512-bit local runs used an effective 256-bit species; they do not prove native 512-bit execution.

The 52 focused tests and 1,001 public/cold-hot tests have zero failures, errors or skips. Caller tests include shared/overlapping arrays across multiple vector groups and persistent history. A local 19.499 µs diagnostic did not predict the remaining Linux regression. The completed screen measured x86 12.206276 µs versus 10.874880 µs (12.24% slower), despite cold allocation falling to 6,442,976 from 7,491,800 bytes. ARM improved in all four pairs; that does not cancel the x86 failure. V17 changes activation outputs, so prior-candidate Bonsai/Qwen and public-QA results cannot qualify it. Its own [Bonsai real-weight run](https://github.com/integrallis/models/actions/runs/38339278729) now passes all four x86/ARM × pinned/current JDK configurations: 12 tests, zero failures, errors or skips. Uploaded classpath manifests identify the exact `d31fa3e0` JAR. This closes the executed Bonsai fixture assertions, not public-QA or performance qualification; affected Qwen reruns and quality, latency, startup, allocation and memory gates remain required.

Actual evidence is outside project repositories at `audit-artifacts/convolution-f32-proof/candidate-v17/final-formatted`: `independent-proof-receipts.json`, the two exhaustive logs, eight random logs, `test-summary.json`, `local.log`, and `source-reference.json`. The artifact hash was independently checked against the archived JAR.

## Current V19 candidate

A direct degree-five Taylor replacement was rejected before timing: 47,206 fused inputs exceeded the unchanged `2e-6` absolute tolerance, with maximum error `2.86102294921875e-6`. Its source and failed proof remain outside repositories at `audit-artifacts/convolution-f32-proof/candidate-v18-degree5`.

V19 instead derives fixed degree-five coefficients from the Chebyshev expansion of `exp(r)` over `[-3/8,3/8]`, using an 80-digit, 40-term coefficient series before rounding once to float. This fit interval is a coefficient-design choice, not a runtime shortcut or the validation domain. Runtime still uses `r=-x/16`, four squarings, ordinary input interval `[-32,32]`, and the same exceptional-block fallback. No model data or benchmark outputs were used to derive the coefficients.

Source `80f705d7` was integrated as `ef30f0526db24970e1c662cd719842a6f748d9db`. The final formatted/clean-built JAR SHA256 is `6102074aefb48b76537f79b7a1dc89eee5f9e5b8f2fbdcd4d5428df8914871e6`. Its two 580,911,106-input sweeps and 80 million random checks pass the unchanged absolute-error, special-value, signed-zero and cold/hot requirements; 1,053 focused/public tests also pass without skips. Maximum absolute error is `1.9073486328125e-6`; **maximum ULP error is 3,487,670 fused / 3,487,676 unfused near −31.4**, where outputs are tiny. This numerical difference is retained, not described as exact exponential agreement.

The preliminary artifact's four local paired observations show a variable speed improvement, not a Linux verdict. Final-artifact allocation/caller checks report 6,663,280 cold bytes and zero warm bytes; their single timing observation ran concurrently with independent proof and is not comparative evidence. Actual final-JAR receipts are at `audit-artifacts/convolution-f32-proof/candidate-v19-cheb5/final-formatted`. Supported-host screening and fresh affected whole-model/public-QA/resource gates remain required. V17's green Bonsai fixtures do not qualify V19's changed arithmetic.

## Immutable run receipts

- [Rejected scalar component cohort](https://github.com/integrallis/vectors/actions/runs/38332030050). All 2,451 retained files, artifact identities and descriptive partial observations are archived outside repositories at `audit-artifacts/2026-10-13-rejected-experiments/vectors/convolution-925460-run-38332030050`.
- [Scalar exact candidate Bonsai correctness](https://github.com/integrallis/models/actions/runs/38332084523): all four jobs pass. Correctness only.
- [Float-polynomial Bonsai correctness](https://github.com/integrallis/models/actions/runs/38334850182): 12 tests, zero failures, errors or skips across all four configurations. External archive `audit-artifacts/2026-10-13-bonsai-correctness-38334850182` has raw XML and SHA manifests.
- [Affected Qwen correctness](https://github.com/integrallis/models/actions/runs/38334801060): 120 tests, zero failures or skips. Real Qwen3.5 0.8B/4B fixtures; synthetic MoE/Next coverage is explicitly narrower than real-weight qualification.
- [Bounded-scratch Linux screen](https://github.com/integrallis/vectors/actions/runs/38335490960): x86 fails its predeclared first-pair screen; ARM completes its diagnostic observations. External receipts at `audit-artifacts/convolution-screen-38335490960`. Four diagnostic pairs cannot replace the full release confidence protocol.
- [V14 corrected-artifact Linux screen](https://github.com/integrallis/vectors/actions/runs/38337375884): one x86 pair rejects warm latency; ARM completes four diagnostic pairs. `audit-artifacts/convolution-screen-38337375884` retains raw artifacts and `summary.json`. The earlier expected `f144…` hash was copied before formatting; both Linux clean builds and an independent local clean build produced `9403…`. The mismatch was caught before timing, and the corrected artifact was proved and screened separately. No original receipt was relabeled.
- [V17 Bonsai real-weight correctness](https://github.com/integrallis/models/actions/runs/38339278729): raw XML, source/JAR manifests and job status are preserved at `audit-artifacts/convolution-v17-bonsai-38339278729`. Two conditional wrapper jobs are skipped separately from the four executed fixture jobs.
- [V17 Linux screen](https://github.com/integrallis/vectors/actions/runs/38339240447): x86 stops after its predeclared first-pair rejection; ARM completes four pairs. Actual `d31fa3e0` rows and control hashes are retained in `audit-artifacts/convolution-screen-38339240447/paired-observations.json`. Numerical/history/cold-hot candidate violations remain zero; the performance failure remains a release blocker.

The canceled screening setup used `-XX:-UseFMA`, which disables the intrinsic while the library still selects fused arithmetic, inducing software `Math.fma`. That is not the library's unfused mode. The harness now uses its actual `vectors.useVectorFMA=false` and `vectors.useScalarFMA=false` properties. No timing result was taken from the invalid setup.

## Remaining release gates

Qualify a replacement on supported hosts, then run affected whole-model numerical, latency, throughput, startup, allocation and resident-memory checks. Bonsai requires the unchanged 200-case public-QA comparison. The previous QA run timed out before completion; its partial outputs are preserved outside repositories and do not establish a quality verdict.

Publish only after the final exact source passes normal CI and release validation, then update the Models dependency through the dependency-ordered release plan. No new release has been published, and no failed control, canceled cohort or later source change is represented as qualified.
