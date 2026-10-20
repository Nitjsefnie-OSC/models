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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AN UNMEASURED MODEL IS NOT A MODEL THAT SCORED ZERO.
 *
 * <p>A catalog that measures what it can and leaves the rest absent is the honest kind, and {@code
 * ModelJarsCatalog} is explicitly that: "missing measurements remain absent so the router can
 * distinguish unknown from measured zero". The router used to erase that distinction in the one
 * place it mattered -- {@code qualityFor} returned 0.0 for an unmeasured task, and on a policy that
 * weights quality at 0.40 an unmeasured candidate could not win against one whose operator had
 * typed a number in.
 *
 * <p>So operators typed numbers in. MEASURED 2026-10-20 against gpt-5.1 on four public datasets,
 * one demo's typed-in quality scores were optimistic by 16 to 35 points: a 1B model declared at
 * 0.70 for grounding scored 37.0 exact match on HotpotQA against the frontier model's 60.0, and
 * never once produced the abstention it was asked for on 50 unanswerable questions.
 *
 * <p>These tests pin the two behaviours that stop the library from asking for that: scoring omits
 * the quality term it cannot weigh, and a quality floor fails closed on a candidate nobody has
 * measured, saying which of the two reasons applies.
 */
final class UnmeasuredQualityTest {

  private static final ModelCandidate MEASURED =
      ModelCandidate.builder("measured")
          .timeToFirstTokenMillis(100)
          .tokensPerSecond(20)
          .quality(Map.of("chat", 0.80))
          .build();

  private static final ModelCandidate UNMEASURED =
      ModelCandidate.builder("unmeasured").timeToFirstTokenMillis(100).tokensPerSecond(20).build();

  private static final RoutingPolicy LEGACY =
      RoutingPolicy.BALANCED.withUnmeasuredQuality(RoutingPolicy.UnmeasuredQuality.ZERO);

  @Test
  void anUnmeasuredTaskIsReportedAsAbsentRatherThanZero() {
    assertThat(UNMEASURED.measuredQualityFor("chat")).isEmpty();
    assertThat(MEASURED.measuredQualityFor("chat")).hasValue(0.80);
    assertThat(MEASURED.measuredQualityFor("summarization")).isEmpty();
    // The primitive accessor cannot tell the two apart, which is why it is documented as lossy and
    // is not what the router scores with.
    assertThat(UNMEASURED.qualityFor("chat")).isZero();
  }

  /**
   * THE CORRECTED SCORING IS THE DEFAULT, BECAUSE THE OLD ONE WAS A DEFECT.
   *
   * <p>{@code ModelJarsCatalog} leaves unmeasured quality absent deliberately -- "missing
   * measurements remain absent so the router can distinguish unknown from measured zero" -- and the
   * router scored that absence as zero, collapsing the distinction the catalog was preserving. Two
   * halves of one project disagreeing about what absence means is a bug, so the fix ships on by
   * default rather than as something a reader has to discover.
   */
  @Test
  void byDefaultAnUnmeasuredTaskScoresTheNeutralPrior() {
    var router = ModelRouter.builder().candidates(List.of(UNMEASURED)).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    double share =
        ModelRouter.UNMEASURED_QUALITY_PRIOR
            * RoutingPolicy.BALANCED.qualityWeight()
            / (RoutingPolicy.BALANCED.costWeight()
                + RoutingPolicy.BALANCED.qualityWeight()
                + RoutingPolicy.BALANCED.latencyWeight()
                + RoutingPolicy.BALANCED.localityWeight());
    assertThat(decision.scoreBreakdown().get("quality"))
        .isCloseTo(share, org.assertj.core.data.Offset.offset(1e-9));
  }

  /**
   * NO NEW BREAKDOWN KEY, SO NO NEW WAY TO BREAK A FLEET AT STARTUP.
   *
   * <p>An earlier attempt put the prior under a second key, {@code quality-unmeasured}, and had to
   * reserve that name -- which had been legal in every prior release, so a fleet with a scorer
   * called that stopped booting on a patch upgrade. The prior goes under the key that already
   * existed instead.
   */
  @Test
  void theScoreBreakdownGainsNoNewKeyAndAScorerCanStillBeNamedAfterOne() {
    var router =
        ModelRouter.builder()
            .candidates(List.of(UNMEASURED))
            .scorer("quality-unmeasured", 0.1, evaluation -> 1.0)
            .build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    assertThat(decision.scoreBreakdown()).containsKey("quality");
    assertThat(decision.scoreBreakdown()).containsKey("quality-unmeasured");
    // Present because the caller registered a scorer by that name, not because the router emits it.
    assertThat(decision.scoreBreakdown().keySet()).contains("cost", "latency", "locality");
  }

