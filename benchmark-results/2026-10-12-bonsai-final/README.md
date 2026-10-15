# Complete Bonsai public-QA comparison

All 200 cases completed: 100 SQuAD2 and 100 BoolQ. The two implementations produce
identical raw answers on every case, with no failures or truncations. SQuAD2 exact match
is 77%, token F1 is 80.0297619%; BoolQ accuracy is 90%. The predeclared descriptive
regression rule passes for both datasets. This is a bounded sample, not a claim of
universal quality or statistical noninferiority across all workloads.

The immutable payload uses Models `2163f9eb` and Vectors `d92aceb`, not the later
artifact with overflow, activation boundary and Q5_1 corrections. Subsequent numeric
correspondence checks do not relabel this as a fresh qualification of those artifacts.
The publisher is pinned PrismML-Eng/llama.cpp `e8fd209c975c80aa7fb36a8423571ff3b1b7e955`.
The original frozen case and prompt-token manifests are under
`../2026-10-11-bonsai-production/`.

Two original Spot instances were interrupted. The merger retains their 10 and 16
complete paired cases respectively, excludes their unpaired Models-only answers, and
uses replacement hosts for both implementations on each missing case. It verifies
all 200 unique case indices, prompt tokens, execution controls, immutable source,
host identity and raw report hashes. Raw interrupted data remains present. No missing
or failed cases were dropped from scoring. Cross-host timing is not pooled.

`fleet.json`, `payload.json`, worker scripts, launch identities and termination receipts
record the bounded fleet. The full payload remains at its checksum-bound S3 URI and
in `/tmp/model-qualification-artifacts/bonsai-final-qa-2163f9eb.tar.gz`; it is not copied
into Git. The full original CloudTrail event is retained outside Git at
`/tmp/model-qualification-artifacts/shard-0-cloudtrail.json`, SHA-256
`e719efe248d637e3a18115f4c10c73e35332395deba9a50968ce1a78e9892674`; the minimal launch
identities required by the merger are committed here.

Reproduce scoring with `merge-recovered-public-qa.py` and the original
`../2026-10-11-bonsai-production/score-public-qa.py`; the merged reports and full
`summary.json` preserve per-case provenance and confidence intervals.

The obsolete `launch.py`, which depended on a generated payload in another worktree,
is preserved in the [external campaign archive](../../docs/findings/historical-experiment-decisions.md).
Scoring and merging remain available here; the archived launcher is not a current entrypoint.
