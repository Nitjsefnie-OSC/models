# Isolate BERT's Q5_1 peak-memory regression

Five immutable artifact arms ran on each supported host: the prior Java implementation, the
SIMD/parallel candidate, scalar/parallel, SIMD/serial, and scalar/serial. Only the Q5_1 decoder
and exact row dispatch were changed for the component controls. All other classes and the
Vectors core artifact were identical. Every arm preserved all 82 real SciFact/NFCorpus embedding
vectors exactly. Five counterbalanced process blocks then measured 24 embeddings per process
after ten warmups, with all-thread allocation, model load, and `/usr/bin/time -v` peak RSS.

| Candidate / prior Java | x86 latency | x86 peak RSS | ARM latency | ARM peak RSS |
|---|---:|---:|---:|---:|
| SIMD + parallel | 0.7783 | 1.1496 | 0.6731 | 1.7708 |
| Scalar + parallel | 0.7894 | 1.1080 | 0.6679 | 1.1035 |
| SIMD + serial | 0.8549 | 1.0895 | 0.8864 | 1.4539 |
| Scalar + serial | 0.9935 | 0.9884 | 0.9983 | 0.9921 |

These are geometric process-paired ratios. Complete intervals and other metrics are in
`paired-summary.json`. The five-block x86 scalar/serial RSS interval is wide
[0.8937,1.1172]; it is not a claim of 1% precision. This phase chooses a conservative restoration,
not a qualified new speedup. The scalar/serial route restores the previous computation order
and avoids both new sources of extra memory. Reusing caller-owned decoded row scratch removes
avoidable allocation without changing the output contract.

Production commit `45f666ac` applies that restoration specifically to BERT. Its scalar decoder
and projection helper are active only from `BertForwardPass`; causal projection classes retain
their separate path. The focused source tests pass all 31 cases without skips. A new final phase
runs the actual rebuilt Models artifact, all 82 embeddings, and ten paired process blocks on
both hosts; this diagnostic's results are not silently assigned to that later artifact.

Raw results are retained under `remote-q51-rss-controls-v2` in the GDN evidence directory and
`s3://models-qual-077051030817/results/q51-rss-controls-v2/`. All 30 commands per host succeeded,
and both instances were verified terminated before their slots were reused. An earlier
bootstrap-only attempt failed on an incorrect payload prefix; no test ran under that attempt,
and its failed logs and instance receipts remain retained separately.
