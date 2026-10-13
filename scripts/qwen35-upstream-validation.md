# Upstream convolution validation for affected families

`Qwen35ForwardPass` is the only production caller of `VectorUtil.causalDepthwiseConv1dSilu`. Its three call sites cover grouped branch decoding, batched prefill, and single-token Gated DeltaNet execution. The shared decoder accepts `qwen35`, `qwen35moe` and `qwen3next`; model names such as Bonsai or Qwen3.6 do not narrow that architecture-based scope. Java and Java+Rust both execute this Java convolution. Qwen2.5 does not call it.

The diagnostic init script replaces exactly one released core JAR in selected test classpaths. It requires the full candidate SHA-256 and exact source revision, records the replacement and every classpath artifact, leaves existing assertions unchanged, rejects skipped/missing classes, and requires results outside the repository. It never modifies normal CI/publication dependency resolution.

| Task | Existing coverage | Real weights |
|---|---|---|
| `:backend-java:qwen35GgufLayoutTest` | Dense layout; pinned first/prompt greedy tokens; public prefill, decode, rewind and explicit session | Qwen3.5-0.8B Q4_K_M, 532,517,120 bytes, SHA-256 `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517` |
| `:backend-java:qwen35GroupedGdnCompatibilityTest` | Grouped GDN heads (16 key/32 value heads); pinned fresh/prompt greedy tokens | Qwen3.5-4B Q4_K_M, 2,740,937,888 bytes, SHA-256 `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4` |
| `:backend-java:qwen35UpstreamSyntheticTest` | Existing dense, MoE, quantized expert, configuration, fused-beta/alpha and Qwen3-Next route tests | Synthetic fixtures only; not real-MoE/Next qualification |

The two real artifacts are pinned in `backend-java/src/test/resources/model-fixtures.properties`; prerequisite download tasks verify their hashes. The separate unchanged Bonsai native fixture remains necessary for the ternary/BF16 Qwen3.5 derivative. These narrow oracle checks complement, and do not replace, full-logit cold/hot/session consistency and whole-model task-quality/resource qualification.

No existing real-weight MoE/Next regression task is registered. The catalog contains ~21–22 GB Qwen3.5-MoE variants and a ~48.5 GB Qwen3-Coder-Next variant, but a catalog entry or synthetic test is not evidence that those weights passed this correction. Keep that coverage gap explicit until a pinned actual-artifact test runs.

Example, after selecting a reviewed immutable candidate:

```bash
export QWEN35_UPSTREAM_JAR=/absolute/path/to/vectors-core.jar
export QWEN35_UPSTREAM_REVISION=<40-character-source-revision>
export QWEN35_UPSTREAM_SHA256=<64-character-artifact-hash>
export QWEN35_UPSTREAM_RESULTS=/absolute/path/outside-the-repository/results
./gradlew :backend-java:qwen35GgufLayoutTest \
  :backend-java:qwen35GroupedGdnCompatibilityTest \
  :backend-java:qwen35UpstreamSyntheticTest \
  --init-script scripts/qwen35-upstream-validation.init.gradle \
  --no-daemon --max-workers=2
```

Run on supported x86 and ARM hosts, preserving the actual CPU/JDK and exact runtime provenance. A configuration-only `--dry-run` verifies task wiring but is never reported as a model test pass.

The composed repeat pins Models `5344b5a2ff84a27548f0f2c4840c24f22d73c32d` and Vectors `b0aadc8d972f3504426f6e38fcb0a4b4a97a0333`. Before inference, it verifies the complete backend JAR (`87f6b22a7a17bf1ebea98a276a9297920d64434d97a4cc0de4e1745fa0fbe94a`) and core JAR (`263bff1aef74bac56d0597ba6b4d7ad52af846fac08b32e70e5d549ce5c8f099`), separately from the harness checkout. Earlier candidate runs retain their own source scope.
