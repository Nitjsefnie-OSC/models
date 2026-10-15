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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to a completion the provider has already billed.
 *
 * <p>A bound refused before a call costs nothing and the router moves on. A bound refused after
 * one, on usage the provider reported, cannot un-spend the money — so the refusal has to carry what
 * the money bought. These pin that, and pin that a candidate may declare a ceiling of its own,
 * because a liveness model and a reasoning model cannot share one.
 */
class BilledBoundsContractTest {
  private static ModelCandidate remote(int maxOutputTokens) {
    var builder =
        ModelCandidate.builder("remote")
            .local(false)
            .capabilities(Set.of("chat"))
            .costPerMillionTokens(1.25, 10.00)
            .contextWindow(400_000)
            .quality(Map.of("chat", 0.95));
    if (maxOutputTokens > 0) builder.maxOutputTokens(maxOutputTokens);
    return builder.build();
  }

  private static ModelFleet<String> fleetOf(ModelCandidate candidate, String answer) {
    return ModelFleet.<String>builder().model(candidate, answer).build();
  }

  private static RoutingExecutionOptions bounded(int input, int output) {
    return RoutingExecutionOptions.builder()
        .sharedBudget(new RoutingBudget(new BigDecimal("1.00")))
        .tokenBounds(new RoutingTokenBounds(input, output))
        .build();
  }

  @Test
  @DisplayName("an overrun carries the completion, its usage and its charge")
  void overrunCarriesWhatWasBilled() {
    var candidate = remote(0);
    var fleet = fleetOf(candidate, "client");
    var reported = new RoutingUsage(61, 611);

    assertThatThrownBy(
            () ->
                fleet.execute(
                    RoutingRequest.builder("synthesise the policy conflict").build(),
                    RoutingRequirements.none(),
                    RoutingContinuity.none(),
                    bounded(4096, 512),
                    client -> "the completion that was paid for",
                    answer -> reported))
        .isInstanceOf(RoutingBudgetExceededException.class)
        .satisfies(
            failure -> {
              var overrun = (RoutingBudgetExceededException) failure;
              assertThat(overrun.billedResult())
                  .as("discarding a billed completion protects no budget")
                  .contains("the completion that was paid for");
              assertThat(overrun.billedUsage()).contains(reported);
              assertThat(overrun.billedCost()).contains(candidate.costOf(61, 611));
            });
  }

  @Test
  @DisplayName("a candidate's own ceiling admits usage the fleet-wide bound would refuse")
  void candidateCeilingOverridesTheFleetBound() {
    // 611 completion tokens against a fleet-wide bound of 512 is a refusal; the same usage against
    // a candidate that declares 2048 is ordinary.
    var declared = remote(2048);
    var result =
        fleetOf(declared, "client")
            .execute(
                RoutingRequest.builder("synthesise the policy conflict").build(),
                RoutingRequirements.none(),
                RoutingContinuity.none(),
                bounded(4096, 512),
                client -> "answer",
                answer -> new RoutingUsage(61, 611));

    assertThat(result.value()).isEqualTo("answer");
    assertThat(result.decision().selected().id()).isEqualTo("remote");
  }

  @Test
  @DisplayName("a candidate's ceiling still binds its own usage")
  void candidateCeilingStillBinds() {
    assertThatThrownBy(
            () ->
                fleetOf(remote(256), "client")
                    .execute(
                        RoutingRequest.builder("synthesise").build(),
                        RoutingRequirements.none(),
                        RoutingContinuity.none(),
                        bounded(4096, 4096),
                        client -> "answer",
                        answer -> new RoutingUsage(10, 611)))
        .isInstanceOf(RoutingBudgetExceededException.class);
  }

  @Test
  @DisplayName("a refusal with nothing spent carries nothing")
  void unspentRefusalCarriesNothing() {
    var refusal = new RoutingBudgetExceededException("request budget exhausted");

    assertThat(refusal.billedResult()).isEmpty();
    assertThat(refusal.billedUsage()).isEmpty();
    assertThat(refusal.billedCost()).isEmpty();
  }

  @Test
  @DisplayName("an undeclared ceiling defers to the execution's bound")
  void undeclaredCeilingDefers() {
    assertThat(remote(0).effectiveMaxOutputTokens(512)).isEqualTo(512);
    assertThat(remote(2048).effectiveMaxOutputTokens(512)).isEqualTo(2048);
  }
}
