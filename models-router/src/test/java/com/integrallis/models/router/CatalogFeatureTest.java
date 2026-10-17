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

import com.integrallis.models.api.catalog.DiscoveredModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catalog knows more about a model than what it can be asked to do.
 *
 * <p>ModelJars declares `long-context` on seven entries and `noncommercial-research-license` on
 * one, as features rather than capabilities -- a capability is what you can ask of a model, a
 * feature is something true about how it was built or licensed. Discovery dropped all of it, so an
 * application could not route on either, and the only way to express "this needs a long context"
 * was to invent a capability name and declare it by hand.
 */
class CatalogFeatureTest {

  private static DiscoveredModel discovered(Set<String> features) {
    return new DiscoveredModel(
        "ministral_3_3b_instruct_2512_q4_k_m",
        true,
        Set.of("chat"),
        Set.of("chat"),
        262_144,
        0.0,
        0.0,
        2_000_000_000L,
        null,
        Map.of(),
        1.0,
        features);
  }

  @Test
  @DisplayName("features survive the trip from catalog to candidate")
  void discoveryCarriesFeatures() {
    var candidates =
        CatalogDiscovery.discover(
            List.of(provider(discovered(Set.of("long-context", "grouped-query-attention")))));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.getFirst().features())
        .containsExactlyInAnyOrder("long-context", "grouped-query-attention");
  }

  @Test
  @DisplayName("a required feature rules out a candidate that does not declare it")
  void requiredFeatureFilters() {
    // The candidate without the feature is made strictly more attractive on every scored term, so
    // that a router which has stopped filtering on features would pick it. A fixture where both
    // candidates score alike cannot fail when the filter is removed.
    var longContext = candidate("long", Set.of("long-context"));
    var ordinary =
        ModelCandidate.builder("short")
            .local(true)
            .capabilities(Set.of("chat"))
            .contextWindow(262_144)
            .quality(Map.of("chat", 1.0))
            .timeToFirstTokenMillis(1)
            .tokensPerSecond(10_000)
            .features(Set.of())
            .build();
    var router = ModelRouter.builder().candidates(List.of(longContext, ordinary)).build();

    var decision =
        router.route(
            RoutingRequest.builder("summarise this").estimatedTokens(10).build(),
            RoutingRequirements.builder().requireFeature("long-context").build());

    assertThat(decision.selected().id()).isEqualTo("long");
  }

  @Test
  @DisplayName("an excluded feature rules out a candidate that declares it")
  void excludedFeatureFilters() {
    var research = candidate("research", Set.of("noncommercial-research-license"));
    var permissive = candidate("permissive", Set.of());
    var router = ModelRouter.builder().candidates(List.of(research, permissive)).build();

    var decision =
        router.route(
            RoutingRequest.builder("anything").estimatedTokens(10).build(),
            RoutingRequirements.builder().excludeFeature("noncommercial-research-license").build());

    assertThat(decision.selected().id()).isEqualTo("permissive");
  }

  @Test
  @DisplayName("requiring a feature nothing declares fails loudly, naming it")
  void unsatisfiableRequirementNamesTheFeature() {
    var router = ModelRouter.builder().candidates(List.of(candidate("a", Set.of()))).build();

    assertThatThrownBy(
            () ->
                router.route(
                    RoutingRequest.builder("x").estimatedTokens(10).build(),
                    RoutingRequirements.builder().requireFeature("long-context").build()))
        .isInstanceOf(NoEligibleModelException.class)
        .hasMessageContaining("long-context");
  }

  @Test
  @DisplayName("features and capabilities are independent axes")
  void featuresAreNotCapabilities() {
    var model = candidate("m", Set.of("long-context"));

    assertThat(model.capabilities()).containsExactly("chat");
    assertThat(model.features()).containsExactly("long-context");
    // Requiring the feature name as a capability must not accidentally succeed.
    var router = ModelRouter.builder().candidates(List.of(model)).build();
    assertThatThrownBy(
            () ->
                router.route(
                    RoutingRequest.builder("x").estimatedTokens(10).build(),
                    RoutingRequirements.builder().requireCapability("long-context").build()))
        .isInstanceOf(NoEligibleModelException.class);
  }

  @Test
  @DisplayName("a feature cannot be both required and excluded")
  void contradictionIsRejected() {
    assertThatThrownBy(
            () ->
                RoutingRequirements.builder()
                    .requireFeature("long-context")
                    .excludeFeature("long-context")
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("long-context");
  }

  @Test
  @DisplayName("derivation keeps features, like every other component")
  void toBuilderKeepsFeatures() {
    var model = candidate("m", Set.of("long-context", "imatrix"));
    assertThat(model.toBuilder().build().features()).isEqualTo(model.features());
  }

  private static ModelCandidate candidate(String id, Set<String> features) {
    return ModelCandidate.builder(id)
        .local(true)
        .capabilities(Set.of("chat"))
        .contextWindow(262_144)
        .features(features)
        .build();
  }

  private static com.integrallis.models.api.catalog.ModelCatalogProvider provider(
      DiscoveredModel... models) {
    return new com.integrallis.models.api.catalog.ModelCatalogProvider() {
      @Override
      public String name() {
        return "test";
      }

      @Override
      public List<DiscoveredModel> discover() {
        return List.of(models);
      }
    };
  }
}
