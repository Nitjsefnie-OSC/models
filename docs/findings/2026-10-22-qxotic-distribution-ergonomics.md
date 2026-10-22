# What jinfer's distribution story tells us about ours

2026-10-22. Source: Craig Walls, *"Spring AI Recipe: Running LLMs in the JVM with Qxotic"*, Medium,
read 2026-10-22. **This is a third party's recipe, not a measurement.** He is the author of *Spring
AI in Action* and had already written the same recipe against ModelJars, so he is the closest thing
we have to a neutral A/B of the two developer experiences — but everything below is *read in an
article* or *read in source*, never *measured here*.

Two of his claims are checked against code and one is left unchecked; that is marked per item.

## How jinfer provisions a model

One property, no Java:

```properties
spring.ai.jinfer.chat.model=LiquidAI/LFM2.5-350M-GGUF:Q8_0
```

Dependencies, versions from a BOM (`com.qxotic:jinfer-bom:0.3.1`):
`jinfer-spring-ai`, `jinfer-spring-ai-autoconfigure`, `jinfer-models-all`, plus `jam-native` and
`jam-vector` for the compute backends. Weights are fetched at first use and cached — he observed
them under `~/.cache/huggingface/hub`, with a `jinfer.hub` progress log and a 362 MB download in
38.5 s. The `ChatModel` bean is auto-configured.

The `ref:quant` string resolves through a pluggable source SPI
(`references/qxotic/jinfer/jinfer-hub/.../ModelSource.java`): `supports(ref)` decides from the ref
alone, `list(ref, dir)` enumerates files, `fetch(ref, file, into)` writes one, staged and renamed,
verifying `sha256` when the listing carries it. Selection, caching and layout policy live in the
store, not the source — so a source is a small, well-bounded interface.

## How we provision a model, and where the article is right about us

**Checked.** Our starter auto-registers a Spring AI `ChatModel`
(`models-spring-boot-starter/.../ModelsAutoConfiguration.java:76-79`, bean name at `:69`), but it
registers it **by adapting a `TextGenerationModel` or
`InferenceBackend` bean the user supplies** — the registrar looks those up and does nothing when
neither is present. So the user writes the Java that opens the model; we adapt it. jinfer's user
writes a property.

That is the real delta, and it is not about either engine. **Our distribution story is the strong
one** (a Maven coordinate, a pinned digest, a measured qualification travelling with the artifact);
**their selection ergonomics are the strong one** (one line, no Java). These are independent, and we
are currently paying their cost without taking their benefit.

**Checked, and since fixed.** Neither project provided a `ChatClient.Builder` bean. He calls its
absence in jinfer "one minor inconvenience" and builds one by hand, and we had the identical hole.
Spring AI's own model starters provide it; an application assembled from `spring-ai-model` alone
does not get one. **Closed on 2026-10-22** by `ModelsChatClientRegistrar`: registered next to the
chat model, prototype-scoped so one injection point's defaults cannot leak into another's, with
every `ChatClientBuilderCustomizer` applied in order, and skipped entirely when the application or
another auto-configuration supplies its own.

**Not checked.** His ModelJars recipe reportedly had to construct a `ChatModel` explicitly before
building a `ChatClient.Builder`. Whether that reflects `models-spring-boot-starter` as it stands,
an older release, or his using `org.modeljars:modeljars` without our starter, was not established —
the recipe source at `github.com/habuma/spring-ai-recipes` was not read. **Do not cite this as a
current defect until someone opens that repository.**

## The design this suggests: a property-driven catalog selector

The pieces already exist; nothing new has to be invented.

- `CatalogDiscovery.discover()` already enumerates every candidate whose weights are on this
  machine, via `ModelCatalogProvider` through `ServiceLoader`, and `ModelJarsCatalog` already
  reports only cached artifacts and carries each one's measured qualification.
- What is missing is the one line that lets a user *name* one in configuration rather than open it
  in Java — something of the shape `integrallis.models.chat.model=<catalog id>`, resolved against
  the installed catalogs, registering the `ChatModel` when it matches exactly one.

That would give us jinfer's one-property ergonomics **on top of** versioned artifacts, which is
strictly more than either side has today: the coordinate is still a build dependency, still pinned,
still carrying its qualification, and the application code still says nothing about a model.

