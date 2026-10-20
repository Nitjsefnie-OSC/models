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

  @Test
  void anUnmeasuredTaskIsReportedAsAbsentRatherThanZero() {
    assertThat(UNMEASURED.measuredQualityFor("chat")).isEmpty();
    assertThat(MEASURED.measuredQualityFor("chat")).hasValue(0.80);
    assertThat(MEASURED.measuredQualityFor("summarization")).isEmpty();
    // The primitive accessor still answers, and still cannot tell the two apart. That is why it is
    // documented as lossy and no longer used for scoring.
    assertThat(UNMEASURED.qualityFor("chat")).isZero();
  }

  @Test
  void anUnmeasuredTaskScoresTheNeutralPriorUnderItsOwnKey() {
    var router = ModelRouter.builder().candidates(List.of(UNMEASURED)).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    var parts = decision.scoreBreakdown();
    // The key is the evidence trail: a reader can tell the number came from nobody measuring
    // rather than from a measurement, which "quality: 0.5" alone would not say.
    assertThat(parts).doesNotContainKey("quality");
    assertThat(parts).containsKey("quality-unmeasured");
    assertThat(parts).containsKeys("cost", "latency", "locality");
    // "availability" is not a weighted term: the router stores the raw reliability under that key
    // and multiplies the rest of the parts by it, so the merit is the sum of everything else.
    double merit =
        parts.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("availability"))
            .mapToDouble(Map.Entry::getValue)
            .sum();
    assertThat(merit).isCloseTo(decision.score(), org.assertj.core.data.Offset.offset(1e-9));
  }

  /**
   * ABSENCE MUST NOT BE REWARDED EITHER, WHICH THE FIRST ATTEMPT AT THIS GOT WRONG.
   *
   * <p>Dropping the quality term for an unmeasured candidate divides its remaining terms by a
   * smaller total weight, which scales each of them up: the unmeasured model then beat a measured
   * one outright. The neutral prior is the fix, and this pins the behaviour that caught it.
   */
  @Test
  void droppingTheTermWouldHaveRewardedAbsence() {
    var router = ModelRouter.builder().candidates(List.of(MEASURED, UNMEASURED)).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    double unmeasuredQualityPart =
        router
            .route(RoutingRequest.builder("anything").taskType("chat").build())
            .scoreBreakdown()
            .getOrDefault("quality-unmeasured", 0.0);
    // The selected candidate is the measured one, and the prior is strictly below its measurement,
    // so the comparison was decided by evidence rather than by renormalisation.
    assertThat(decision.selected().id()).isEqualTo("measured");
    assertThat(ModelRouter.UNMEASURED_QUALITY_PRIOR)
        .isLessThan(MEASURED.measuredQualityFor("chat").getAsDouble());
    assertThat(unmeasuredQualityPart).isZero();
  }

  /**
   * The case the old behaviour got right by accident and for the wrong reason.
   *
   * <p>An unmeasured candidate did fail a floor, because zero is below every floor. The verdict was
   * right and the reason was unusable: "it scored below the floor" is fixed by routing elsewhere,
   * and "nobody measured it" is fixed by measuring. An operator cannot act on the wrong one.
   */
  @Test
  void aQualityFloorFailsClosedOnAnUnmeasuredCandidateAndSaysWhy() {
    var router =
        ModelRouter.builder()
            .candidates(List.of(UNMEASURED))
            .policy(RoutingPolicy.BALANCED.withMinimumQuality(0.5))
            .build();

    assertThatThrownBy(
            () -> router.route(RoutingRequest.builder("anything").taskType("chat").build()))
        .isInstanceOf(NoEligibleModelException.class)
        .hasMessageContaining("measured quality")
        .hasMessageContaining("chat");
  }

  @Test
  void aMeasuredCandidateBelowTheFloorStillSaysItMissedTheFloor() {
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
        .hasMessageContaining("quality floor of 0.5");
  }

  @Test
  void aMeasuredCandidateIsNotBeatenByAnUnmeasuredOneOnQualityAlone() {
    var router = ModelRouter.builder().candidates(List.of(MEASURED, UNMEASURED)).build();
    var decision = router.route(RoutingRequest.builder("anything").taskType("chat").build());

    // Identical on every other axis, so the one with evidence behind it wins. Before this change
    // they were both scored on quality -- one on its measurement, one on a zero it never earned --
    // which happened to give the same ordering here and the wrong one wherever the unmeasured
    // model was cheaper or closer.
    assertThat(decision.selected().id()).isEqualTo("measured");
  }
}
