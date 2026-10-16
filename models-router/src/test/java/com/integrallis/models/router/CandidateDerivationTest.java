/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.integrallis.models.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catalog knows what a model is; only the application knows what it is for.
 *
 * <p>Discovery reports identity, context window and throughput. It cannot report an application's
 * own capability names or its per-task quality weights. Without a derivation, adding either meant
 * restating every other component by hand, so the cheaper thing was to stop discovering -- which
 * trades figures read from the artifact for figures invented at the call site.
 */
class CandidateDerivationTest {

  /** What the ModelJars catalog reports for this model, as discovery returns it. */
  private static ModelCandidate discovered() {
    return ModelCandidate.builder("smollm2_360m_instruct_q8_0")
        .local(true)
        .tags(Set.of("chat"))
        .capabilities(Set.of("chat"))
        .contextWindow(8192)
        .timeToFirstTokenMillis(72)
        .tokensPerSecond(20.7)
        .successRate(1.0)
        .build();
  }

  /**
   * A second discovered model, used wherever a test must observe a component carrying through.
   *
   * <p>Its context window is the catalog's real 131,072 rather than SmolLM2's 8,192, which is also
   * the builder's default: a fixture that holds a field at its default cannot fail when that field
   * stops being copied.
   */
  private static ModelCandidate discoveredGrounder() {
    return ModelCandidate.builder("bartowski_llama_3_2_1b_instruct_gguf_q4_k_m")
        .local(true)
        .tags(Set.of("chat"))
        .capabilities(Set.of("chat"))
        .contextWindow(131_072)
        .timeToFirstTokenMillis(150)
        .tokensPerSecond(9.9)
        .successRate(1.0)
        .build();
  }

  @Test
  @DisplayName("a derived candidate keeps every component it was not asked to change")
  void derivationPreservesEveryComponent() {
    ModelCandidate source =
        ModelCandidate.builder("premium")
            .local(false)
            .tags(Set.of("chat", "reasoning"))
            .capabilities(Set.of("chat", "reasoning", "long-context"))
            .costPerMillionTokens(1.25, 10.00)
            .timeToFirstTokenMillis(850)
            .tokensPerSecond(90)
            .contextWindow(400_000)
            .quality(Map.of("reasoning", 0.95))
            .successRate(0.99)
            .maxOutputTokens(2048)
            .pricingTier(100_001, 0.50, 2.50)
            .build();

    assertThat(source.toBuilder().build()).isEqualTo(source);
  }

  @Test
  @DisplayName("the application adds what the catalog cannot know, and keeps what it reported")
  void addsApplicationCapabilitiesWithoutLosingCatalogFacts() {
    ModelCandidate catalog = discoveredGrounder();
    assertThat(catalog.contextWindow())
        .as("fixture must not sit on the builder default, or this test cannot fail")
        .isNotEqualTo(ModelCandidate.builder("any").build().contextWindow());

    ModelCandidate routed =
        catalog.toBuilder().addCapabilities("grounding").quality(Map.of("chat", 0.45)).build();

    // What only the application knows.
    assertThat(routed.capabilities()).containsExactlyInAnyOrder("chat", "grounding");
    assertThat(routed.qualityFor("chat")).isEqualTo(0.45);
    // What the catalog read from the artifact, carried through untouched.
    assertThat(routed.contextWindow()).isEqualTo(catalog.contextWindow());
    assertThat(routed.timeToFirstTokenMillis()).isEqualTo(catalog.timeToFirstTokenMillis());
    assertThat(routed.tokensPerSecond()).isEqualTo(catalog.tokensPerSecond());
    assertThat(routed.local()).isEqualTo(catalog.local());
    assertThat(routed.id()).isEqualTo(catalog.id());
  }

  @Test
  @DisplayName("setting tags on a derived builder does not silently drop its capabilities")
  void laterTagsDoNotReplaceCarriedCapabilities() {
    ModelCandidate routed = discovered().toBuilder().addCapabilities("liveness").build();

    // On a fresh builder, tags() seeds capabilities. On a derived one that would discard the
    // capability the application just added, which is the failure this guards.
    ModelCandidate retagged = routed.toBuilder().tags(Set.of("chat", "summarization")).build();

    assertThat(retagged.tags()).containsExactlyInAnyOrder("chat", "summarization");
    assertThat(retagged.capabilities()).containsExactlyInAnyOrder("chat", "liveness");
  }

  @Test
  @DisplayName("adding capabilities is additive and idempotent")
  void addCapabilitiesIsAdditive() {
    ModelCandidate once = discovered().toBuilder().addCapabilities("liveness").build();
    ModelCandidate twice =
        discovered().toBuilder().addCapabilities("liveness").addCapabilities("liveness").build();
    ModelCandidate separately =
        discovered().toBuilder().addCapabilities("liveness").addCapabilities("grounding").build();

    assertThat(twice.capabilities()).isEqualTo(once.capabilities());
    assertThat(separately.capabilities())
        .containsExactlyInAnyOrder("chat", "liveness", "grounding");
  }

  @Test
  @DisplayName("a derived candidate is independent of the one it came from")
  void derivationDoesNotMutateTheSource() {
    ModelCandidate catalog = discovered();
    Set<String> before = catalog.capabilities();

    ModelCandidate derived = catalog.toBuilder().addCapabilities("liveness").build();

    assertThat(catalog.capabilities()).isEqualTo(before).containsExactly("chat");
    assertThat(derived.capabilities()).contains("liveness");
  }

  @Test
  @DisplayName("declared pricing tiers survive derivation")
  void pricingTiersSurviveDerivation() {
    ModelCandidate haiku =
        ModelCandidate.builder("claude-haiku-5-5")
            .local(false)
            .capabilities(Set.of("chat"))
            .costPerMillionTokens(0.10, 0.50)
            .pricingTier(100_001, 0.50, 2.50)
            .build();

    ModelCandidate derived = haiku.toBuilder().addCapabilities("long-context").build();

    assertThat(derived.pricingTiers()).isEqualTo(haiku.pricingTiers());
    assertThat(derived.costOf(150_000, 1_000)).isEqualTo(haiku.costOf(150_000, 1_000));
    assertThat(derived.tierFor(150_000)).isPresent();
  }

  @Test
  @DisplayName("a null capability is rejected rather than stored")
  void rejectsNullCapability() {
    assertThatThrownBy(() -> discovered().toBuilder().addCapabilities((String) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> discovered().toBuilder().addCapabilities((String[]) null))
        .isInstanceOf(NullPointerException.class);
  }
}
