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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * One model the router may choose, local or hosted.
 *
 * <p>An application registers whatever it can actually reach: ModelJars artifacts it runs in
 * process and hosted providers for which it holds credentials. {@link ModelFleet} binds these
 * descriptors to application-owned clients without adding provider SDKs to this module.
 *
 * <p>For local artifacts the performance and quality figures can come from published ModelJars
 * qualification evidence, which is measured on a recorded host rather than advertised.
 *
 * @param id stable identifier the application maps back to a client
 * @param local whether the model runs in this process
 * @param tags folksonomy labels such as {@code code} or {@code math}
 * @param capabilities concrete features the candidate can provide, such as {@code tool-calling}
 * @param costPerMillionInputTokens prompt price, zero for local
 * @param costPerMillionOutputTokens completion price, zero for local
 * @param timeToFirstTokenMillis measured or advertised time to first token
 * @param tokensPerSecond sustained decode throughput
 * @param contextWindow maximum tokens the model accepts
 * @param quality per-task quality in [0, 1], keyed by task type
 * @param successRate share of recent calls that succeeded, in [0, 1]
 */
public record ModelCandidate(
    String id,
    boolean local,
    Set<String> tags,
    Set<String> capabilities,
    double costPerMillionInputTokens,
    double costPerMillionOutputTokens,
    long timeToFirstTokenMillis,
    double tokensPerSecond,
    int contextWindow,
    Map<String, Double> quality,
    double successRate,
    int maxOutputTokens,
    List<PricingTier> pricingTiers,
    Set<String> features,
    int maxInputTokens) {

  /** Validates and defensively copies the descriptor. */
  public ModelCandidate {
    id = requireText(id);
    tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    quality = Map.copyOf(Objects.requireNonNull(quality, "quality"));
    features = Set.copyOf(Objects.requireNonNull(features, "features"));
    if (maxOutputTokens < 0) {
      throw new IllegalArgumentException("maxOutputTokens must not be negative");
    }
    if (maxInputTokens < 0) {
      throw new IllegalArgumentException("maxInputTokens must not be negative");
    }
    // List.copyOf of a sorted copy: the same immutability the other components get, and the idiom
    // the static analysis recognises as a defensive copy.
    pricingTiers =
        pricingTiers == null
            ? List.of()
            : List.copyOf(
                pricingTiers.stream()
                    .sorted(Comparator.comparingLong(PricingTier::fromInputTokens))
                    .toList());
    for (int index = 1; index < pricingTiers.size(); index++) {
      if (pricingTiers.get(index).fromInputTokens()
          == pricingTiers.get(index - 1).fromInputTokens()) {
        throw new IllegalArgumentException(
            "two pricing tiers start at " + pricingTiers.get(index).fromInputTokens());
      }
    }
    requireNonNegative(costPerMillionInputTokens, "costPerMillionInputTokens");
    requireNonNegative(costPerMillionOutputTokens, "costPerMillionOutputTokens");
    if (timeToFirstTokenMillis < 0) {
      throw new IllegalArgumentException("timeToFirstTokenMillis must not be negative");
    }
    if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= 0) {
      throw new IllegalArgumentException("tokensPerSecond must be positive");
    }
    if (contextWindow < 1) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    quality.forEach(
        (task, value) -> {
          if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("quality for " + task + " must be within [0, 1]");
          }
        });
    if (!Double.isFinite(successRate) || successRate < 0 || successRate > 1) {
      throw new IllegalArgumentException("successRate must be within [0, 1]");
    }
  }

  /**
   * Source-compatible constructor for descriptors written before capability requirements existed.
   *
   * <p>Older callers only supplied task tags. Treating those tags as the declared capability set
   * preserves their prior routing behavior; catalog providers should supply exact capabilities.
   */
  public ModelCandidate(
      String id,
      boolean local,
      Set<String> tags,
      double costPerMillionInputTokens,
      double costPerMillionOutputTokens,
      long timeToFirstTokenMillis,
      double tokensPerSecond,
      int contextWindow,
      Map<String, Double> quality,
      double successRate) {
    this(
        id,
        local,
        tags,
        tags,
        costPerMillionInputTokens,
        costPerMillionOutputTokens,
        timeToFirstTokenMillis,
        tokensPerSecond,
        contextWindow,
        quality,
        successRate);
  }

  /**
   * The previous arity, for callers that declare no output ceiling of their own.
   *
   * @param id stable model identifier
   * @param local whether the weights run in this process
   * @param tags task tags this model is restricted to, empty for none
   * @param capabilities capabilities this model declares
   * @param costPerMillionInputTokens prompt price, zero for local
   * @param costPerMillionOutputTokens completion price, zero for local
   * @param timeToFirstTokenMillis measured or declared time to first token
   * @param tokensPerSecond measured or declared decode rate
   * @param contextWindow largest prompt this model accepts
   * @param quality per-task quality, between zero and one
   * @param successRate observed success rate, between zero and one
   */
  public ModelCandidate(
      String id,
      boolean local,
      Set<String> tags,
      Set<String> capabilities,
      double costPerMillionInputTokens,
      double costPerMillionOutputTokens,
      long timeToFirstTokenMillis,
      double tokensPerSecond,
      int contextWindow,
      Map<String, Double> quality,
      double successRate) {
    this(
        id,
        local,
        tags,
        capabilities,
        costPerMillionInputTokens,
        costPerMillionOutputTokens,
        timeToFirstTokenMillis,
        tokensPerSecond,
        contextWindow,
        quality,
        successRate,
        0);
  }

  /**
   * The previous arity, for callers that declare a single pair of rates.
   *
   * @param id stable model identifier
   * @param local whether the weights run in this process
   * @param tags task tags this model is restricted to, empty for none
   * @param capabilities capabilities this model declares
   * @param costPerMillionInputTokens prompt price, zero for local
   * @param costPerMillionOutputTokens completion price, zero for local
   * @param timeToFirstTokenMillis measured or declared time to first token
   * @param tokensPerSecond measured or declared decode rate
   * @param contextWindow largest prompt this model accepts
   * @param quality per-task quality, between zero and one
   * @param successRate observed success rate, between zero and one
   * @param maxOutputTokens completion ceiling, zero to defer to the execution's bound
   */
  public ModelCandidate(
      String id,
      boolean local,
      Set<String> tags,
      Set<String> capabilities,
      double costPerMillionInputTokens,
      double costPerMillionOutputTokens,
      long timeToFirstTokenMillis,
      double tokensPerSecond,
      int contextWindow,
      Map<String, Double> quality,
      double successRate,
      int maxOutputTokens) {
    this(
        id,
        local,
        tags,
        capabilities,
        costPerMillionInputTokens,
        costPerMillionOutputTokens,
        timeToFirstTokenMillis,
        tokensPerSecond,
        contextWindow,
        quality,
        successRate,
        maxOutputTokens,
        List.of(),
        Set.of(),
        0);
  }

  /**
   * The rates that apply to a prompt of this size.
   *
   * <p>A tier replaces the candidate's own rates from its threshold upwards, which is how providers
   * meter the step and how other gateways model it: crossing the threshold reprices the whole
   * request rather than only the tokens above it.
   *
   * @param inputTokens prompt size the request will actually send
   * @return the tier in force, or empty when the candidate's own rates apply
   */
  public Optional<PricingTier> tierFor(long inputTokens) {
    PricingTier applicable = null;
    for (PricingTier tier : pricingTiers) {
      if (inputTokens >= tier.fromInputTokens()) {
        applicable = tier;
      }
    }
    return Optional.ofNullable(applicable);
  }

  /**
   * The completion ceiling this model can honour, or the execution's when it declares none.
   *
   * <p>One fleet-wide bound cannot describe a mixed fleet: a 360M model answering a liveness check
   * in twenty tokens and a reasoning model that bills its reasoning as completion do not share a
   * ceiling. A candidate that declares its own is measured against that.
   *
   * @param fallback the execution-wide bound, used when this candidate declares none
   * @return the ceiling to hold this candidate to
   */
  public int effectiveMaxOutputTokens(int fallback) {
    return maxOutputTokens > 0 ? maxOutputTokens : fallback;
  }

  /**
   * The prompt ceiling this model can honour, or the execution's when it declares none.
   *
   * <p>The mirror of {@link #effectiveMaxOutputTokens(int)}, and needed for the same reason. A
   * fleet-wide input bound cannot describe a mixed fleet either: a 360M model with an 8,192-token
   * window and a provider that accepts 200,000 do not share a prompt ceiling. Without this the only
   * way to admit a large prompt is to raise the fleet-wide bound, and because the execution takes
   * {@code inputTokens + outputTokens} as the context every request must fit, raising it makes
   * every small request demand a model large enough for the largest one.
   *
   * @param fallback the execution-wide bound, used when this candidate declares none
   * @return the prompt ceiling to hold this candidate to
   */
  public int effectiveMaxInputTokens(int fallback) {
    return maxInputTokens > 0 ? maxInputTokens : fallback;
  }

  /**
   * Returns the quality recorded for one task type, or zero when none is recorded.
   *
   * <p>Zero for an unmeasured task is a lossy answer, and {@link #measuredQualityFor(String)} is
   * the one to prefer: a model nobody has measured on this task and a model measured to be useless
   * at it are different facts, and this method returns the same number for both. It is kept because
   * callers depend on a primitive {@code double}, and the router itself no longer uses it for
   * scoring.
   *
   * @param taskType task classification, may be null
   * @return recorded quality, or zero when the model declares none for that task
   */
  public double qualityFor(String taskType) {
    return measuredQualityFor(taskType).orElse(0.0);
  }

  /**
   * Returns the measured quality for one task type, empty when there is no measurement.
   *
   * <p>The distinction matters more than it looks. A catalog that measures what it can and leaves
   * the rest absent -- which is what {@code ModelJarsCatalog} does, deliberately -- hands the
   * router a model with no quality score for a task nobody benchmarked it on. Scoring that as zero
   * punishes the honest catalog: on a policy that weights quality at 0.40, an unmeasured candidate
   * cannot win against one whose operator typed a number in, so the operator learns to type numbers
   * in. MEASURED 2026-10-20 on four public datasets, the numbers one demo had typed in were wrong
   * by 16 to 35 points, in the optimistic direction.
   *
   * <p>So absence is reported as absence here, and the router's scoring omits the quality term for
   * a candidate that has none -- the same way it omits cache affinity, residency and load when
   * there is no evidence for those -- rather than scoring the candidate zero on it.
   *
   * @param taskType task classification, may be null for the average across every measured task
   * @return the measurement, or empty when this candidate has none for that task
   */
  public OptionalDouble measuredQualityFor(String taskType) {
    if (taskType == null) {
      return quality.isEmpty()
          ? OptionalDouble.empty()
          : OptionalDouble.of(
              quality.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }
    Double measured = quality.get(taskType);
    return measured == null ? OptionalDouble.empty() : OptionalDouble.of(measured);
  }

  /**
   * Prices one actual usage against this candidate's declared rates.
   *
   * <p>This is the same arithmetic the shared budget charges, exposed because an application that
   * keeps its own ledger otherwise has to reimplement it and then drift from it. Local candidates
   * declare zero prices, so this returns zero for them rather than needing a special case.
   *
   * @param inputTokens prompt tokens actually consumed
   * @param outputTokens completion tokens actually produced
   * @return the cost of that usage
   * @throws IllegalArgumentException if either count is negative
   */
  public BigDecimal costOf(long inputTokens, long outputTokens) {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("usage must not be negative");
    }
    double inputRate = costPerMillionInputTokens;
    double outputRate = costPerMillionOutputTokens;
    // A tier reprices the whole request, input and output alike, rather than only the tokens above
    // its threshold. That is how providers meter the step and how other gateways model it.
    var tier = tierFor(inputTokens);
    if (tier.isPresent()) {
      inputRate = tier.get().inputPerMillion();
      outputRate = tier.get().outputPerMillion();
    }
    return BigDecimal.valueOf(inputRate)
        .multiply(BigDecimal.valueOf(inputTokens))
        .add(BigDecimal.valueOf(outputRate).multiply(BigDecimal.valueOf(outputTokens)))
        .movePointLeft(6);
  }

  /**
   * Blended list price, used for comparing candidates while routing.
   *
   * <p>Weighted three-to-one toward output tokens, because completions dominate spend on the chat
   * and agent workloads this routes.
   *
   * <p>Deliberately ignores {@link #pricingTiers()}. Scoring normalises cost across the eligible
   * candidates, so a rate that moved with prompt size would make a candidate's score jump at its
   * threshold and routing non-monotonic in the size of the request. Accounting is where the actual
   * tier has to be right, and {@link #costOf} applies it. Production gateways draw the line in the
   * same place: dispatch is billed at the tier that served it while routing continues to score on
   * list price.
   *
   * @return blended list price per million tokens
   */
  public double blendedCostPerMillionTokens() {
    return (costPerMillionInputTokens + 3.0 * costPerMillionOutputTokens) / 4.0;
  }

  /**
   * Starts building a candidate.
   *
   * @param id stable model identifier
   * @return a new builder
   */
  public static Builder builder(String id) {
    return new Builder(id);
  }

  /**
   * Starts building a copy of this candidate.
   *
   * <p>Exists so that adjusting one field of a discovered candidate does not mean restating the
   * twelve that were already right. {@link CatalogDiscovery} reports what a catalog knows -- the
   * identity, the context window read from the artifact, measured or estimated throughput -- but it
   * cannot know an application's own capability names or its per-task quality weights. Without a
   * derivation the only way to add either was to rebuild the candidate component by component and
   * keep that copy in step by hand, which costs more than declaring the model from scratch and is
   * why a caller would reasonably decide not to discover at all.
   *
   * <p>Capabilities carry over as an explicit choice, so a later {@link Builder#tags(Set)} call on
   * the returned builder does not silently replace capabilities this candidate already had.
   *
   * @return a builder initialised from every component of this candidate
   */
  public Builder toBuilder() {
    Builder builder = new Builder(id);
    builder.local = local;
    builder.tags = tags;
    builder.capabilities = capabilities;
    builder.capabilitiesExplicit = true;
    builder.inputCost = costPerMillionInputTokens;
    builder.outputCost = costPerMillionOutputTokens;
    builder.ttftMillis = timeToFirstTokenMillis;
    builder.tokensPerSecond = tokensPerSecond;
    builder.contextWindow = contextWindow;
    builder.quality = quality;
    builder.successRate = successRate;
    builder.maxOutputTokens = maxOutputTokens;
    builder.maxInputTokens = maxInputTokens;
    builder.pricingTiers.addAll(pricingTiers);
    builder.features = features;
    return builder;
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    return value;
  }

  private static void requireNonNegative(double value, String field) {
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
  }

  /** Fluent builder, so adding a dimension later does not break every call site. */
  public static final class Builder {
    private int maxOutputTokens;
    private int maxInputTokens;
    private final java.util.List<PricingTier> pricingTiers = new java.util.ArrayList<>();
    private final String id;
    private boolean local;
    private Set<String> tags = Set.of();
    private Set<String> capabilities = Set.of();
    private boolean capabilitiesExplicit;
    private double inputCost;
    private double outputCost;
    private long ttftMillis = 1_000;
    private double tokensPerSecond = 1.0;
    private int contextWindow = 8_192;
    private Map<String, Double> quality = Map.of();
    private double successRate = 1.0;
    private Set<String> features = Set.of();

    private Builder(String id) {
      this.id = id;
    }

    /**
     * Marks the model as running in this process.
     *
     * @param value whether the model is local
     * @return this builder
     */
    public Builder local(boolean value) {
      this.local = value;
      return this;
    }

    /**
     * Sets folksonomy labels.
     *
     * @param value tags such as {@code code}
     * @return this builder
     */
    public Builder tags(Set<String> value) {
      // Copy here as well as in the record: the builder would otherwise hold the caller's
      // collection between this call and build(), where a mutation would still land.
      this.tags = Set.copyOf(value);
      if (!capabilitiesExplicit) {
        this.capabilities = this.tags;
      }
      return this;
    }

    /**
     * Sets catalog-declared features, which are filtered separately from capabilities.
     *
     * @param value feature names such as {@code long-context}
     * @return this builder
     */
    public Builder features(Set<String> value) {
      this.features = Set.copyOf(Objects.requireNonNull(value, "features"));
      return this;
    }

    /** Sets concrete capabilities independently from task-quality tags. */
    public Builder capabilities(Set<String> value) {
      this.capabilities = Set.copyOf(Objects.requireNonNull(value, "capabilities"));
      this.capabilitiesExplicit = true;
      return this;
    }

    /**
     * Adds capabilities to the ones already set, rather than replacing them.
     *
     * <p>The operation a discovered candidate usually needs: a catalog reports what the model is
     * ({@code chat}), while an application routes on names only it defines ({@code liveness},
     * {@code grounding}). Replacing would mean restating the catalog's own answer to keep it.
     *
     * @param values capability names to add
     * @return this builder
     */
    public Builder addCapabilities(String... values) {
      Objects.requireNonNull(values, "values");
      Set<String> combined = new java.util.LinkedHashSet<>(this.capabilities);
      for (String value : values) {
        combined.add(Objects.requireNonNull(value, "capability"));
      }
      return capabilities(combined);
    }

    /**
     * Sets prompt and completion pricing.
     *
     * @param input price per million prompt tokens
     * @param output price per million completion tokens
     * @return this builder
     */
    public Builder costPerMillionTokens(double input, double output) {
      this.inputCost = input;
      this.outputCost = output;
      return this;
    }

    /**
     * Sets time to first token.
     *
     * @param value milliseconds
     * @return this builder
     */
    public Builder timeToFirstTokenMillis(long value) {
      this.ttftMillis = value;
      return this;
    }

    /**
     * Sets sustained decode throughput.
     *
     * @param value tokens per second
     * @return this builder
     */
    public Builder tokensPerSecond(double value) {
      this.tokensPerSecond = value;
      return this;
    }

    /**
     * Sets the usable context window.
     *
     * @param value tokens
     * @return this builder
     */
    public Builder contextWindow(int value) {
      this.contextWindow = value;
      return this;
    }

    /**
     * Sets per-task quality.
     *
     * @param value quality in [0, 1] keyed by task type
     * @return this builder
     */
    public Builder quality(Map<String, Double> value) {
      this.quality = Map.copyOf(value);
      return this;
    }

    /**
     * Sets the observed success rate.
     *
     * @param value share of recent calls that succeeded
     * @return this builder
     */
    public Builder successRate(double value) {
      this.successRate = value;
      return this;
    }

    /**
     * Adds a rate that applies from a prompt size upwards.
     *
     * <p>The threshold is inclusive, so a provider's "above 100,000 tokens" is declared as {@code
     * 100_001}. Token counts are integers, which makes an explicit inclusive/exclusive flag
     * unnecessary; gateways comparing floating-point thresholds need one.
     *
     * @param fromInputTokens smallest prompt this rate applies to, above zero
     * @param inputPerMillion prompt price per million tokens at this tier
     * @param outputPerMillion completion price per million tokens at this tier
     * @return this builder
     */
    public Builder pricingTier(
        long fromInputTokens, double inputPerMillion, double outputPerMillion) {
      this.pricingTiers.add(new PricingTier(fromInputTokens, inputPerMillion, outputPerMillion));
      return this;
    }

    /**
     * Declares the completion ceiling this model can honour.
     *
     * @param value maximum completion tokens, zero to defer to the execution's bound
     * @return this builder
     */
    public Builder maxOutputTokens(int value) {
      if (value < 0) {
        throw new IllegalArgumentException("maxOutputTokens must not be negative");
      }
      this.maxOutputTokens = value;
      return this;
    }

    /**
     * Sets the prompt ceiling this candidate can honour.
     *
     * @param value maximum input tokens, zero to defer to the execution's bound
     * @return this builder
     */
    public Builder maxInputTokens(int value) {
      this.maxInputTokens = value;
      return this;
    }

    /**
     * Builds the candidate.
     *
     * @return an immutable descriptor
     */
    public ModelCandidate build() {
      return new ModelCandidate(
          id,
          local,
          tags,
          capabilities,
          inputCost,
          outputCost,
          ttftMillis,
          tokensPerSecond,
          contextWindow,
          quality,
          successRate,
          maxOutputTokens,
          pricingTiers,
          features,
          maxInputTokens);
    }
  }
}
