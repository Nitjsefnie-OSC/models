# Attention and PTQ physical-width regressions

Status: the narrow-host corrections pass exact-output checks and remove the measured ARM allocation regressions. **The combined component run still fails x86 attention-score latency.** Helper-layout controls are being measured; neither is qualified or integrated as a fix. This report does not grant whole-model or release qualification.

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

The x86 score host is AMD EPYC 9V74 with `UseAVX=3`, `MaxVectorSize=64`, `UseFMA=true` and `UseSSE=4`. The 5.3% score regression remains unresolved. Compiler captures show changed register allocation/scheduling after adding the hardware dispatch even though the wide arithmetic is unchanged; they do not yet establish a sufficient causal explanation or a fix.

The source-only correction was integrated as `d296ae24112a1419d31273b1a327c2cc22a7b4b7`. Its comment correction changes debug line information but not instructions. Standalone diagnostic compilation uses `javac -g`; Gradle also emits `MethodParameters`. Resolved `javap -p -c` instructions match for the two changed classes; artifact hashes are not falsely equated.

## Pending helper comparison

[Run 38345876367](https://github.com/integrallis/models/actions/runs/38345876367), harness `224a1d0277aeb0391428aa4f3af6adf037d48837`, freezes four arms: original baseline, corrected current method, unchanged wide body behind a helper, and the entire original wide method including validation behind a helper. The latter preserves all 359 original instructions and bytecode offsets after normalizing constant-pool indices. The comparison admits CPU/JVM metadata matching the failed EPYC cohort before timing, uses four bounded host attempts, and retains every admitted host's 16 rotating four-arm cycles without outcome-based stopping. Non-admitted hardware is recorded explicitly and supplies no timing verdict.

The eight focused attention tests and baseline/candidate parity checks pass locally. That establishes tested correctness, not a performance fix. The production attention-score path will not be called qualified until the measured regression is resolved.

## Reachability and evidence

Grouped attention is reached by fused/default Granite attention and applicable fallback plans. The scoped Bonsai/Qwen3.5 fixtures use their own `attendHead` implementation, so those quality checks do not measure this score path. Q8 PTQ is reached by Java or unsupported-native routes; a supported native PTQ implementation bypasses it. Qualification must name the executed route.

All raw and rejected-control evidence is outside project repositories at `audit-artifacts/attention-ptq-width`, including immutable source patches, dependency/class hashes, per-process records, JFR, compiler captures, and analysis scripts. Primary receipts are `run-38343576030/descriptive-summary.json`, `integration-source-final.json`, and `score-boundary/original-body-correspondence.json`. No failed experimental implementation is added to the continuing production runtime through this report.
