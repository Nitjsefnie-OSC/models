# BF16 serial callback allocation diagnosis

The nine-line serial-path correction removes a measured 48-byte allocation on
SVE ARM. Numerical contracts pass, but this experiment does **not** qualify the
candidate for release or establish no regression on x86.

## Source and actual artifacts

- Original allocation finding: run 38319356761, BF16-only cb3dab3d candidate,
  actual core `107b807f5dfd3ec420ebbdfd72c3e825240b224861fcb2f7d940683c5843fd96`.
  Its twelve-pair raw observations and warmup logs are preserved under
  `prior-run-38319356761`; these results are not rewritten.
- New fixed ablation: [run 38324610747](https://github.com/integrallis/vectors/actions/runs/38324610747),
  immutable source `91dd64ae1a4b4bffa534514f10aeaba7e9671574`.
- Control: combined 6893a587, actual core
  `e3e7b0ab79d0630bcad65c4cbe9fd356cf589dd159eba8e6c0aa402db24bd33c`.
- Candidate: production-only commit `00d26851182b2d797e6bbedab431c0d878db0c5d`,
  actual core `13f0bd516ede8e4306541c72ef368e94c9bddddd80a99631ff5aa65992fdbba8`.
- Both hosts built byte-identical actual Gradle artifacts. Neither arm uses a
  class overlay. Both use Temurin 25.0.3+9 and four CPUs. Host receipts are retained.

## Cause and correction

`BFloat16Matrix.vectorMultiplyBatch` constructed a captured `IntConsumer` before
`GgufParallelSupport.forEachRow` selected its serial or parallel route. The lambda
captures three references and five integers, consistent with a 48-byte aligned
object when references are compressed. The 48x5120 matrix has 245760 elements,
below the existing 1048576-element parallel threshold: no persistent worker pool
is involved in this case.

The candidate checks the existing `willParallelize` predicate first and calls
the identical `vectorMultiplyBatchRow` helper in a direct row loop for the serial
case. All numerical helpers, reduction trees, and threshold values are unchanged.
The parallel case retains its existing callback. The paired ARM ablation removes
48.009 B/op (descriptive 95% paired interval 48.0005–48.0208 B/op), providing causal
evidence for this site. The old published-to-candidate compiler escape difference
is inferred from the source and retained measurements; no compiler trace was
captured to prove why scalar replacement differed.

The installed pinned Bonsai GGUF header contains 96 BF16 tensors, all of dimensions
5120x48, named `ssm_alpha.weight` and `ssm_beta.weight`. One 48-byte allocation per
such tensor would total 4608 bytes per prefill chunk when each is projected once.
That is a scope calculation, **not** a measured whole-model allocation delta. It
must not be confused with the earlier hundreds-of-megabytes vector-escape defect.

## Fixed paired results

Twelve alternating same-host pairs per architecture; unchanged public benchmark,
13-token batch; five 1-second warmup and five 1-second measurement iterations per
fork. All observations retained. Ratio is candidate/control; intervals use the
predeclared paired procedure. No repeats or changed gate limits.

| Host / shape | Mean allocation control→candidate B/op | Latency ratio [95% CI] | Verdict |
|---|---:|---:|---|
| ARM 48x5120 serial |52.786→4.777|0.998168 [0.997678,0.998700]|Faster; allocation removed|
| ARM 4864x896 parallel |71.087→71.004|0.996765 [0.990197,1.004492]|Unresolved|
| x86 48x5120 serial |4.478→4.562|1.020383 [0.995980,1.043857]|Unresolved; precision gate fails|
| x86 4864x896 parallel |80.899→80.853|1.000423 [0.994213,1.005673]|Unresolved|

The roughly 5 B/op remainder in the serial cases is consistent with measurement
bookkeeping over time, not one allocated object per call. The original raw data
and the approximate bookkeeping calculation are retained in `source-diagnosis.json`.
No precise allocation-site profiler was used; the matched single-change ablation
is the causal evidence.

Both workflow jobs correctly exit failure because their strict inherited timing
gate does not permit crossing-one intervals. The x86 serial interval includes a
4.39% slowdown and cannot be described as no regression. This is a diagnosis
against combined e3e7, **not** the published 0.1.29 release comparison. Final-artifact
whole-model resource, startup, latency, and quality qualification remains separate.

All 45 focused tests passed locally and on each supported host, including 12 fresh
JVM cold/hot shape×vector-width contracts, batched raw-bit equality, confined and
shared memory, and nested worker dispatch. Local Spotless also passed. Existing
allocation tests remain unchanged; no tiny noisy per-call assertion was added to
force a green outcome. Exact paired receipts provide the durable allocation proof.

## Separate unresolved ordinary-matvec timing modes

The earlier ARM 4864x896 ordinary-matvec measurements had five fast forks averaging
249.760 µs and seven slow forks averaging425.812 µs. Each mode is stable within its
five measurement iterations, with zero collections. Slow forks move from roughly
350 µs in warmup 2 to 425 µs in warmup 3. Fast forks remain near 250 µs. Both modes have
roughly 40 B/op fixed callback allocation plus time-related measurement overhead;
there is no evidence that garbage collection causes the split.

This pattern makes differing compilation/profile outcomes a plausible hypothesis,
but the retained logs do not distinguish generated-code differences from worker
scheduling or other host effects. A minimal next causal diagnostic would retain
per-fork HotSpot compilation logs and per-thread work/timing for this single
ordinary-matvec shape, preserving both outcomes. **No such extra run was launched.**
The new batch-only correction does not resolve or waive the earlier precision
failure, and neither timing mode was discarded.

## Retained raw archive

All 232 source files, including the raw paths named above, are retained in
`raw-evidence.tar.gz` under `benchmark-results/2026-10-12-bf16-serial-batch/`.
`archive.json` records the source repository, immutable commit and archive hash.
