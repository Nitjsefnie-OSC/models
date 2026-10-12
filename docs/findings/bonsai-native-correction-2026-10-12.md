# Bonsai native batch parity correction

## Current result

The combined correction passes the expanded native suite on Linux x86 and ARM,
each with Temurin 25.0.3 and 25.0.4.1, in
[38323006687](https://github.com/integrallis/models/actions/runs/38323006687).
Downloaded JUnit reports confirm **three tests per configuration, zero failures,
errors or skips**. All four jobs built the same core JAR:
`e3e7b0ab79d0630bcad65c4cbe9fd356cf589dd159eba8e6c0aa402db24bd33c`.
The final source retains exactly that artifact; later Vectors changes add tests only.

The new test runs in an isolated JVM with four visible CPUs. It retains owned
snapshots of all vocabulary logits at eight continuation positions across three
fresh sessions, checks every publisher token, then compares implicit and reset
sessions. Published Vectors fails locally despite matching publisher tokens;
the corrected artifact passes. These receipts are retained in
`benchmark-results/2026-10-12-bonsai-fresh-session-regression/`.

Release status remains **unpublished**. Normal Models CI still uses the declared
published dependency. Performance/resource gates and the final release dry run
remain open; the dry run is [38323832193](https://github.com/integrallis/vectors/actions/runs/38323832193).
A candidate-classpath pass does not complete the downstream dependency fix.

## Confirmed failure

Models' normal dependency graph resolves Vectors 0.1.29. The unchanged native Bonsai
fixture compares every vocabulary logit after sequential prefill and batched prefill,
including three continuation positions. The latest released-core x86 job reports a
maximum absolute error of 0.12134552001953125. The corresponding ARM failure has also
been reproduced. Matching the selected token does not satisfy this test.

The first BF16-only attribution was incorrect. The exact BF16-only artifact
`cb3dab3d` fails on both architectures in
[38319438508](https://github.com/integrallis/models/actions/runs/38319438508), with
the same x86 error. The broader core `6893a587` passes both original tests on both
architectures in [38319081991](https://github.com/integrallis/models/actions/runs/38319081991).
The earlier local success used an unmanifested mutable classes directory and cannot
establish an isolated BF16 cause.

The exact artifact header says `general.architecture=qwen35`. It contains 402
PTQ1_0 tensors, 353 F32 tensors, and 96 BF16 tensors (the recurrent alpha/beta
projections, each 48 by 5120). The independent header receipt is retained in
`benchmark-results/2026-10-12-bonsai-native-hotfix/bonsai2-actual-header.json`.
The artifact's SHA-256 remains
`53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3`.

An intermediate diagnosis incorrectly treated this artifact as Qwen3/Llama and
excluded its recurrent activation operations. That diagnosis was wrong.
`Qwen35ForwardPass` calls all three affected APIs in its sequential, batched and
grouped routes: `causalDepthwiseConv1dSilu`, `sigmoidAndScaledSoftplus`, and
`swiGlu`. The published implementations use vector EXP/LOG operations whose
rounding can differ after compilation.

The SwiGLU-only candidate `3f4db6c8` also fails both architectures in
[38320444703](https://github.com/integrallis/models/actions/runs/38320444703):
maximum absolute error 0.10480070114135742 on x86 and 0.11671257019042969 on ARM,
both at continuation zero. Each host passed the publisher-oracle/fresh-session
case and failed the full-vocabulary parity case. Its core JAR SHA-256 is
`f2d4e7a23ca4cae79cf8795acf06f98c6a56f412cd0e8078312c9377d01e2c6f`.
This experiment changed only one of the three activation operations the model
actually uses; it cannot settle the complete activation correction.

The complete activation correction **without BF16 changes**, `8495fbf9`, passes
both original tests on both architectures in
[38321333816](https://github.com/integrallis/models/actions/runs/38321333816).
Each host reports two tests, zero failures/errors/skips, and exactly zero absolute
and scaled logit difference at all three continuation positions. Both built the
same core JAR, SHA-256
`4d447d40ee0c5dd3942d0f9045f1d7edb4fa31a11b9029d1d6a512089cce7c58`.
This establishes sufficiency for these two original test cases only. It does
not establish full-logit repeatability across fresh sessions: the original
fresh-session assertion checks the selected token, not every logit. It also does
not establish which individual recurrent activation change is indispensable.

| Core under test | x86 original tests | ARM original tests |
| --- | --- | --- |
| Published 0.1.29 | Batch parity fails | Batch parity fails |
| BF16 only, `cb3dab3d` | 1 pass, 1 fail | 1 pass, 1 fail |
| SwiGLU only, `3f4db6c8` | 1 pass, 1 fail | 1 pass, 1 fail |
| Complete activations only, `8495fbf9` | 2 pass, no skips | 2 pass, no skips |
| Complete activations + BF16, `6893a587` | 2 pass, no skips | 2 pass, no skips |

The original test source and exact assertions were not changed in these experiments.

## Broader fresh-session failure and complete correction

The complete-activation-only artifact fails the broader full-vocabulary fixture on
both architectures in [38321902032](https://github.com/integrallis/models/actions/runs/38321902032).
All four Bonsai jobs fail with `fresh session changed after compilation`, as do the
four Qwen BF16 jobs. These are real numerical failures; the workflow correctly
blocked timing. Matching selected tokens had concealed different full logits.

The combined BF16 + complete-activation runtime passes those broader fixtures in
[38321278750](https://github.com/integrallis/models/actions/runs/38321278750).
Whole-model timing/resource analysis is still pending for Bonsai. The corrected
release candidate therefore needs both changes. The permanent isolated-JVM native
fixture now checks complete snapshots across fresh sessions, with all original
assertions retained.

The new activation sequence test also exposed a test-oracle bug in scalar CI:
it used the SIMD softplus and fused-convolution contract even when the selected
provider was scalar. The corrected oracle checks the actual provider, preserves
exact comparisons, and adds explicit scalar runs. This does not change production
arithmetic or relax the original Bonsai tests.

## Validation and release boundary

The separate manual upstream-validation workflow runs the original two Bonsai tests
with one explicitly selected Vectors core. It checks an immutable source revision,
builds its JAR, removes the released core from that test's runtime classpath, and
records the replacement, hashes and complete classpath beside the JUnit results.
It cannot be used for publication or other Gradle tasks. Normal CI remains on the
declared released dependency. A green diagnostic is not a fixed published chain.

The first two-architecture validation is
[38319081991](https://github.com/integrallis/models/actions/runs/38319081991), using
Vectors `6893a587d7f1d8fd2cd58e85310ef87579b42b25`. Its result must be read from the
completed jobs and retained XML; a running job is not a pass.

The broader Vectors candidate also contains unrelated changes with unresolved
performance gates. A narrow hotfix is being evaluated separately. The smallest
fixed-tree patch already has historical kernel regressions, so it cannot inherit
the optimized implementation's performance evidence. Any optimized hotfix must
retain its actual source, JAR hashes and matching measurements.

## Completion criteria

1. Pass the unchanged Bonsai assertions on x86 and ARM with the exact upstream artifact.
2. Complete the upstream correctness, affected performance and release checks.
3. Publish and independently fetch the corrected Vectors artifacts from Maven Central.
4. Update Models' declared Vectors version, documentation attribute and dependency locks.
5. Pass both normal Bonsai jobs without a classpath replacement, and validate the
   resolved dependency graph.

No tolerance relaxation, skipped case, warm-up-only workaround or disabled native
route is part of this correction.