  @Test
  void everyKeyTheRouterItselfEmitsIsRefusedAsAScorerName() {
    var emitted =
        new java.util.TreeSet<>(
            ModelRouter.builder()
                .candidates(List.of(MEASURED))
                .build()
                .route(RoutingRequest.builder("anything").taskType("chat").build())
                .scoreBreakdown()
                .keySet());
    assertThat(emitted).contains("quality", "cost", "latency", "locality");
    for (var key : emitted) {
      assertThatThrownBy(
              () ->
                  ModelRouter.builder()
                      .candidates(List.of(MEASURED))
                      .scorer(key, 0.1, evaluation -> 1.0)
                      .build(),
              "a scorer named " + key + " must be refused")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reserved");
    }
  }

  /**
   * ABSENCE MUST NOT BE REWARDED EITHER, WHICH AN EARLIER ATTEMPT GOT WRONG.
   *
   * <p>Dropping the quality term for an unmeasured candidate divides its remaining terms by a
   * smaller total weight, scaling each one up: the unmeasured model then beat a measured one
   * outright. The prior does neither.
   */
  @Test
  void aMeasuredCandidateStillBeatsAnUnmeasuredOneOnQuality() {
    var router = ModelRouter.builder().candidates(List.of(MEASURED, UNMEASURED)).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    assertThat(decision.selected().id()).isEqualTo("measured");
    assertThat(ModelRouter.UNMEASURED_QUALITY_PRIOR)
        .isLessThan(MEASURED.measuredQualityFor("chat").getAsDouble());
  }

  /**
   * A FLOOR REFUSES WHAT NOBODY MEASURED, IN WORDING AN OLDER FLEET ALREADY PARSES.
   *
   * <p>The refusal gains the reason it lacked -- "nobody measured this" is fixed by measuring,
   * "scored below the floor" is fixed by routing elsewhere -- without dropping the sentence a fleet
   * may already be matching on.
   */
  @Test
  void aQualityFloorRefusesAnUnmeasuredCandidateAndSaysWhichKindOfRefusalItIs() {
    var router =
        ModelRouter.builder()
            .candidates(List.of(UNMEASURED))
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.5))
            .build();

    assertThatThrownBy(
            () -> router.route(RoutingRequest.builder("anything").taskType("chat").build()))
        .isInstanceOf(NoEligibleModelException.class)
        // The message this release ships is the message 0.5.1 shipped, to the byte: a floor
        // refusal is a diagnostic a fleet may already be matching on, and a patch release that
        // reworded it for neatness would break that fleet for nothing. It already distinguishes
        // the two refusals, which is all the new behaviour needs from it.
        .hasMessageContaining("floor of 0.5")
        .hasMessageContaining("measured quality for chat");
  }

  @Test
  void aMeasuredCandidateBelowTheFloorIsRefusedWithoutTheUnmeasuredReason() {
    var low =
        ModelCandidate.builder("low")
            .timeToFirstTokenMillis(100)
            .tokensPerSecond(20)
            .quality(Map.of("chat", 0.10))
            .build();
    var router =
        ModelRouter.builder()
            .candidates(List.of(low))
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.5))
            .build();

    assertThatThrownBy(
            () -> router.route(RoutingRequest.builder("anything").taskType("chat").build()))
        .isInstanceOf(NoEligibleModelException.class)
        .hasMessageContaining("quality floor of 0.5")
        .hasMessageNotContaining("no measured quality");
  }

  /** The escape hatch, for a fleet that has to pin the old arithmetic while it measures. */
  @Test
  void theLegacyScoringIsStillReachableAndStillScoresZero() {
    var router = ModelRouter.builder().candidates(List.of(UNMEASURED)).policy(LEGACY).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    assertThat(decision.scoreBreakdown().get("quality")).isZero();
  }

  /** A setting a later with-call silently discards is worse than no setting. */
  @Test
  void theSettingSurvivesEveryOtherPolicyModifier() {
    assertThat(LEGACY.withMinimumQuality(0.4).withLocalOnly().unmeasuredQuality())
        .isEqualTo(RoutingPolicy.UnmeasuredQuality.ZERO);
    assertThat(RoutingPolicy.BALANCED.withMinimumQuality(0.4).unmeasuredQuality())
        .isEqualTo(RoutingPolicy.UnmeasuredQuality.NEUTRAL_PRIOR);
  }
}
