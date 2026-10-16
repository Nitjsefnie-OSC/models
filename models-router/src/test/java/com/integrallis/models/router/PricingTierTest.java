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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Providers do not all charge one rate, and a flat pair prices a long request at the cheap tier.
 *
 * <p>The worked case is Anthropic's Haiku 5.5: $0.10 and $0.50 per million below a 100,000-token
 * prompt, $0.50 and $2.50 at or above it. Declared flat, a 150,000-token request is understated
 * five-fold, which also means a budget stops spending later than it was told to.
 */
class PricingTierTest {
  private static ModelCandidate haiku() {
    return ModelCandidate.builder("claude-haiku-5-5")
        .local(false)
        .capabilities(Set.of("chat"))
        .costPerMillionTokens(0.10, 0.50)
        // The provider says "over 100k", and the threshold here is inclusive, so the step is
        // declared one token above it.
        .pricingTier(100_001, 0.50, 2.50)
        .quality(Map.of("chat", 0.82))
        .build();
  }

  @Test
  @DisplayName("a small prompt pays the candidate's own rates")
  void belowTheThreshold() {
    assertThat(haiku().tierFor(143)).isEmpty();
    // 143 input at $0.10/M plus 104 output at $0.50/M.
    assertThat(haiku().costOf(143, 104)).isEqualByComparingTo(new BigDecimal("0.0000663"));
  }

  @Test
  @DisplayName("crossing the threshold reprices the whole request, output included")
  void atAndAboveTheThreshold() {
    assertThat(haiku().tierFor(100_000)).as("the provider's last cheap token").isEmpty();
    assertThat(haiku().tierFor(100_001)).isPresent();

    // 150,000 input at $0.50/M plus 1,000 output at $2.50/M: both rates move, not just input.
    assertThat(haiku().costOf(150_000, 1_000)).isEqualByComparingTo(new BigDecimal("0.0775"));
    // The same usage priced flat would be five times less, which is the defect this closes.
    var flat =
        ModelCandidate.builder("flat")
            .local(false)
            .capabilities(Set.of("chat"))
            .costPerMillionTokens(0.10, 0.50)
            .build();
    assertThat(flat.costOf(150_000, 1_000)).isEqualByComparingTo(new BigDecimal("0.0155"));
  }

  @Test
  @DisplayName("the highest applicable tier wins, whatever order they were declared in")
  void highestApplicableTierWins() {
    var tiered =
        ModelCandidate.builder("stepped")
            .local(false)
            .capabilities(Set.of("chat"))
            .costPerMillionTokens(1.00, 1.00)
            .pricingTier(1_000_000, 9.00, 9.00)
            .pricingTier(1_000, 3.00, 3.00)
            .build();

    assertThat(tiered.tierFor(999)).isEmpty();
    assertThat(tiered.tierFor(1_000).orElseThrow().inputPerMillion()).isEqualTo(3.00);
    assertThat(tiered.tierFor(999_999).orElseThrow().inputPerMillion()).isEqualTo(3.00);
    assertThat(tiered.tierFor(1_000_000).orElseThrow().inputPerMillion()).isEqualTo(9.00);
    assertThat(tiered.pricingTiers())
        .as("stored in threshold order regardless of declaration order")
        .extracting(PricingTier::fromInputTokens)
        .containsExactly(1_000L, 1_000_000L);
  }

  @Test
  @DisplayName("routing still scores on list price, so a score cannot jump at a threshold")
  void scoringIgnoresTiers() {
    // Deliberate: cheapness() normalises cost across eligible candidates, and a rate that moved
    // with prompt size would make a candidate's rank discontinuous in the size of the request.
    assertThat(haiku().blendedCostPerMillionTokens())
        .isEqualTo((0.10 + 3.0 * 0.50) / 4.0)
        .isEqualTo(
            ModelCandidate.builder("flat")
                .local(false)
                .capabilities(Set.of("chat"))
                .costPerMillionTokens(0.10, 0.50)
                .build()
                .blendedCostPerMillionTokens());
  }

  @Test
  @DisplayName("the budget charges what the accessor reports, tiers included")
  void budgetAgreesWithTheAccessor() {
    assertThat(RoutingBudget.cost(haiku(), 150_000, 1_000))
        .isEqualByComparingTo(haiku().costOf(150_000, 1_000));
  }

  @Test
  @DisplayName("a candidate without tiers is unchanged")
  void noTiersIsTheOldBehaviour() {
    var flat =
        new ModelCandidate(
            "flat",
            false,
            Set.of(),
            Set.of("chat"),
            1.25,
            10.00,
            800,
            90,
            400_000,
            Map.of("chat", 0.9),
            1.0,
            2048);

    assertThat(flat.pricingTiers()).isEmpty();
    assertThat(flat.tierFor(Long.MAX_VALUE)).isEmpty();
    assertThat(flat.costOf(61, 611)).isEqualByComparingTo(new BigDecimal("0.00618625"));
  }

  @Test
  @DisplayName("a tier must start above zero and cost nothing negative")
  void refusesNonsense() {
    assertThatThrownBy(() -> new PricingTier(0, 1.0, 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("above zero");
    assertThatThrownBy(() -> new PricingTier(-1, 1.0, 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PricingTier(10, -1.0, 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  @DisplayName("two tiers cannot start at the same prompt size")
  void refusesDuplicateThresholds() {
    assertThatThrownBy(
            () ->
                ModelCandidate.builder("ambiguous")
                    .local(false)
                    .capabilities(Set.of("chat"))
                    .pricingTier(1_000, 1.0, 1.0)
                    .pricingTier(1_000, 2.0, 2.0)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");
  }

  @Test
  @DisplayName("declared tiers are not mutable through the list the candidate hands back")
  void tiersAreDefensivelyCopied() {
    var tiers = haiku().pricingTiers();
    assertThatThrownBy(() -> tiers.add(new PricingTier(5, 1.0, 1.0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(List.copyOf(tiers)).hasSize(1);
  }
}
