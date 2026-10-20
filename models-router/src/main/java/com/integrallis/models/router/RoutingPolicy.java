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

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * How to trade cost, quality, latency and reliability against each other.
 *
 * <p>Weights are relative and get normalized, so only their ratios matter. Constraints are hard: a
 * candidate that breaks one is removed before scoring rather than merely penalized, because a
 * context window that cannot hold the prompt is not a preference.
 *
 * <p>Most applications should use a preset. Routing is a tuning problem with no single right
 * answer, and hand-weighting five dimensions is a poor first experience, so {@link #BALANCED},
 * {@link #CHEAPEST}, {@link #FASTEST}, {@link #BEST_QUALITY}, {@link #LOCAL_FIRST} and {@link
 * #PRIVACY_STRICT} are pre-tuned. {@link #costQualityTradeoff(int)} collapses the whole space onto
 * one 0-to-10 dial for applications that want a single knob.
 *
 * @param costWeight relative weight on being cheap
 * @param qualityWeight relative weight on task quality
 * @param latencyWeight relative weight on responding quickly
 * @param availabilityWeight relative weight on observed reliability
 * @param localityWeight relative weight on staying in process
 * @param minimumQuality quality floor a candidate must clear
 * @param maximumCostPerMillionTokens blended price ceiling
 * @param maximumTimeToFirstTokenMillis time-to-first-token ceiling
 * @param localOnly whether hosted models are excluded outright
 */
public record RoutingPolicy(
    double costWeight,
    double qualityWeight,
    double latencyWeight,
    double availabilityWeight,
    double localityWeight,
    OptionalDouble minimumQuality,
    OptionalDouble maximumCostPerMillionTokens,
    OptionalDouble maximumTimeToFirstTokenMillis,
    boolean localOnly,
    UnmeasuredQuality unmeasuredQuality) {

  /**
   * How a task with no measured quality is scored.
   *
   * <p>Up to 0.4.3 an unmeasured task scored zero against the quality weight, which made absence
   * indistinguishable from a measurement of uselessness and pushed every honest catalog's models to
   * the bottom of the ranking. 0.5.0 corrected it to a neutral prior. That is a change in model
   * selection, so it is nameable: the corrected scoring is the default, and {@link #ZERO} pins the
   * old arithmetic for a fleet that has to reproduce a previous release's routes while it measures
   * its own models.
   *
   * <p>The default is the correct behaviour, not the old one. A defect whose fix has to be opted
   * into is a defect that ships twice.
   */
  public enum UnmeasuredQuality {
    /**
     * Score an unmeasured task as {@link ModelRouter#UNMEASURED_QUALITY_PRIOR}. The default, and
     * the correct reading of an absent measurement.
     *
     * <p>Neutral: absence neither punishes a catalog that refuses to guess nor rewards one that
     * declares nothing. {@code ModelJarsCatalog} leaves unmeasured quality absent on purpose --
     * "missing measurements remain absent so the router can distinguish unknown from measured zero"
     * -- and scoring that absence as zero broke the distinction the catalog was preserving.
     */
    NEUTRAL_PRIOR,

    /**
     * Score an unmeasured task as zero, which is what releases up to 0.4.3 did.
     *
     * <p>An escape hatch for a fleet that depends on the old arithmetic, not a supported reading of
     * the evidence. It is lossy by construction: a model nobody has measured on this task and a
     * model measured to be useless at it score identically. On a policy weighting quality at 0.40
     * that pushes every route toward whichever candidate happens to carry a measurement for some
     * other task, and it is why one fleet ended up with four invented quality scores that measured
     * 16 to 35 points optimistic. Prefer measuring the gap, or {@link #withMinimumQuality(double)}
     * to refuse routing without evidence.
     */
    ZERO
  }

  /**
   * The arity every release before 0.5.2 had, defaulting to the corrected scoring.
   *
   * <p>A fleet constructing a policy directly keeps compiling. It does not keep the old arithmetic,
   * because the old arithmetic was a defect: it is reachable through {@link
   * #withUnmeasuredQuality(UnmeasuredQuality)} with {@link UnmeasuredQuality#ZERO} for anyone who
   * has to pin it while they measure their fleet.
   *
   * @param costWeight weight on price
   * @param qualityWeight weight on measured task quality
   * @param latencyWeight weight on time to first token
   * @param availabilityWeight sharpness of the success-rate multiplier
   * @param localityWeight weight on staying in process
   * @param minimumQuality quality floor a candidate must clear
   * @param maximumCostPerMillionTokens price ceiling
   * @param maximumTimeToFirstTokenMillis latency ceiling
   * @param localOnly whether hosted candidates are refused outright
   */
  public RoutingPolicy(
      double costWeight,
      double qualityWeight,
      double latencyWeight,
      double availabilityWeight,
      double localityWeight,
      OptionalDouble minimumQuality,
      OptionalDouble maximumCostPerMillionTokens,
      OptionalDouble maximumTimeToFirstTokenMillis,
      boolean localOnly) {
    this(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        minimumQuality,
        maximumCostPerMillionTokens,
        maximumTimeToFirstTokenMillis,
        localOnly,
        UnmeasuredQuality.NEUTRAL_PRIOR);
  }

  /**
   * Chooses how a task with no measurement is scored.
   *
   * @param value ZERO for the behaviour of every release before 0.5.0, NEUTRAL_PRIOR for the
   *     corrected scoring, which reports the prior under the {@code quality} key the breakdown has
   *     always had
   * @return a copy of this policy with that setting
   */
  public RoutingPolicy withUnmeasuredQuality(UnmeasuredQuality value) {
    return new RoutingPolicy(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        minimumQuality,
        maximumCostPerMillionTokens,
        maximumTimeToFirstTokenMillis,
        localOnly,
        Objects.requireNonNull(value, "value"));
  }

  /** Sensible default: quality-leaning without ignoring cost or latency. */
  public static final RoutingPolicy BALANCED = weights(0.30, 0.40, 0.15, 0.10, 0.05);

  /** Minimize spend, which in a mixed fleet means preferring local models. */
  public static final RoutingPolicy CHEAPEST = weights(0.70, 0.15, 0.05, 0.05, 0.05);

  /** Minimize time to first token. */
  public static final RoutingPolicy FASTEST = weights(0.05, 0.15, 0.70, 0.05, 0.05);

  /**
   * Maximize task quality and let cost fall where it may.
   *
   * <p>Cost carries no weight at all. A token weight is worse than none: it leaves the preset
   * picking a cheaper model whenever two are within a hair of each other on quality, which is
   * exactly the surprise a preset with this name must not deliver.
   */
  public static final RoutingPolicy BEST_QUALITY = weights(0.00, 0.95, 0.00, 0.05, 0.00);

  /** Prefer in-process models, but escalate when a hosted model is clearly better. */
  public static final RoutingPolicy LOCAL_FIRST = weights(0.20, 0.30, 0.10, 0.05, 0.35);

  /** Never leave the process, whatever the quality cost. */
  public static final RoutingPolicy PRIVACY_STRICT =
      new RoutingPolicy(
          0.20,
          0.55,
          0.15,
          0.10,
          0.00,
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          OptionalDouble.empty(),
          true);

  /** Validates weights and constraints. */
  public RoutingPolicy {
    requireWeight(costWeight, "costWeight");
    requireWeight(qualityWeight, "qualityWeight");
    requireWeight(latencyWeight, "latencyWeight");
    requireWeight(availabilityWeight, "availabilityWeight");
    requireWeight(localityWeight, "localityWeight");
    if (costWeight + qualityWeight + latencyWeight + availabilityWeight + localityWeight <= 0) {
      throw new IllegalArgumentException("at least one weight must be positive");
    }
    unmeasuredQuality = Objects.requireNonNull(unmeasuredQuality, "unmeasuredQuality");
    requireLimit(minimumQuality, "minimumQuality");
    requireLimit(maximumCostPerMillionTokens, "maximumCostPerMillionTokens");
    requireLimit(maximumTimeToFirstTokenMillis, "maximumTimeToFirstTokenMillis");
    if (minimumQuality.isPresent() && minimumQuality.getAsDouble() > 1) {
      throw new IllegalArgumentException("minimumQuality must be within [0, 1]");
    }
  }

  /**
   * Collapses the trade-off onto one dial, in the spirit of a cost/quality slider.
   *
   * @param zeroToTen zero favours capability, ten favours economy
   * @return an interpolated policy
   */
  public static RoutingPolicy costQualityTradeoff(int zeroToTen) {
    if (zeroToTen < 0 || zeroToTen > 10) {
      throw new IllegalArgumentException("costQualityTradeoff must be within [0, 10]");
    }
    double economy = zeroToTen / 10.0;
    return weights(0.05 + 0.70 * economy, 0.85 - 0.70 * economy, 0.05, 0.05, 0.05 * economy);
  }

  /**
   * Returns a copy that also enforces a quality floor.
   *
   * @param minimum quality a candidate must reach for the classified task
   * @return a constrained policy
   */
  public RoutingPolicy withMinimumQuality(double minimum) {
    return new RoutingPolicy(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        OptionalDouble.of(minimum),
        maximumCostPerMillionTokens,
        maximumTimeToFirstTokenMillis,
        localOnly,
        unmeasuredQuality);
  }

  /**
   * Returns a copy that also enforces a price ceiling.
   *
   * @param maximum blended price per million tokens
   * @return a constrained policy
   */
  public RoutingPolicy withMaximumCostPerMillionTokens(double maximum) {
    return new RoutingPolicy(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        minimumQuality,
        OptionalDouble.of(maximum),
        maximumTimeToFirstTokenMillis,
        localOnly,
        unmeasuredQuality);
  }

  /**
   * Returns a copy that also enforces a latency ceiling.
   *
   * @param maximum time to first token in milliseconds
   * @return a constrained policy
   */
  public RoutingPolicy withMaximumTimeToFirstTokenMillis(double maximum) {
    return new RoutingPolicy(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        minimumQuality,
        maximumCostPerMillionTokens,
        OptionalDouble.of(maximum),
        localOnly,
        unmeasuredQuality);
  }

  /**
   * Returns a copy that keeps every request in process.
   *
   * @return a local-only policy
   */
  public RoutingPolicy withLocalOnly() {
    return new RoutingPolicy(
        costWeight,
        qualityWeight,
        latencyWeight,
        availabilityWeight,
        localityWeight,
        minimumQuality,
        maximumCostPerMillionTokens,
        maximumTimeToFirstTokenMillis,
        true,
        unmeasuredQuality);
  }

  /**
   * Reports whether hosted models are excluded.
   *
   * @return true when routing may not leave the process
   */
  public boolean isLocalOnly() {
    return localOnly;
  }

  private static RoutingPolicy weights(
      double cost, double quality, double latency, double availability, double locality) {
    return new RoutingPolicy(
        cost,
        quality,
        latency,
        availability,
        locality,
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        false);
  }

  private static void requireWeight(double value, String field) {
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
  }

  private static void requireLimit(OptionalDouble value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isPresent() && (!Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0)) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
  }
}
