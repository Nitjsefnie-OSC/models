# BF16-only candidate: complete supported-host result

Frozen source cb3dab3dd35c7f3209b41d833c878a744620e77c; actual core SHA256 107b807f5dfd3ec420ebbdfd72c3e825240b224861fcb2f7d940683c5843fd96. Workflow38319356761 completed both architectures and retained all12 process pairs. No retry or discarded samples.

**Not qualified.** Every latency confidence interval is below1, but five cases fail the prospectively frozen maximum0.02 interval-width gate. ARM small prompt batching also increases absolute allocation. The actual Bonsai fixture fails with this candidate; activation-only testing subsequently identified that separate fix.

| Host | Operation | Shape | Ratio [95% CI] | Precision gate | Baseline → candidate B/op |
|---|---|---|---|---|---|
| x86 | mappedBfloat16MatVec | 151936x896 | 0.337399 [0.335826, 0.339094] | PASS | 187.880 → 103.729 |
| x86 | mappedBfloat16MatVec | 4864x896 | 0.445121 [0.426495, 0.461376] | NOT PASSED | 233476.547 → 41.975 |
| x86 | mappedBfloat16MatVec | 48x5120 | 0.793753 [0.790323, 0.797076] | PASS | 2304.312 → 2304.248 |
| x86 | mappedBfloat16PromptBatch | 151936x896 | 0.624468 [0.610839, 0.638303] | NOT PASSED | 1212.538 → 741.984 |
| x86 | mappedBfloat16PromptBatch | 4864x896 | 0.629783 [0.607376, 0.654259] | NOT PASSED | 84.604 → 71.079 |
| x86 | mappedBfloat16PromptBatch | 48x5120 | 0.567392 [0.565132, 0.569489] | PASS | 9.512 → 5.406 |
| arm | mappedBfloat16MatVec | 151936x896 | 0.265234 [0.262700, 0.268113] | PASS | 247.301 → 106.261 |
| arm | mappedBfloat16MatVec | 4864x896 | 0.591125 [0.516396, 0.675637] | NOT PASSED | 175108.531 → 42.708 |
| arm | mappedBfloat16MatVec | 48x5120 | 0.985722 [0.983269, 0.988261] | PASS | 1728.232 → 0.225 |
| arm | mappedBfloat16PromptBatch | 151936x896 | 0.972440 [0.969124, 0.975657] | PASS | 811.675 → 751.758 |
| arm | mappedBfloat16PromptBatch | 4864x896 | 0.958726 [0.945964, 0.971306] | NOT PASSED | 72.094 → 71.106 |
| arm | mappedBfloat16PromptBatch | 48x5120 | 0.948628 [0.947889, 0.949392] | PASS | 5.033 → 52.782 |

x86 host: AMD EPYC9V74, four CPUs. ARM host: Neoverse-N2, four CPUs; its recorded hardware flags include sve,sve2,svebf16. Both use Temurin25.0.3. See exact host.txt receipts.

ARM ordinary-matvec variability is in the candidate: five forks measure approximately248–252µs, seven approximately423–427µs, against stable baseline568–587µs. Cause is not established. All forks remain included. The small-batch allocation rises5.033→52.782 B/op; the unchanged focused allocation contract passes, but that does not erase the increase.

Artifacts retain actual baseline/candidate JARs, all JMH commands, all24 raw arm outputs per host, analysis, host data and focused JUnit reports. No inference is made about whole-model performance or unmeasured hardware.


Historical raw host/JMH/test receipts are preserved in the external archive indexed by [archive.json](archive.json). This report does not describe the current candidate.
