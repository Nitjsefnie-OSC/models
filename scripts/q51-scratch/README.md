# Q5_1 scratch code-form diagnosis

This fixed prospective diagnostic isolates two existing edits: row-buffer reuse (`e800899b`) and avoiding unused Q8 scratch (`38d0d2aa`). It rebuilds all three TensorOps variants with one compiler and identical `-g:none` flags (preventing source-line-only nested-class differences), verifies that only the outer TensorOps class differs, and keeps the exact original hybrid-route runtime and Vectors JAR unchanged. The old payload's stale BERT class-delta metadata is not trusted: actual ZIP entries are compared again.

Twelve matched triplets repeat every permutation of the three arms twice. Every fresh process runs the original 32 warm and three measured requests. The unchanged 148-case cohort retains nine independently owned full-logit snapshots per case; real provider calls are counted separately. All observations are retained, including failures. A separate JFR/compiler-log process per arm follows timing and is never included in decisive measurements.

The GitHub hosts use four threads on both arms, explicitly different from the earlier 16-core experiment. Results diagnose code form on these supported hosts; they do not retrospectively qualify the old candidate, prove the published comparison, or substitute for a final artifact release gate. The two comparisons must not be conflated: `prior` here is the prior hybrid route, never blanket native. Fixed metric limits and precision criteria are preserved in protocol.json. A successful collection job can still report regressing or unresolved metrics.

The reference payload is fetched through the repository secret `Q51_SCRATCH_REFERENCE_URL` and checked against its frozen SHA-256. All extracted historical experiments and measured output live under RUNNER_TEMP, never in the repository. GitHub workflow dispatch is separate from committing this reusable tool. The derived Q5_1 fixture comes from the pinned publisher Q5_0 file and the original checksum-bound quantizer source; its complete resulting hash must match before any inference.

Local validation is limited to source compilation, actionlint, and analysis mutation tests. Model inference and downloads run remotely per the campaign runbook.

The separate `q51-width-repaired.yml` campaign selects
`width-repaired-protocol.json`. All three arms receive the same reviewed native
128-bit reduction and Q5 decoder repairs before comparing scratch variants.
The old unsupported-width ARM timeout remains a failed historical control; it
is not pooled with this new campaign. The full numerical cohort, twelve
triplets, metric limits, warmup and timing settings remain unchanged. Fresh
JAR preflight verifies the new decoder nested class, every preserved entry and
identical shared class hashes; only TensorOps differs across these three arms.
This comparison isolates scratch changes and does not qualify the final
composed Models/Vectors release runtime.
