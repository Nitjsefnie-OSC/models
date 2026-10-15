# Final Q5_1 protocol and artifact identities

- v4 x86 / v5 ARM: actual d2d0 backend and afc Vectors; restored BERT versus retained BERT control, then narrowed native Q5_1 routing versus the previous blanket native route. The prior-native control changes only RustGgufBatchedMatrixKernel eligibility.
- v6 ARM: a prospective timing-only split, assigned pairs 5–9 before any causal timing was inspected. Original ARM owns pairs 0–4 and both 148-case cohorts. Do not select later duplicate original-host pairs. Each pair runs both arms on the same host.
- v7 both hosts: the same frozen hybrid candidate with only TensorOps.class changed for final scratch fixes versus the prior hybrid. Here the label `prior` means prior hybrid, **not** blanket native. Runtime class comparison confirmed exactly one changed class and no added/deleted entries. Its source is identical to final integration TensorOps.java.

All timing processes use 32 fixed warm requests and three measured requests, with ten counterbalanced process pairs. Cohorts retain all nine owned full-logit snapshots for all 148 requests. Allocated bytes exclude separately measured harness snapshot copies; RSS comes from `/usr/bin/time -v`. No JFR runs are included in these decisive timings.

The original metadata inherited stale descriptive fields in v4; actual `inputs.sha256`, runtime class inventories, and JAR hashes are authoritative. v5 onward refreshes the complete file hashes. The final v7 candidate is a component-only experimental JAR built by replacing the tested Gradle TensorOps.class in the frozen actual d2d0 JAR; it is not represented as a fresh complete release artifact.

The first ARM v4 worker stopped before inference because its Python lacked hashlib.file_digest. The replacement uses portable chunked SHA256. The unsuccessful receipt and terminated host remain accounted for. Every host has an automatic deadline; v7 launch has an additional explicit 22:15 cutoff and 15-minute duration.
