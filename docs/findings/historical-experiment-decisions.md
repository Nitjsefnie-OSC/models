# Historical experiments and decisions

Completed, rejected and superseded campaign payloads are archived outside the library repository. Removing a historical harness does not turn an unresolved finding into a pass, or mean that useful performance evidence is discarded. Current production code, permanent tests, qualification scripts, catalog records and acceptance gates are unchanged.

| Historical campaign | Evidence and decision | Work that remains |
|---|---|---|
| Activated framework fixture | Frozen LangChain4j run hit its 900-second bound; Spring AI failed after 18 seconds; both repeated a weather tool call. This was failed model behavior, not a proven deadlock. The historical launcher/telemetry repair is retired. | Actual model tool behavior still needs qualification; bounding repeated calls does not establish it. |
| DeltaNet and related ownership/activation experiments | Candidate matrix and older cloud launchers are superseded. Arithmetic-changing chunked recurrence was rejected and removed; corrected recurrence, Q4 batching and exclusive ownership fixes have separate retained tests/evidence. Final historical whole-model cohorts still reported RSS/startup/precision failures. | Follow current revision-specific correctness and resource gates. A correct component or a completed campaign is not a complete release qualification. |
| Kernel staleness comparison | Completed two-model historical comparison of September and released native artifacts; both selected models lacked Q5_1 tensors. It measured a narrow worker-pool/source change and exposed the need for actual binary identity. | No Q5_1 qualification or universal per-model performance guarantee can be inferred from those two models. Current payload provenance checks remain in place. |
| Load-warmup first generation | Protocol result was VOID: an unstable control and an unobserved toggle invalidated the causal inference. Load warmup was subsequently removed. | The original JIT-state hypothesis was not disproved by a void experiment; determinism must be tested directly on current artifacts. |
| Q4_K repacked tile | llama.cpp ON/OFF measurements showed a benefit inside that implementation. The local Rust port did not establish a win over the shipping kernel and its unused runtime module was removed. | Any renewed port needs an actual supported-host comparison and whole-model/resource proof. The old result neither proves the idea impossible nor establishes a Models speedup. |
| Q5 route experiments | Historical embedding comparison never executed Rust; renewed actual-provider evidence and failed Java/native alternatives are retained. Grouped Q5_0 failed numerical/performance checks and was retired. BERT returned to its measured scalar serial route; causal Q5_1 requires hardware/shape-specific proof. | Preserve final revision-specific allocation/RSS/startup/precision failures and exact embedding/logit checks. Earlier latency wins do not qualify a resource-regressing route. |
| Short-sequence embedding divergence | Recorded nine historical artifacts below the fixed worst-probe cosine gate; the token-length hypothesis was preregistered but not run. This is unresolved qualification work, not a refuted hypothesis. | Run the declared length sweep on pinned artifacts while retaining the one-character probe and unchanged 0.999 floor. Cross-model successes alone cannot rule out an input-dependent kernel defect. |

The independent prefill report analyzer remains at `benchmark-results/2026-10-08-prefill-metric-audit/audit.py`: its experiment mention was only a comment, and it still recomputes retained certified reports. The Bonsai public-QA scorer, merger, results and immutable launch identities remain; only its obsolete launcher, which depended on another worktree's generated payload, was archived. No runtime, build, current script or workflow calls the removed campaign paths.

## Archive provenance

The external archive is `audit-artifacts/2026-10-13-models-experiment-cleanup/models-experiments.tar.gz` in the parent workspace, beside a per-file SHA-256 manifest. It preserves all seven original directories and the obsolete Bonsai launcher, including licenses, protocols, rejected sources, raw observations and unfinished plans. Every archived byte was verified against its original before removal. Historical JSON command paths remain immutable provenance, not live instructions.

Source revision: `b76c3f675fe275303b67bba9b399e553b632d7ae`. Archived files: 270. Archive SHA-256: `a961d8382f17b33f0cc5ed1bf8014adcf82be0eab7be9cba1d84bc7872bc8c58`.

The same content remains recoverable from that source revision in Git history, so the decisions do not depend on an unversioned local path. To reproduce an old experiment, extract its complete archived environment; do not run its old cloud launcher as a current qualification entrypoint.

## Superseded checkout snapshot

The remaining `superseded-checkouts` snapshot beneath the runtime-cleanup evidence directory contained 102 duplicate historical files, including rejected BF16 payloads and patches. No current build, workflow or source referenced it. It has now been removed from the release tree after every archived file and the remote archive readback were verified.

Source revision: `70ed047916fb1ec04f9c48cc0ebede97d383b7bc`. Archive SHA-256: `917fa30264cbee1008cff07d260577e95a6587abc3fed79c7447a41bdabe8e30`. Durable archive: `s3://models-qual-077051030817/audit/2026-10-13/superseded-checkouts/917fa30264cbee1008cff07d260577e95a6587abc3fed79c7447a41bdabe8e30.tar.gz`. The parent workspace retains the archive, per-file hashes and verification receipt under `audit-artifacts/superseded-checkouts-cleanup-2026-10-13/`. This is evidence relocation, not a change to any experiment's verdict.
