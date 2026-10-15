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

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A spending limit or a declared token bound refused an attempt.
 *
 * <p>Two cases reach this, and they are not the same. Refused <em>before</em> the call, nothing was
 * spent and the router moves to the next candidate. Refused <em>after</em> it, on usage the
 * provider reported, the money is already gone — so this carries what that money bought. Discarding
 * a completion that has been billed protects no budget; it only loses the thing that was paid for.
 */
public final class RoutingBudgetExceededException extends RuntimeException {
  private static final long serialVersionUID = 2L;

  private final transient RoutingUsage usage;
  private final transient BigDecimal cost;
  private final transient Object billed;

  /**
   * A refusal with nothing spent.
   *
   * @param message what was refused and why
   */
  public RoutingBudgetExceededException(String message) {
    this(message, null, null, null);
  }

  private RoutingBudgetExceededException(
      String message, RoutingUsage usage, BigDecimal cost, Object billed) {
    super(message);
    this.usage = usage;
    this.cost = cost;
    this.billed = billed;
  }

  /**
   * A refusal on usage already billed.
   *
   * @param message what was exceeded
   * @param usage the usage the provider reported
   * @param cost what that usage was charged
   * @return a refusal carrying the charge
   */
  static RoutingBudgetExceededException charged(
      String message, RoutingUsage usage, BigDecimal cost) {
    return new RoutingBudgetExceededException(message, usage, cost, null);
  }

  /**
   * The same refusal, carrying the result that was paid for.
   *
   * @param result what the billed attempt returned
   * @return a refusal a caller can recover the artifact from
   */
  RoutingBudgetExceededException withBilledResult(Object result) {
    var carried = new RoutingBudgetExceededException(getMessage(), usage, cost, result);
    carried.setStackTrace(getStackTrace());
    return carried;
  }

  /**
   * Usage the provider reported, when the refusal came after the call.
   *
   * @return the billed usage, or empty when nothing was spent
   */
  public Optional<RoutingUsage> billedUsage() {
    return Optional.ofNullable(usage);
  }

  /**
   * What the billed usage cost, priced from the candidate's declared rates.
   *
   * @return the charge, or empty when nothing was spent
   */
  public Optional<BigDecimal> billedCost() {
    return Optional.ofNullable(cost);
  }

  /**
   * The result the billed attempt produced.
   *
   * <p>Typed as {@code Object} because the fleet's client and result types are the application's,
   * not this library's. A caller that knows what it asked for can cast.
   *
   * @return what was paid for, or empty when nothing was spent or nothing was produced
   */
  public Optional<Object> billedResult() {
    return Optional.ofNullable(billed);
  }
}
