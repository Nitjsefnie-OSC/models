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
import com.integrallis.models.api.catalog.ModelCatalogProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE WHOLE PATH, NOT A HAND-BUILT CANDIDATE: CATALOG SPI TO DISCOVERY TO A ROUTING DECISION.
 *
 * <p>The unit tests beside this one build a {@link ModelCandidate} directly, which cannot show the
 * failure that mattered. A real catalog does not hand the router a quality map at all for most
 * tasks -- {@code ModelJarsCatalog} measures what it can and leaves the rest absent on purpose --
 * and the defect lived in what the router then did with that absence. So this test registers a
 * provider through the real {@link java.util.ServiceLoader} file, calls the real {@link
 * CatalogDiscovery}, and routes.
 *
 * <p>This is as far as the tiering allows from inside this module: models-router carries no
 * ModelJars dependency, deliberately, so the catalog with real GGUF-derived qualifications is
 * exercised by an integration test in that repository instead. What is proven here is that the
 * discovery path preserves absence end to end, and that a quality floor refuses what nobody has
 * measured.
 */
@Tag("integration")
final class UnmeasuredQualityDiscoveryIntegrationTest {

  /** Registered in {@code META-INF/services}, so the real ServiceLoader lookup finds it. */
  public static final class ProbeCatalog implements ModelCatalogProvider {

    @Override
    public String name() {
      return "unmeasured-quality-probe";
    }

    @Override
    public List<DiscoveredModel> discover() {
      var performance = new DiscoveredModel.Performance(900L, 20.0);
      return List.of(
          // A model with one measured task and nothing for the others, which is the shape a
          // catalog that refuses to guess actually produces.
          new DiscoveredModel(
              "probe-measured-tool-use",
              true,
              Set.of(),
              Set.of("chat", "tool-use"),
              32768,
              0.0,
              0.0,
              800_000_000L,
              performance,
              Map.of("tool-use", 0.91),
              0.99,
              Set.of()),
          new DiscoveredModel(
              "probe-unmeasured",
              true,
              Set.of(),
              Set.of("chat", "tool-use"),
              32768,
              0.0,
              0.0,
              800_000_000L,
              performance,
              Map.of(),
              0.99,
              Set.of()));
    }
  }

  private static List<ModelCandidate> discovered() {
    var candidates =
        CatalogDiscovery.discover().stream()
            .filter(candidate -> candidate.id().startsWith("probe-"))
            .toList();
    assertThat(candidates)
        .as("the probe catalog must be visible through the real ServiceLoader lookup")
        .hasSize(2);
    return candidates;
  }

  @Test
  void discoveryCarriesAbsenceThroughInsteadOfFillingItIn() {
    for (var candidate : discovered()) {
      // Both declare the chat capability; neither was measured on chat. A catalog that filled that
      // in would be guessing about somebody else's hardware and somebody else's corpus.
      assertThat(candidate.capabilities()).contains("chat");
      assertThat(candidate.measuredQualityFor("chat")).isEmpty();
    }
    var measured =
        discovered().stream().filter(c -> c.id().endsWith("measured-tool-use")).findFirst().get();
    assertThat(measured.measuredQualityFor("tool-use")).hasValue(0.91);
  }

  @Test
  void aTaskNobodyMeasuredScoresThePriorRatherThanZero() {
    var router = ModelRouter.builder().candidates(discovered()).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    // One key, the one that always existed. A second key would have meant reserving a name that
    // was legal in every prior release, and a fleet using it would stop booting on an upgrade.
    assertThat(decision.scoreBreakdown()).containsKey("quality");
    assertThat(decision.scoreBreakdown()).doesNotContainKey("quality-unmeasured");
    // Nothing on this machine measured chat, so the share is the prior -- and positive, where the
    // scoring up to 0.4.3 made it zero and pushed every chat route toward whichever artifact
    // happened to carry a tool-use qualification.
    assertThat(decision.scoreBreakdown().get("quality")).isGreaterThan(0.0);
  }

  @Test
  void aMeasuredTaskScoresUnderTheMeasuredKeyAndTheMeasuredModelWins() {
    var router = ModelRouter.builder().candidates(discovered()).build();
    var decision = router.route(RoutingRequest.builder("call a tool").taskType("tool-use").build());

    assertThat(decision.selected().id()).isEqualTo("probe-measured-tool-use");
    assertThat(decision.scoreBreakdown()).containsKey("quality");
    assertThat(decision.scoreBreakdown()).doesNotContainKey("quality-unmeasured");
  }

  /**
   * The guard an operator actually reaches for, against discovered rather than declared data.
   *
   * <p>A floor on a task with no measurement refuses every candidate and says so. The alternative
   * -- which is what shipped until 0.5.0 -- was to score them all zero, refuse them for appearing
   * to have failed, and leave the operator reading a message about a floor nobody had measured
   * against.
   */
  @Test
  void aFloorOnAnUnmeasuredTaskRefusesTheWholeFleetAndNamesTheReason() {
    var router =
        ModelRouter.builder()
            .candidates(discovered())
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.6))
            .build();

    assertThatThrownBy(
            () -> router.route(RoutingRequest.builder("anything").taskType("chat").build()))
        .isInstanceOf(NoEligibleModelException.class)
        .hasMessageContaining("measured quality")
        .hasMessageContaining("chat");
  }

  @Test
  void aFloorOnAMeasuredTaskAdmitsTheModelThatClearsIt() {
    var router =
        ModelRouter.builder()
            .candidates(discovered())
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.6))
            .build();

    var decision = router.route(RoutingRequest.builder("call a tool").taskType("tool-use").build());
    assertThat(decision.selected().id()).isEqualTo("probe-measured-tool-use");
  }
}
