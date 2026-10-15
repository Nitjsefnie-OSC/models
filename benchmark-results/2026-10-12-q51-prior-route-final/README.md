# Final hybrid Q5_1 routing versus the prior native route

Actual Models d2d0 and Vectors afc were frozen before the later row-scratch fixes. Each host retained 148 requests with nine owned full-logit snapshots, and ten paired timing processes (32 warm requests, three measured). All snapshots agree between routes and between x86/ARM. Native calls are observed: only x86 single-token Q5_1 projections use Rust in the candidate; the prior control routes every supported Q5_1 projection through Rust.

These results identify regressions in the frozen candidate; they are not evidence of complete qualification. The later one-class scratch-fix experiment is separate.

| Host | Metric | Candidate/prior | 95% interval |
|---|---|---:|---|
| x86 | prefillNanos | 0.4437 | [0.4229, 0.4606] |
| x86 | decodeNanos | 1.0652 | [1.0307, 1.1062] |
| x86 | modelNanos | 0.5339 | [0.5125, 0.5514] |
| x86 | javaAllocatedBytes | 1.1726 | [1.1579, 1.1886] |
| x86 | loadNanos | 0.9875 | [0.9675, 1.0086] |
| x86 | peakRssKb | 1.0031 | [0.9951, 1.0110] |
| arm | prefillNanos | 0.1422 | [0.1399, 0.1444] |
| arm | decodeNanos | 0.5410 | [0.5315, 0.5511] |
| arm | modelNanos | 0.1953 | [0.1903, 0.2003] |
| arm | javaAllocatedBytes | 1.6539 | [1.6313, 1.6721] |
| arm | loadNanos | 1.0082 | [0.9886, 1.0271] |
| arm | peakRssKb | 1.0066 | [0.9973, 1.0158] |

Prefill and whole-request latency improve substantially. Nevertheless, x86 decode is slower and both hosts allocate more bytes per request. The row-scratch and unused-Q8-scratch defects were then fixed with focused tests and observable allocation probes. Their actual-model follow-up must be assessed independently; the improvements above do not waive any resource gate.

ARM blocks 0–4 and correctness came from v5; blocks 5–9 came from the prospectively assigned v6 timing shard. Each block compares both routes on the same host. Later duplicate blocks from the original worker are excluded regardless of their values. The original worker was terminated only after all selected commands completed successfully and all 148 numerical cases were verified. The selected-completion receipt is retained.

Payload and artifact identities are in the adjacent `2026-10-12-q51-final-protocol` directory. The candidate backend JAR is `8268cf010a9c7ac8c1cb3da2d1f237d004d84fb53f377482e3c9c84c1556317d`; Vectors is `79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7`. The prior control changes only native Q5_1 eligibility, retaining the final surrounding runtime.
