# Models Rust FFM backend

[![MFCQI](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/integrallis/models/main/backend-native/.github/badges/mfcqi.json)](https://github.com/integrallis/mfcqi-java)

`backend-native` accelerates selected GGUF compute kernels without embedding
llama.cpp, Ollama, or another inference engine. Model parsing, tokenizer behavior,
the transformer graph, KV-cache ownership, sampling, and generation remain in Java.
The native boundary is a versioned C ABI implemented by the Models-owned
`jmodels-kernels` Rust crate.

ABI 7 exposes Q4_0, Q5_0, Q5_1, Q8_0, Q4_K, Q5_K, Q6_K, and PTQ1_0 projections,
grouped dispatch, attention, and corrected float32 Gated DeltaNet recurrence kernels.
The Qwen 3.5 graph selects native recurrence when the loaded capability, JVM fused
arithmetic policy, and supported state dimensions agree. The native implementation
advances tokens sequentially; unsupported configurations retain Java recurrence.
Mixed Q4_K/Q5_K/Q6_K groups share one Q8_K activation quantization.

PTQ1_0 uses Q8_0 activations in both Java and Rust: nearest-even integer rounding,
32-value activation blocks with binary16-rounded scales, and a pinned FMA fold.
This changes Bonsai's earlier float-activation arithmetic. The independent test
oracle, Java fallback, scalar Rust and AVX2 routes must agree bit for bit.
The float-activation method is retained only as an explicit accuracy reference;
loading or losing the native capability does not select it. Bonsai's signed
Hadamard transforms and recurrent state remain in Java. Qualification evidence
for an earlier arithmetic contract does not transfer automatically.

Q5_1 consumes **F32** activations and preserves the exact Java
`PinnedReduction.dot` order. Its dequantization leaves `q * d + m` unfused.
Other quantized projection formats consume Q8_0 or Q8_K activations; their
floating-point scale accumulation and reduction order still need explicit
parity tests. Exact integer partial sums alone do not prove that two complete
projection routes agree. There is no grouped Q5_1 kernel, because it does not
share quantized activations.

The historical short embedding comparison did not execute the native kernel:
BERT used Java in both arms. That evidence is corrected, not treated as a native
qualification. A new experiment injected the provider, counted actual calls,
and checked 82 real SciFact/NFCorpus embeddings on x86 and ARM. Outputs were
bit-identical. Java SIMD block expansion, decoded-row reuse, and the existing
worker pool then outperformed Rust for those BERT workloads, so production BERT
retains the improved Java route. Native single-token causal-model projections
use a different Java fallback and require their own actual-model comparison.
No JVM limitation has been established for Q5_1. See
`benchmark-results/2026-10-12-q5-production/` for current raw-evidence indexes
and the preserved correction in
`benchmark-results/2026-10-08-q5-1-rust-shim/NOTES.md`.

The other x86-64 quantized paths use format-specialized AVX2/FMA integer dots, vectorized Q8_0
activation preparation, batched weight reuse, reusable activation scratch, and
an explicitly owned persistent worker context. Scalar kernels remain available
as cross-platform conformance fallbacks.

## Build and test

```shell
./gradlew :backend-native:check
./gradlew :backend-native:nativePlatformJar
./gradlew :backend-native:integrationTest
./gradlew :backend-native:slowTest \
  --tests com.integrallis.models.backend.nativekernel.RustFfmQ5KLargeModelTest
```

Gradle builds the host library with Cargo under
`backend-native/build/rust-target`. Native Java tests run with
`--enable-native-access=ALL-UNNAMED`. The real-model integration test requires
`~/.jvllm/models/Qwen3-0.6B-Q4_0.gguf`,
`~/.jvllm/models/smollm2-360m-instruct-q8_0.gguf`, and
`~/.jvllm/models/MiniCPM5-1B-Q4_K_M.gguf`. The Q5_K slow test requires
`~/.jvllm/models/sqlcoder-7b-q5_k_m.gguf`.

## Load

When the matching platform artifact is present on the runtime classpath,
`RustFfmBackend` verifies its ABI/platform metadata and SHA-256, extracts it to
`~/.models/native-kernels/abi-6/<platform>/<sha256>/`, and opens it through Java
25 FFM:

```java
Path gguf = Path.of("/opt/models/private-model.gguf");

try (var backend = RustFfmBackend.load(gguf)) {
  // Use the same Models backend API as the pure-Java implementation.
}
```

The content-addressed extraction directory and library are owner-only on POSIX
filesystems. Set `models.native.kernels.cache` to override the cache root.

For a local development build, the library path can still be supplied
explicitly:

```shell
java \
  --enable-native-access=ALL-UNNAMED \
  -Dmodels.native.kernels.library=/path/to/libjmodels_kernels.so \
  -jar application.jar
```

`models.native.kernels.threads` controls the worker-context size and defaults to
the JVM-reported processor count.

There is one native route and no switch to choose it. The shim serves supported quantized
projections at every batch size including single-token decode. The dispatcher
chooses which grouped routes to use; a capability bit alone does not decide routing.
Qwen3.5's recurrence currently stays in Java: `supportsGatedDeltaNet()` and
`supportsGroupedGatedDeltaNet()` both return `false`. The native recurrence kernels
remain available for experiments, but their tolerance-level parity does not establish
greedy token identity or preservation of published qualification records. Routing them
requires new oracle and performance qualification. The graph, convolution, state
ownership, tokenizer, and generation loop remain in Java.

`models.native.quantizedDecode`, `models.native.gatedDeltaNet`,
`models.native.q5_0.grouped` and `models.native.loadWarmup` were removed. Each
vetoed a kernel the shim already had, and each defaulted to `false` unless an
exactly-matching ModelJar profile supplied one -- which no ordinary host did,
since every published profile pins an exact `cpu-model`, `processors` and usually
an exact `vm-version`. Routing decode to the Java path instead measured 6.2x
slower on one host and harness: 2.52 against 15.72 tokens/second, OFFLINE against
USABLE. Meanwhile every certified qualification run forced the setting on, so the
published tiers described a route users did not get. Setting a removed property
now fails with a message saying it is gone, rather than being ignored.

Q5_0 grouped projections remain on the Java path. The previously advertised
native grouped capability had no production caller and has been removed; bit13
is reserved and will not be reassigned. Single Q5_0 native projections remain
supported. Controlled whole-model comparisons rejected combined grouping and
separate dual/triple candidates: neither configuration established an
application-level performance benefit on both hosts, and the isolated x86
candidates changed greedy outputs. The older 37.33/38.94 tokens/s claim remains
historical evidence without a raw artifact; the removal decision uses the new
recorded comparisons in `benchmark-results/2026-10-12-q5-production/`.

## CI artifacts and private packages

`.github/workflows/native-kernels.yml` compiles and opens the packaged library on
Linux x86-64/AArch64, macOS x86-64/AArch64, and Windows x86-64/AArch64. Pull
requests retain the raw library, platform JAR, slim Java JAR, and standalone JAR
as workflow artifacts.

Successful `main` builds publish immutable CI versions to the repository's
GitHub Packages Maven registry:

```text
com.integrallis:backend-native-standalone:<ci-version>
com.integrallis:models-kernels-linux-x86_64:<ci-version>
com.integrallis:models-kernels-linux-aarch64:<ci-version>
com.integrallis:models-kernels-macos-x86_64:<ci-version>
com.integrallis:models-kernels-macos-aarch64:<ci-version>
com.integrallis:models-kernels-windows-x86_64:<ci-version>
com.integrallis:models-kernels-windows-aarch64:<ci-version>
```

The standalone coordinate is the private-test artifact: it contains the Java
backend, its runtime dependencies, and all six native resources with no Maven
dependencies. The platform artifacts allow isolated binary testing. These CI
packages are not release-qualified or a substitute for signed Maven Central
artifacts.

The backend is not release-qualified merely because a kernel exists. A
model/platform profile must preserve its correctness oracle and meet the
project's controlled performance floor before ModelJars selects it.