**Built on 2026-10-22**, and it needed one addition to the catalog SPI, because the gap was deeper
than ergonomics: `ModelCatalogProvider` reported *what* was installed and the router consumed it,
but there was no way to learn *where*, so opening a catalogued model always meant naming a file the
catalog already knew. `ModelCatalogProvider.locate(id)` now answers that, as a default method
returning empty so a provider written earlier keeps compiling and keeps meaning what it meant.
`CatalogBackends` in `backend-java` resolves an id across the installed catalogs and opens it with
`PureJavaBackend.loadAutomatic`, and `integrallis.models.catalog-model` names one from
configuration. Ambiguity fails loudly, as planned: two catalogs holding one id is refused with both
names, and an id that is described but not on disk is refused with that distinction rather than
reported as unknown.

**The engine is ours and there is no way to pick another.** Resolution ends at `loadAutomatic`, so a
catalog says where the weights are and the Java decoder runs them, with the native shims picked up
underneath through the existing `PureJavaBackendProvider` seam. No engine-selection parameter was
added, deliberately: an engine chosen at the call site is a measurement seam, and the shipped path
has to be the one the qualification measured.

**ModelJars implements `locate`, and the whole chain is verified end to end.**
`ModelJarsCatalog.locate` returns the same path `ModelJars` itself opens -- `cached()` and `locate()`
are now one method behind two names, so "reported by discover" and "locatable" cannot drift apart.
Checked on this machine: 26 installed models all located at paths that exist, 10 of them matching
`ModelJarCache.artifactPath` exactly, and 70 catalogued-but-uninstalled models correctly reporting
empty. Each of those tests prints its count, because on an empty cache they would otherwise pass
while proving nothing.

The cross-project gate is `modeljars-cli`'s `CatalogModelSpringIntegrationIT`, the only test scope
where the ModelJars catalog and the Models Spring starter share a classpath. It drives
`integrallis.models.catalog-model` through the starter to a generated answer with nothing stubbed,
and it passes: `smollm2_360m_instruct_q8_0`, picked as the smallest installed non-tool-trained chat
model, answered "## READY". Run it with
`./gradlew :modeljars-cli:catalogModelSpringIntegrationTest`.

**One sequencing step remains.** `modeljars` builds against published `models 0.5.2`, which has no
`locate` on the SPI, so the method carries no `@Override` yet -- the annotation would not compile.
This is not a guess about the signature: it was compiled *with* `@Override` against a locally
published `models 0.5.3`, which is how the end-to-end run above was done. A reflection guard in
`ModelJarsCatalogLocateTest` asserts the binding from the moment the interface declares the method
and prints that it could not check before then. When 0.5.3 ships: bump `modelsVersion`, restore the
`@Override`, and the integration task goes green in CI rather than only locally.

### What the end-to-end run found that no unit test did

Two defects, both in code written earlier the same day, and both only reachable with the real
catalog on the classpath:

1. **`CatalogBackends.resolve` had a refusal branch that was unreachable where it helped and wrong
   where it fired.** It tried to distinguish "this catalog knows the id, the weights just are not
   here" by consulting `discover()`. `ModelJarsCatalog.discover()` reports *only cached artifacts*,
   so for the one shipping catalog the branch could never trigger -- and the catalog where it could,
   on-device platform intelligence, describes a model with no path any process may open, so a user
   would have been told to "fetch the artifact first", which is not a thing that can be done. The
   branch is gone; there is one refusal, and it lists the ids that *are* openable. A distinction
   this layer cannot draw truthfully is better not drawn.
2. **That refusal listed the wrong machine.** The no-match message called `openable()` -- the
   ServiceLoader-wide overload -- from inside the provider-scoped `resolve`, so the message
   described the installed catalogs rather than the providers the lookup actually consulted. The
   unit tests passed anyway, because in their classpath the two happened to coincide. Now
   provider-scoped, with a test that pins it.

A third thing, not a defect but a cost: the gate needs its own Gradle task with
`--add-modules jdk.incubator.vector`, because `jdk.incubator.vector` is not optional for this
runtime and the default `test` task does not add it. That follows the convention already in this
repo for real-weight tests, which are named `*IntegrationIT` and excluded from `test`.

## What nobody should take from this

No performance comparison. The article runs a 350M model and reports a plausible answer about
Rayleigh scattering; it reports no tokens/s, no latency, and no comparison against the ModelJars
recipe it replaced. Nothing here says jinfer is faster or slower than our runtime, and the one
number in it (362 MB in 38.5 s) measures his internet connection.
