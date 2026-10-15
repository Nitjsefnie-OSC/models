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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An application that keeps its own token ledger has to price usage the same way the shared budget
 * does. When that arithmetic was not reachable, the only option was to reimplement it and then
 * drift from it, so these pin the public accessor and that the budget agrees with it.
 */
class CandidateCostTest {
  private static ModelCandidate remote() {
    return ModelCandidate.builder("remote")
        .local(false)
        .capabilities(Set.of("chat"))
        .costPerMillionTokens(1.25, 10.00)
        .build();
  }

  @Test
  @DisplayName("usage is priced from the declared per-million rates")
  void pricesUsageFromDeclaredRates() {
    // 61 input at $1.25/M is $0.00007625; 611 output at $10.00/M is $0.00611.
    assertThat(remote().costOf(61, 611)).isEqualByComparingTo(new BigDecimal("0.00618625"));
    assertThat(remote().costOf(0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("a local candidate declares no prices, so its usage is free")
  void localUsageIsFree() {
    var local = ModelCandidate.builder("local").local(true).capabilities(Set.of("chat")).build();

    assertThat(local.costOf(10_000, 10_000)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("the shared budget charges exactly what the accessor reports")
  void budgetAgreesWithTheAccessor() {
    var candidate = remote();

    assertThat(RoutingBudget.cost(candidate, 61, 611))
        .isEqualByComparingTo(candidate.costOf(61, 611));
  }

  @Test
  @DisplayName("negative usage is refused rather than credited")
  void refusesNegativeUsage() {
    assertThatThrownBy(() -> remote().costOf(-1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
    assertThatThrownBy(() -> remote().costOf(0, -1)).isInstanceOf(IllegalArgumentException.class);
  }
}
