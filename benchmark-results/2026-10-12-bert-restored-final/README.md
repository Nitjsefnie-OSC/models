# Final restored BERT route: complete measured evidence

Actual Models d2d0fb4f and Vectors afc4ebd8 were compared with the retained e3b1698e BERT backend artifact, using the same final surrounding libraries on each host. The production candidate uses scalar, serial Q5_1 unpacking with caller-owned row reuse. Each host ran all 82 real SciFact/NFCorpus embeddings and ten counterbalanced timing-process pairs. Every embedding value is exactly equal.

Measured candidate/prior geometric ratios (95% paired bootstrap intervals):

| Host | Latency | Allocated bytes | Peak RSS | Load time |
|---|---|---|---|---|
| x86 | 0.9984 [0.9849, 1.0118] | 0.0827 | 1.0043 [0.9286, 1.0823] | 0.9939 [0.9753, 1.0128] |
| ARM | 0.9824 [0.9769, 0.9878] | 0.3090 | 1.0047 [0.9950, 1.0141] | 1.0038 [0.9960, 1.0130] |

The previous large peak-memory regressions from SIMD/parallel BERT are absent from these point estimates, while per-request allocation improves substantially. The confidence intervals do **not** establish a strict 1% upper-bound memory/startup gate. This result must not be described as complete resource qualification. Raw `/usr/bin/time -v` output and every process result are retained.

The candidate backend JAR SHA256 is `8268cf010a9c7ac8c1cb3da2d1f237d004d84fb53f377482e3c9c84c1556317d`; the Vectors JAR is `79f536b453c5ad6d7339b30e41d8c8c440da3ff613caf5d1bea6f83e40ade1c7`. Later Q5_1 convenience/scratch fixes are separate source amendments; this evidence retains its original artifact identity.

Original results: `s3://models-qual-077051030817/results/gdn-proof-q5-final-v4/x86/` and `.../gdn-proof-q5-final-v5/arm/`. The first ARM v4 bootstrap failed before inference because Python lacked `hashlib.file_digest`; v5 uses portable chunked SHA256 with identical runtime and test protocol. The failed receipt was retained and its instance terminated.
