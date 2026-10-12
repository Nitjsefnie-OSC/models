# Final Q5_1 scratch fix: full actual-model result

The only changed JAR entry is TensorOps.class: final row-cache and unused-Q8-buffer fixes versus the frozen d2d0 hybrid route. Both variants use identical native routing and surrounding afc runtime. This is a component experiment, not a newly rebuilt complete release. The tested class source matches final integration source.

Both hosts completed all148 requests with nine owned full-logit snapshots and ten counterbalanced paired processes, each with32 warm and three measured requests. All cohort vectors agree across hosts and variants; all350 warm/timed requests per host also agree exactly. No cases or outlier pairs were dropped.

| Host | Metric | Fixed/prior hybrid | 95% interval |
|---|---|---:|---|
| x86 | prefillNanos | 1.0063 | [0.9493, 1.0732] |
| x86 | decodeNanos | 1.0416 | [0.8734, 1.2437] |
| x86 | modelNanos | 1.0061 | [0.8992, 1.1223] |
| x86 | javaAllocatedBytes | 0.8607 | [0.8499, 0.8706] |
| x86 | loadNanos | 1.0072 | [0.9965, 1.0195] |
| x86 | peakRssKb | 0.9870 | [0.9399, 1.0181] |
| arm | prefillNanos | 1.0363 | [1.0107, 1.0654] |
| arm | decodeNanos | 1.0103 | [0.9878, 1.0411] |
| arm | modelNanos | 1.0257 | [1.0028, 1.0539] |
| arm | javaAllocatedBytes | 0.6236 | [0.6166, 0.6323] |
| arm | loadNanos | 0.9964 | [0.9878, 1.0063] |
| arm | peakRssKb | 1.0145 | [1.0016, 1.0269] |

## Verdict

The definite scratch-allocation defects are fixed and actual-model allocation is lower. Nevertheless, this code form does not clear all performance gates: ARM prefill, whole-request latency and peak RSS regress; x86 intervals remain too wide to establish no regression. Do not call this candidate release-qualified. The cause of the latency/RSS changes remains unresolved; the allocation reduction alone is not sufficient evidence to select it.

The earlier blanket-native comparison is retained separately in `2026-10-12-q51-prior-route-final`. Its `prior` label means blanket native, whereas this experiment’s `prior` label means the frozen hybrid route. These controls must not be conflated. Ratios from distinct host experiments are not silently combined into a final release claim.

Payload SHA256: `06adff0d10bf5986fa2d532d4134201989293b573c45ab000238bf48c59f84b5`. Cloud receipts and exact payload hashes are in `2026-10-12-q51-final-protocol`. Both workers had15-minute caps and were launched before22:15; termination was requested after all required results were collected.
