# Rejected scalar final activation controls

These local controls retain vector/FMA convolution accumulation and store the convolved values into the existing output array, then apply scalar StrictMath SiLU in place. They add no buffer or warmup-dependent route. Twenty width/FMA/history checks pass; measured cold and warm output/history bits match exactly.

The primary control retains the existing grouping and store order. Cold allocation is about 4.87 MB (below the published implementation's 5.10 MB), but warmed latency is 1.464 times published and 2.571 times the current correction. A full-width companion allocates about 4.18 MB cold but remains 1.306 times published latency and 2.293 times current latency. Both allocate zero bytes warmed.

Both are rejected on these results. Fixing cold allocation by introducing a warm regression is insufficient. These are local feasibility measurements, not supported-host or whole-model qualification. No production source or frozen cohort changed. The archive preserves all 52 tracked files from the source identified in the manifest.
