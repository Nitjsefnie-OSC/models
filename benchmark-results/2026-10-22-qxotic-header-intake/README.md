# qxotic header intake — raw artifacts

2026-10-22. Stage 0 of `docs/QXOTIC-PARITY.md`, executed. Every architectural number in
`docs/QXOTIC-PARITY-FEASIBILITY.md` comes from these files; nothing in that document is estimated.

| file | what it is |
| --- | --- |
| `qxotic-models.txt` | copied verbatim from `references/qxotic` at `6f08d93ec`, `jinfer/scripts/models.txt` — **their** test manifest, which is where every artifact below came from. Their own fixtures, so no artifact here was chosen by us to flatter a result. |
| `probe.mjs` | the probe. Calls `projects/model-jars-central-gate/tools/gguf-inspector.mjs`, the repo's existing range-fetch inspector; no new GGUF parser was written. |
| `headers.json` | its output: `general.architecture`, `general.name`, tensor count, tensor roots, byte size and every non-tokenizer scalar metadata key, per artifact. |
| `quants.mjs`, `arch-keys-and-dtypes.txt` | per-artifact GGML dtype histogram and the `<arch>.*` metadata keys. The dtype numbers are GGML type ids; `GgufTensorType.java:23-62` maps them. |
| `repo-file-sizes.txt` | every `.gguf` in each repository with its size, from the HuggingFace API. This is what decides whether a quantization small enough to qualify exists at all. |

Reproduce: `node probe.mjs` then `node quants.mjs`. Both are read-only HTTP range requests against
public repositories and download no weights — the largest artifact probed is 33.59 GB and the probe
reads a few hundred KB of it.

Three results are absences, which is why the manifest is committed next to the probe: no artifact in
qxotic's own manifest declares `minicpm` (`openbmb/MiniCPM5-1B-GGUF` reports `llama`), none declares
plain `nemotron_h` (the Nemotron artifact reports `nemotron_h_moe`), and `bailingmoe3` has no
artifact in the manifest at all.
