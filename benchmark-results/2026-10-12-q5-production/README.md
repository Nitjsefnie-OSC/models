# Q5 production cleanup and qualification

This is a historical source-specific report. Its superseded experiment harness and data
are preserved in the [external campaign archive](../../docs/findings/historical-experiment-decisions.md);
later BERT restoration and resource failures are not overwritten by these earlier observations.

The production change is `71d301a0`: exact Java Q5_1 SIMD expansion, one decoded
weight row shared across a batch, parallel row dispatch through the existing
Vectors executor, and reusable BERT scratch. BERT retains its Java path. The
experimental native injection is preserved only in the evidence worktree; it
is not enabled in the production change. Confined memory stays on its owning
thread. Tests cover mixed matrix widths, unused scratch tails, and an independent
scalar oracle over 65,536 decoded values. The final Vectors dependency contains
the same-pool/cross-pool nested-dispatch guards.

The unused Q5_0 grouped capability and Java dispatch branches are removed.
Generic grouped and independent native entry points reject Q5_0. Capability bit
13 is reserved. Active single Q5_0 projections and their arithmetic remain intact.
The Rust worker-generation test now exercises an active Q4_0 group and verifies
rejection of the retired Q5_0 format.

## What the measurements establish

* Historical short-embedding results did not execute Rust. The correction is in
  `../2026-10-08-q5-1-rust-shim/NOTES.md`; those results are not native qualification.
* The renewed actual-provider experiment executed 30 native projections in its
  first BERT embedding. All 82 SciFact/NFCorpus query/document embeddings matched
  Java exactly on both hosts. Input selection and pinned source hashes are in
  the archived `experiments/q5-proof/data/` directory. Oversized documents were shortened to the
  model's 512-token limit; every record retains original and evaluated lengths.
* The new Java BERT implementation was faster than actual Rust injection in
  ten counterbalanced process pairs on both hosts. Arithmetic mean request times
  were Java 458.19 ms / Rust 477.54 ms on x86 and Java 555.97 ms / Rust 651.94 ms on ARM.
  The paired geometric **Java/Rust** latency ratios were 0.833 and0.761.
  Geometric ratios weight each short and long request equally in log space;
  they are not ratios of arithmetic means.
* Q5_0 combined dual/triple grouping did not establish a whole-model win:
  candidate/baseline ratios 1.009 x86 and1.019 ARM across 30 matched requests in
  ten process pairs. The ARM isolated dual and triple variants were slower;
  the x86 isolated variants also changed greedy tokens (17 and 26 of 148 cases).
  No tested grouped configuration qualified for production.

The old Q5_0 harness retained reused logit-array references. Its greedy sequences
are valid observations, but it must not be described as a complete immutable
per-step logit oracle. `Q50RouteProof` and `Q51CausalProof` now clone every snapshot,
including initial logits used for classification. The final Q5 phase uses the
corrected helper. BERT's pooling result is already an owned array and is serialized
immediately; it does not have the snapshot aliasing issue.

## Final-artifact phase

`q5-final-v1` uses production 71d301a0 and Vectors d92aceb0, with immutable payload
SHA256 `7c7573c533624f36e48b4dbb9adef99d3fa1fe72ec9f6bc9f25360866137dc79`.
It compares the previous Java BERT artifact against the final Java implementation
through 82 real embeddings and ten counterbalanced timing pairs. It then builds
the pinned llama.cpp quantizer, derives a Q5_1 causal fixture from the pinned
Qwen2.5-0.5B Q5_0 artifact, and compares Java/Rust through 148 public tasks and ten
timing pairs. This derived fixture is explicitly a format-execution fixture,
not a claim that a publisher supplied a Q5_1 model. The conversion command and
both source/output checksums are recorded. This phase is still running; its
results must pass before claiming the remaining causal native route is qualified.

## Receipts and reproducibility

`focused-test-counts.json` records 66 Java tests with no failures/skips. Rust:
32 tests pass; one intentionally ignored timing benchmark is not a correctness
test. Full raw supported-host logs are retained under
`projects/models-gdn-proof/benchmark-results/2026-10-11-gdn-proof/remote-q5-*`,
`remote-q50-*`, and `remote-q51-*` in the workspace and the corresponding
`s3://models-qual-077051030817/results/gdn-proof-.../` prefixes.
`raw-files.sha256` indexes retained files. `paired-summary.json` records explicit
ratio direction and bootstraps entire process-pair clusters (10,000 draws,
seed 724). The archived `experiments/q5-proof/analyze-results.py` regenerates it
when run inside the extracted historical campaign environment. No failed candidate or incomplete phase is a release pass.
