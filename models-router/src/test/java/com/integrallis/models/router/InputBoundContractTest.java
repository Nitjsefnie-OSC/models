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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fleet-wide input bound cannot describe a mixed fleet, for the same reason the output bound
 * could not before 0.3.60 gave candidates their own.
 *
 * <p>The execution takes {@code inputTokens + outputTokens} as the context every request must fit,
 * so raising the fleet-wide input bound to admit one large prompt makes every small request demand
 * a model large enough for the largest one. Observed: a bound of 200,000 made a one-token liveness
 * check ask for a 200,512-token context window and match nothing local.
 */
class InputBoundContractTest {

  @Test
  @DisplayName("a candidate's own prompt ceiling wins over the execution's")
  void candidateCeilingWins() {
    var longContext = ModelCandidate.builder("haiku").maxInputTokens(200_000).build();

    assertThat(longContext.effectiveMaxInputTokens(4096)).isEqualTo(200_000);
  }

  @Test
  @DisplayName("a candidate declaring none defers to the execution's bound")
  void undeclaredDefersToExecution() {
    var local = ModelCandidate.builder("smollm2").build();

    assertThat(local.effectiveMaxInputTokens(4096)).isEqualTo(4096);
    assertThat(local.maxInputTokens()).isZero();
  }

  @Test
  @DisplayName("input and output ceilings are independent")
  void inputAndOutputAreIndependent() {
    var candidate =
        ModelCandidate.builder("premium").maxInputTokens(400_000).maxOutputTokens(2048).build();

    assertThat(candidate.effectiveMaxInputTokens(4096)).isEqualTo(400_000);
    assertThat(candidate.effectiveMaxOutputTokens(512)).isEqualTo(2048);
    // Declaring one must not silently set the other.
    var inputOnly = ModelCandidate.builder("x").maxInputTokens(400_000).build();
    assertThat(inputOnly.effectiveMaxOutputTokens(512)).isEqualTo(512);
  }

  @Test
  @DisplayName("derivation carries the prompt ceiling like every other component")
  void toBuilderCarriesIt() {
    var candidate = ModelCandidate.builder("haiku").maxInputTokens(200_000).build();

    assertThat(candidate.toBuilder().build()).isEqualTo(candidate);
    assertThat(candidate.toBuilder().build().maxInputTokens()).isEqualTo(200_000);
  }

  private static ModelCandidate remote(int maxInputTokens) {
    var builder =
        ModelCandidate.builder("remote")
            .local(false)
            .capabilities(Set.of("chat"))
            .costPerMillionTokens(1.25, 10.00)
            .contextWindow(400_000)
            .quality(Map.of("chat", 0.95));
    if (maxInputTokens > 0) {
      builder.maxInputTokens(maxInputTokens);
    }
    return builder.build();
  }

  private static RoutingExecutionOptions bounded() {
    return RoutingExecutionOptions.builder()
        .sharedBudget(new RoutingBudget(new BigDecimal("10.00")))
        .tokenBounds(new RoutingTokenBounds(4096, 512))
        .build();
  }

  private static <T> T run(ModelCandidate candidate, RoutingUsage reported) throws Exception {
    return ModelFleet.<String>builder()
                .model(candidate, "client")
                .build()
                .execute(
                    RoutingRequest.builder("summarise this appendix")
                        .estimatedTokens(147_011)
                        .build(),
                    RoutingRequirements.none(),
                    RoutingContinuity.none(),
                    bounded(),
                    client -> "the summary",
                    answer -> reported)
                .value()
            != null
        ? null
        : null;
  }

  @Test
  @DisplayName("a prompt over the fleet-wide bound is refused when the candidate declares none")
  void refusesWhenNoCandidateCeiling() {
    assertThatThrownBy(() -> run(remote(0), new RoutingUsage(141_534, 96)))
        .isInstanceOf(RoutingBudgetExceededException.class)
        .hasMessageContaining("token bounds");
  }

  @Test
  @DisplayName("the same prompt is admitted when the candidate declares a ceiling that holds it")
  void admitsWhenCandidateCeilingHolds() {
    assertThatCode(() -> run(remote(200_000), new RoutingUsage(141_534, 96)))
        .doesNotThrowAnyException();
  }
}
