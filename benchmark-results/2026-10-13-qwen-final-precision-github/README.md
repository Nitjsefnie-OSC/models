# Final 13f0 Qwen fixed-cohort evidence

Run [38325763396](https://github.com/integrallis/models/actions/runs/38325763396) completed successfully. All 320 x86 and 160 ARM prospective pairs pass the unchanged seven numerical/resource/performance gates. Independent local analyses reproduce every aggregate metric, interval and verdict in the GitHub summary; one retained per-process reconstructed floating-point ratio differs at the final bit across Python hosts.

This result applies only to Models `4f3d3e9a548bf3781ab3d002ff37df0875760e49`, Vectors source `91dd64ae1a4b4bffa534514f10aeaba7e9671574`, core SHA-256 `13f0bd516ede8e4306541c72ef368e94c9bddddd80a99631ff5aa65992fdbba8`, and the pinned native libraries, weights, JDK and settings in the protocol. Original e3e7 observations remain separate.

| Metric (candidate/published) | x86 ratio [95% CI] | ARM ratio [95% CI] |
|---|---|---|
| prefillNanos | 0.828103 [0.827353, 0.828848] | 0.990346 [0.989300, 0.991377] |
| decodeNanos | 0.459829 [0.454079, 0.465520] | 0.437811 [0.435664, 0.440451] |
| requestNanos | 0.704083 [0.701425, 0.706727] | 0.853407 [0.850748, 0.856036] |
| coldRequestNanos | 0.903279 [0.901665, 0.904896] | 0.982213 [0.978232, 0.986061] |
| loadNanos | 0.997056 [0.991966, 1.002072] | 1.002659 [0.995790, 1.009708] |
| peakRssKiB | 0.945494 [0.941309, 0.949749] | 0.950368 [0.943410, 0.957364] |
| javaAllocatedBytes | 0.272312 [0.269798, 0.274737] | 0.543203 [0.538416, 0.547960] |

Load and resource limits allow at most a 1% increase; latency limits allow none. Every interval also meets the frozen maximum width of 0.02. In particular ARM load is not evidence of zero increase.

Candidate full-vocabulary snapshots, greedy tokens and predictions remain exact across all cold/warm/session/process contexts. The published baseline fresh-session fixture failure is retained in every shard and does not invalidate comparison timing. Baseline and candidate quality agree: JEVBench 320/480 x86 and 160/240 ARM; SNIPS 448/480 x86 and 224/240 ARM. These repeated measurements cover 30 distinct tasks, not 1,440 independent quality examples.

Cold-request allocation is present in raw records but was not a frozen acceptance metric. Its separate retrospective extraction is explicitly planning evidence for the next artifact; it does not change this cohort’s original verdict or qualify a replacement runtime.

Raw host files are preserved in six compressed archives with SHA-256 hashes and counts in `raw-archives.json`. The extracted convenience copy is ignored.
