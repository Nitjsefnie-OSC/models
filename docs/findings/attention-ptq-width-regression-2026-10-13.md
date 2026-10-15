# Attention and PTQ physical-width regressions

Status: the final narrow-host corrections pass exact-output and allocation checks on both architectures. ARM passes its component gates. **Final x86 latency non-regression remains unresolved:** the completed five-host cohort has near-baseline point estimates but confidence upper bounds above one. The earlier 5.3% slowdown is retained as a failed earlier source. This report does not grant whole-model or release qualification.

The original code maintained eight logical reduction lanes using physical 256-bit vectors even on a JVM whose preferred width is 128 bits. Those logical lanes are needed for reproducibility; physical emulation is not. The initial same-host [diagnostic](https://github.com/integrallis/models/actions/runs/38341564816) measured 634,880 allocated bytes per score call, 163,200 per softmax call and 653,312 per Q8 PTQ call on ARM. Separate JFR records attribute the allocation to unsupported-vector fallback objects. The x86 vector routes did not show that warmed allocation problem.

The correction preserves the original eight floating-point chains and fold. Attention scores use two native 128-bit halves on narrow hosts. Softmax separates its existing deterministic polynomial-exp array pass from the pinned sum so vector values stay within compiled boundaries. Narrow-host PTQ uses primitive exact integer accumulation for Q8 input, and eight primitive FMA chains for the public float-input API. The wide-host arithmetic remains unchanged. No warmup-dependent route, retained scratch buffer, or new public switch is introduced. The public float PTQ routine is retained: absence of internal callers does not establish absence of external consumers.

Earlier native-128 PTQ controls improved warm allocation but increased cold allocation; they were rejected and retained outside repositories. A first split softmax still allocated 8,288 bytes per call; that control was also superseded. Those failures are not erased by the later results.

## Completed corrected-source comparison

[Run 38343576030](https://github.com/integrallis/models/actions/runs/38343576030) measured source `7ad622a404511e178201eae1ad7f9fc97287d08e` against baseline `c95e243d0335657958c4f254d45cc9a1ff83f2f5`. Each kernel has 16 same-host fresh-JVM triplets, 2,000 warm calls and five measured blocks of at least 250 ms. All observations, including early block allocations, are retained. Exact cold/hot signatures and 2,328 baseline/candidate cases per width configuration pass. Default and forced-128 configurations execute separately.

| Kernel | ARM latency ratio | ARM cold-allocation ratio | x86 latency ratio, 95% interval |
| --- | ---: | ---: | --- |
| Attention score | 0.01004 | 0.85675 | **1.05296 [1.00592, 1.10385]** |
| Softmax | 0.02603 | 0.98818 | 0.99259 [0.98294, 1.00158] |
| Q8 PTQ | 0.03772 | 0.08680 | 0.98911 [0.98298, 0.99599] |
| Float PTQ | 0.03060 | 0.08192 | 0.98896 [0.98366, 0.99365] |

These intervals are descriptive paired-process bootstrap estimates, not a substitute for declared release precision or whole-model gates. Warm per-process median allocation is zero for every final ARM kernel. On x86, some early softmax and float-PTQ measurement blocks still allocate while compilation settles; the raw blocks are retained, so this is not a claim that every measurement block allocates zero bytes.

The x86 score host is AMD EPYC 9V74 with `UseAVX=3`, `MaxVectorSize=64`, `UseFMA=true` and `UseSSE=4`. That earlier source has a measured 5.3% score regression; the later frozen-source results below do not erase it. Compiler captures show changed register allocation/scheduling after adding the hardware dispatch even though the wide arithmetic is unchanged; they do not yet establish a sufficient causal explanation or a fix.

The source-only correction was integrated as `d296ae24112a1419d31273b1a327c2cc22a7b4b7`. Its comment correction changes debug line information but not instructions. Standalone diagnostic compilation uses `javac -g`; Gradle also emits `MethodParameters`. Resolved `javap -p -c` instructions match for the two changed classes; artifact hashes are not falsely equated.

## Completed helper comparison

[Run 38345876367](https://github.com/integrallis/models/actions/runs/38345876367), harness `224a1d0277aeb0391428aa4f3af6adf037d48837`, freezes four arms: original baseline, corrected current method, unchanged wide body behind a helper, and the entire original wide method including validation behind a helper. The latter preserves all 359 original instructions and bytecode offsets after normalizing constant-pool indices. The comparison admits CPU/JVM metadata matching the failed EPYC cohort before timing, uses four bounded host attempts, and retains every admitted host's 16 rotating four-arm cycles without outcome-based stopping. Non-admitted hardware is recorded explicitly and supplies no timing verdict.

One admitted EPYC 9V74 host completed all 16 cycles; three other attempts were excluded by CPU/JVM metadata before timing. The current corrected method measured 1.00330 versus baseline, interval [0.97367, 1.03351], so the earlier 5.3% slowdown was not precisely reproduced in this cohort. The body-only helper measured 1.02021 [0.99402, 1.05075] and is rejected. The full-original-method helper measured 0.98069 [0.94224, 1.01905] against baseline, and 0.97747 [0.94985, 1.00264] against current. It has the best point estimate, but the intervals still permit a small slowdown; it is not performance-qualified. Its cold allocation ratio is 1.00733 and maximum measured warm block allocation is 0.002 bytes per call.

The eight focused attention tests and all baseline/candidate parity checks pass. The minimal source-only candidate is `48ead2163f42c8c697ea0818f2ad5e6f85dcb251`, equivalent to measured `61a47f54`, changing only the score helper layout. A prospective fixed comparison is required to resolve latency and precision before qualification. Raw admitted and non-admitted receipts plus paired analysis remain at `audit-artifacts/attention-ptq-width/score-boundary-run-38345876367`.

## Follow-up cold cost and mixed-caller correction

A five-triplet local allocation attribution isolates the full-body helper's first-call growth: class loading increases from 31,656 to 50,992 bytes, while the actual first score call remains 1,822,552 bytes in both arms. A lazy nested narrow-host implementation removes the unnecessary wide-host code loading. Replacing the three fixed shuffle-construction lambdas with primitive permutation arrays removes bootstrap initialization without changing any permutation or reduction arithmetic. The combined local first-call comparison measures 2,624,584 bytes versus 2,692,048 baseline. These are explicitly local component observations, not a Linux or whole-model verdict.

The permanent six-kernel mixed-caller guard then reproduces **2,112 bytes per softmax call at 128 bits under `-Xbatch`**. The ordinary-compilation path passes, so the isolated zero-median finding did not cover this caller/compiler context. JFR attributes the allocation to vector sums inside `expAndSumPinned128`; compiler logs show the sum method inlined into softmax while its vector-returning reduction boundary remains outlined. The fix retains the same eight logical chains and fold using primitive floats after the unchanged array-only exponential phase. No assertion threshold is increased.

The final source `d72380a39d9bbb56fd2d4b1129902625a33f97c5`, source-only integration correction `3c592a0a850320ade4adfeb66227621246557b7b`, passes all eight focused tests and 2,328 exact parity cases in each default/forced128 mode. The reproduced six-kernel mixed caller now measures zero bytes per call at 128/256 bits with both ordinary compilation and `-Xbatch`. The local and integration backend-Java JAR both hash `afe2cb1888c77697415f0132be40a8ea0fa1744e56faa61fae29795faa8b30f9`. Raw failed and corrected observations remain under `audit-artifacts/attention-ptq-width/cold-attribution`.

[Powered final validation](https://github.com/integrallis/models/actions/runs/38347192761), harness `451163341f66a6cacac1895a7f5452452234de38`, freezes 16 x86 hardware-admission attempts with 128 score pairs per admitted host and four ARM hosts with 16 score pairs each; every host also records 16 softmax pairs. At least four matching x86 hosts/512 pairs are required. The sample rationale starts from retained log-ratio variance, increases the independent-fork estimate, and explicitly resamples both host and paired-process levels. Every admitted host and failed/non-admitted attempt is retained. The completed gate results are recorded below; the local fix alone was never represented as a completed performance pass. The queued predecessor `38347144006` was canceled after detecting that copied four-arm ordering did not match the new strict two-arm alternation; no measurements from that canceled setup qualify this run.

## Completed prospective powered validation

[Run 38347192761](https://github.com/integrallis/models/actions/runs/38347192761) completed every one of its 20 fixed host attempts. Five admitted EPYC 9V74 hosts supplied 640 score pairs and 80 softmax pairs; four Neoverse-N2 hosts supplied 64 pairs per kernel. Eleven metadata non-admissions remain recorded. No attempt was dropped based on timing. The exact source, per-file hashes, cold/hot signatures and complete pair counts pass verification.

| Host / kernel | Latency ratio, hierarchical 95% interval | Cold-allocation ratio | Declared result |
| --- | --- | ---: | --- |
| x86 score | 0.999981 [0.989015, 1.010079] | 0.972540 | **NOT_PASSED**: upper bound exceeds 1; interval width 0.021064 exceeds 0.02 |
| x86 softmax | 0.997904 [0.988999, 1.006340] | 0.979556 | **NOT_PASSED**: upper bound exceeds 1 |
| ARM score | 0.010096 [0.010045, 0.010149] | 0.827410 | Component gates passed |
| ARM softmax | 0.027369 [0.027251, 0.027473] | 0.925896 | Component gates passed |

Every individual cold-allocation pair improves. Warm per-process median allocation is zero for both final kernels; this does not mean every early block is allocation-free. The maximum candidate blocks are 0.002 bytes/call for x86 score, 394.843 for x86 softmax, 0.008 for ARM score and 69.439 for ARM softmax. All blocks remain in the archive. The separate mixed-caller permanent guard also passes under ordinary compilation and `-Xbatch`.

The final x86 score point estimate is effectively unchanged from baseline. The observed interval does not establish a remaining slowdown, but it also does not meet the declared strict non-regression requirement. The earlier 5.3% failure remains a historical failure of the earlier source, not a claim that the final source is 5.3% slower. No confidence gate is relaxed and no extra samples are appended to this completed cohort. Overall component qualification remains **NOT_PASSED**. Whole-model and release requirements remain separate.

Raw observations, all 20 admission records, immutable protocol/source identities, analysis and completion summary are outside the repository at `audit-artifacts/attention-ptq-width/powered-run-38347192761`. The analysis resamples both hosts and matched process pairs with equal host weighting; it does not pool all forks as independent hosts.

## Generated-code correspondence and rejected controls

[Compiler-only run 38350203624](https://github.com/integrallis/models/actions/runs/38350203624) captures every byte of final C2 code on two admitted EPYC 9V74 hosts. Both score kernels have the same 672-instruction, 3,680-byte layout and register allocation after address normalization. Separate compiler logs verify that the public hardware-dispatch wrapper inlines into the hot probe caller. Softmax is 984 instructions and 4,808 bytes in both arms: one host matches normalized layout; the other only swaps two zero-vector-length blocks, unused by the 515-element workload. External runtime/object/table addresses are enumerated rather than equated, and their live referents were not dumped. This establishes scoped instruction-path correspondence, not full binary identity or a passing statistical verdict. No ongoing wide-path regression is demonstrated by the final evidence.

Seven bounded local layout controls retain exact outputs but regress allocation or latency: half-vector extraction via cast or reinterpret, zero-filling slices, primitive lane extraction, paired exponential unrolling, and two-head tiles with four or two rows. None is promoted. Sources, compiled classes, fixed paired observations and rejected decisions remain outside repositories. The first compiler capture retains its detected stdout-interleaving gaps; the corrected run uses the existing isolated-output wrapper and verifies complete byte coverage. Four intervening attempts supply only metadata non-admissions. The fixed eight-attempt final diagnosis retains all six non-admitted hosts alongside its two captures. No performance samples were appended to the completed 640-pair cohort.

Receipts: `audit-artifacts/attention-ptq-width/final-compiler-38350203624/instruction-correspondence.json`, `caller-inlining.json` and `assembly-summary.json`.

## Reachability and evidence

Grouped attention is reached by fused/default Granite attention and applicable fallback plans. The scoped Bonsai/Qwen3.5 fixtures use their own `attendHead` implementation, so those quality checks do not measure this score path. Q8 PTQ is reached by Java or unsupported-native routes; a supported native PTQ implementation bypasses it. Qualification must name the executed route.

All raw and rejected-control evidence is outside project repositories at `audit-artifacts/attention-ptq-width`, including immutable source patches, dependency/class hashes, per-process records, JFR, compiler captures, and analysis scripts. Primary receipts are `run-38343576030/descriptive-summary.json`, `integration-source-final.json`, and `score-boundary/original-body-correspondence.json`. No failed experimental implementation is added to the continuing production runtime through this report.
