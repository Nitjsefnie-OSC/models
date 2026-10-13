# Completed prospective model-load startup phase

[Run38323913262](https://github.com/integrallis/models/actions/runs/38323913262) completed all **400
matched fresh-JVM pairs per architecture** and passed its unchanged startup gates. Source is
`6b2325d6ba842c81ef6a49de3b3837f44e4bbeab`; candidate Vectors6893a587/core SHA256e3e7b0ab,
Models4f3d3e9a, released baseline0.1.29, JDK25.0.3. Every JAR entry and native binary matched the
prior macro provenance; public weights/settings were identical.

| Qwen BF16 model-load metric | Candidate / baseline | 95% interval | Fixed gate |
|---|---:|---:|---|
| x86 |0.99676519|[0.99131851,1.00217472]|PASS|
| ARM |1.00393891|[1.00031912,1.00759743]|PASS|

ARM is a measured modest **0.39% increase**, not zero regression: the entire interval is above1,
but its upper endpoint is below the predeclared1.01 allowance. x86's interval includes1; this does
not establish a speedup. Both interval widths are below the unchanged0.02 requirement. No
observations were discarded, replaced, pooled with the earlier cohort, or added after seeing results.

Scope is fresh-JVM model-load startup only. No request-latency, memory, quality or overall-release
qualification follows from these numbers. The original10-pair whole-model report and failed
precision gates remain unchanged. Bonsai was not run in this phase. The newer13f0bd51 BF16 artifact
was not measured here and does not inherit these results.

`github-summary/load-summary.json` is the workflow result; `local-summary.json` independently
recomputes the same result from all downloaded raw observations. `raw-archives.json` records SHA256
and file counts for every raw archive; each contains all200-pair process logs, commands, exit codes,
complete-pair receipts, CPU/JDK/artifact provenance and final status from one host. To reproduce,
extract all four archives into `evidence/` and run the frozen `scripts/bonsai-load/analyze.py`.
Intermediate20-pair checkpoint artifacts remain attached to the original GitHub run. The final
archives include every observation from those checkpoints.
