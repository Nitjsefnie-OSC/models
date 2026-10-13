# Active attention: overlapping mutable slices

> Historical measurements and decisions from the recorded revisions below, not a current release verdict. Raw experiments, failed controls, scripts and captures are preserved in the [verified external archive](ARCHIVE.md). Current status is in [runtime cleanup](../../docs/findings/runtime-cleanup-2026-10-12.md).

The active Rust `execute_attention_partition` constructs mutable slices covering the entire
output and score buffers in every worker, then indexes the heads assigned to that worker.
Disjoint writes do not make overlapping exclusive references valid. Rust's
[`from_raw_parts_mut` contract](https://doc.rust-lang.org/std/slice/fn.from_raw_parts_mut.html)
requires exclusivity for the returned slice's lifetime.

A minimal two-worker reproduction of this exact ownership pattern fails under Miri with
undefined behavior: a data race between retag writes. Constructing only the worker's disjoint
range passes. The actual library's attention test also reproduces the race at the whole-output
slice construction. With the patch, that same test passes Miri's memory checks. This does not
verify every operation in the library, and no observed inference corruption is attributed to it.

## Authorized correction — committed locally

[The archived ownership patch](../../docs/findings/historical-experiment-decisions.md) narrows output and
score slices to each worker's assigned heads and indexes them relative to that range.
Arithmetic and scheduling remain unchanged. Following the user’s instruction to continue, the patch is also applied to the production checkout.
The pending DeltaNet deletion remains separate and unpublished. A pinned Miri CI check exercises
the existing concurrent attention regression test.

Validation on the local Intel host:

- All 31 ordinary Rust tests pass. The separately invoked ignored `prefill_budget` test also passes.
- 540 attention configurations, each repeated three times, produce exactly matching outputs and
  scores against the original library, preserving offset and suffix guards. The matrix covers
  one/four/eight workers, idle workers, one/two cache spans, grouped heads, tails and dimensions.
- The Miri overlapping-view reproduction fails as expected; the disjoint-view correction passes.
- The actual four-worker attention test fails on the original code and passes on the patched code
  under Miri with deterministic float emulation. No memory checks or numerical assertions are disabled.
- The pinned Granite artifact preserves full logits at prefill and through eight generated tokens
  on one JevBench and one SNIPS prompt. Each prompt executes 320 native attention calls per arm.
  This is a bounded parity check, not model task qualification or supported-host coverage.

Raw logs are `attention-exclusive-*.log`, `attention-ownership.*` and `miri-*.stdout/stderr`.
`attention-ownership-provenance.json` records source, patch, binary hashes and compiler versions.
These checks do not establish supported-host model parity or whole-library memory safety.

The Granite artifact hash and source are recorded in `local-attention-model-artifact.json`;
`local-attention-model.*` retains the executed call counts and token IDs. An initial Granite run
used the Qwen activation diagnostic agent, which correctly exited 93 because none of its target
operations executed. That run is retained as `local-attention-unused-agent.*`; the successful
Granite check runs without that irrelevant agent. Qwen's successful recurrence checks are not
attention evidence: its attention route does not call this shim.

`actual-attention-miri-*` retains the original and patched library checks. The first patched run
got past ownership checking but failed its exact single-worker/four-worker comparison under Miri's
deliberately randomized transcendental rounding. Both arms were then run with
`-Zmiri-deterministic-floats`, keeping the test's exact assertion and memory checks intact:
original fails with the data race, patched passes. This flag's scope is documented by
[Miri](https://github.com/rust-lang/miri/#miri--z-flags-and-environment-variables).

## Related inactive code

The inactive DeltaNet native partition creates the same overlapping whole-buffer mutable views.
It needs the same ownership correction before serving as a qualified native control. Earlier
kernel measurements and the completed model diagnostic remain diagnostic observations;
they cannot certify a safe native implementation.

Separate grouped DeltaNet tests found a one-row group ignoring its state-slot mapping, and an
inline float exponential missed by the prefix-only arithmetic alignment. Correcting both in
the experiment passes all 324 ragged/scattered-state comparisons but does not fix ownership.
A subsequent exclusive-range comparator also passes those 324 comparisons and 42 prefix cases,
rejecting duplicate, negative and out-of-range slot tables without mutation. The corrected comparator has now completed the sustained prefix/group benchmark on both
architectures; Java using the existing executor wins every measured shape. This does not settle
whole-model integration or production qualification.

## Production follow-through

The narrow attention patch is authorized and applied locally. Run normal CI and supported-host
validation before the next release. This does not authorize or qualify DeltaNet activation.

## CI regression check

The production Native Kernels workflow now runs the actual concurrent attention test with
`nightly-2026-09-17` Miri and deterministic float emulation. The standalone package job depends
on this check, so private package publication cannot proceed after its failure. Locally, the
pinned check passes for macOS and the emulated Linux target; the original source fails the same
Linux-target check with the overlapping-reference data race. This is interpreter validation,
not an additional physical Linux host measurement. Logs are `attention-ci-*.log`.
`attention-production-source.json` proves the tested Rust source is byte-identical to the committed
production fix. The pending DeltaNet removal is excluded from that tree. The local production commit is
`4337ee4580af3c19920bd6071790b382632220c3`; it has not been released.

## Repository validation scope

The clean source tree passed `spotlessCheck build complianceCheck :backend-native:check`,
including Maven/Gradle consumer dependency graphs, reproducible JARs, documentation and SBOM checks.
The default test suite records 2,551 passed cases and 39 pre-existing hardware/fixture-gated
skips, with no failures; `attention-production-tests.json` lists every skipped case and reason.
Those skips are not qualification evidence. The affected native attention route is separately
exercised with the installed Granite weights and executed-call counters, as described above.

The first full-build attempt failed because the temporary source export lacked Git metadata
required by Antora. The validation directory was then made a detached Git repository and the
same checks resumed. The initial failure log is retained; this was validation setup, not a
production code correction.
