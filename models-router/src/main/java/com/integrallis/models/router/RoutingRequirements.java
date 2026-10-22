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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Hard requirements for one routing decision.
 *
 * <p>Capabilities are facts declared by the candidate, such as {@code tool-calling}, {@code
 * text-generation}, or {@code text-embedding}. They are not quality tags. The caller, or a
 * separately audited policy layer, chooses the data boundary; this type deliberately does not
 * pretend to infer whether arbitrary text contains PII.
 */
public record RoutingRequirements(
    Set<String> requiredCapabilities,
    RoutingDataBoundary dataBoundary,
    Set<String> requiredFeatures,
    Set<String> excludedFeatures,
    int minimumContextWindow) {
  private static final RoutingRequirements NONE =
      new RoutingRequirements(Set.of(), RoutingDataBoundary.REMOTE_ALLOWED, Set.of(), Set.of(), 0);

  /**
   * The previous arity, which places no floor on the context window.
   *
   * @param requiredCapabilities capabilities a candidate must declare
   * @param dataBoundary whether the request may leave this machine
   * @param requiredFeatures features a candidate must declare
   * @param excludedFeatures features that disqualify a candidate
   */
  public RoutingRequirements(
      Set<String> requiredCapabilities,
      RoutingDataBoundary dataBoundary,
      Set<String> requiredFeatures,
      Set<String> excludedFeatures) {
    this(requiredCapabilities, dataBoundary, requiredFeatures, excludedFeatures, 0);
  }

  /**
   * The previous arity, which constrains nothing on features.
   *
   * @param requiredCapabilities capabilities a candidate must declare
   * @param dataBoundary whether the request may leave this machine
   */
  public RoutingRequirements(Set<String> requiredCapabilities, RoutingDataBoundary dataBoundary) {
    this(requiredCapabilities, dataBoundary, Set.of(), Set.of(), 0);
  }

  /** Validates and defensively copies the requirements. */
  public RoutingRequirements {
    requiredCapabilities =
        Set.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
    for (String capability : requiredCapabilities) {
      if (capability == null || capability.isBlank()) {
        throw new IllegalArgumentException("required capability must not be blank");
      }
    }
    dataBoundary = Objects.requireNonNull(dataBoundary, "dataBoundary");
    requiredFeatures = Set.copyOf(Objects.requireNonNull(requiredFeatures, "requiredFeatures"));
    excludedFeatures = Set.copyOf(Objects.requireNonNull(excludedFeatures, "excludedFeatures"));
    for (String feature : requiredFeatures) {
      if (feature == null || feature.isBlank()) {
        throw new IllegalArgumentException("required feature must not be blank");
      }
    }
    for (String feature : excludedFeatures) {
      if (feature == null || feature.isBlank()) {
        throw new IllegalArgumentException("excluded feature must not be blank");
      }
      if (requiredFeatures.contains(feature)) {
        throw new IllegalArgumentException("feature both required and excluded: " + feature);
      }
    }
    if (minimumContextWindow < 0) {
      throw new IllegalArgumentException(
          "minimumContextWindow must not be negative: " + minimumContextWindow);
    }
  }

  /** Returns the backwards-compatible unconstrained requirement set. */
  public static RoutingRequirements none() {
    return NONE;
  }

  /** Starts a fluent requirement declaration. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder. */
  public static final class Builder {
    private final Set<String> requiredCapabilities = new LinkedHashSet<>();
    private final Set<String> requiredFeatures = new LinkedHashSet<>();
    private final Set<String> excludedFeatures = new LinkedHashSet<>();
    private RoutingDataBoundary dataBoundary = RoutingDataBoundary.REMOTE_ALLOWED;
    private int minimumContextWindow;

    private Builder() {}

    /** Requires one declared model capability. */
    public Builder requireCapability(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("required capability must not be blank");
      }
      requiredCapabilities.add(value);
      return this;
    }

    /**
     * Requires a catalog-declared feature, such as {@code long-context}.
     *
     * <p>Separate from {@link #requireCapability(String)} because the two answer different
     * questions: a capability is what the model can be asked to do, a feature is something true
     * about how it was built, quantized or licensed.
     *
     * @param value the feature name
     * @return this builder
     */
    public Builder requireFeature(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("required feature must not be blank");
      }
      requiredFeatures.add(value);
      return this;
    }

    /**
     * Rules out any candidate declaring this feature.
     *
     * <p>The reason this exists alongside {@code requireFeature}: the useful predicates are often
     * negative. {@code noncommercial-research-license} and {@code medical-use-warning} are the kind
     * of thing a deployment needs to route away from, and an allow-list cannot express that without
     * enumerating every acceptable feature a catalog might ever add.
     *
     * @param value the feature name
     * @return this builder
     */
    public Builder excludeFeature(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("excluded feature must not be blank");
      }
      excludedFeatures.add(value);
      return this;
    }

    /** Sets the permitted data boundary for this request. */
    public Builder dataBoundary(RoutingDataBoundary value) {
      this.dataBoundary = Objects.requireNonNull(value, "dataBoundary");
      return this;
    }

    /**
     * Requires a context window of at least {@code tokens}, whatever this one prompt measures.
     *
     * <p>The router already refuses a candidate whose window cannot hold the current request. That
     * is not the same question an operator usually has. Traffic that grows inside one model --- a
     * tool-call loop that appends each result, a session that accumulates a transcript --- is
     * served correctly by the first turn and then fails, and the request that fails is not the
     * request that chose the model.
     *
     * <p>Measured on 2026-10-22: a 13 MB tool model with a 2,048-token window won a tool-use prompt
     * against an 8B with 40,960, on a short first turn, and nothing in this type could say that the
     * traffic needed room. That is what this floor is for. It is a hard filter, like the data
     * boundary and unlike a weight: a window that cannot hold the conversation is not a preference.
     *
     * @param tokens the smallest acceptable context window
     * @return this builder
     */
    public Builder requireContextWindow(int tokens) {
      if (tokens < 0) {
        throw new IllegalArgumentException("context window floor must not be negative: " + tokens);
      }
      this.minimumContextWindow = tokens;
      return this;
    }

    /** Builds immutable requirements. */
    public RoutingRequirements build() {
      return new RoutingRequirements(
          requiredCapabilities,
          dataBoundary,
          requiredFeatures,
          excludedFeatures,
          minimumContextWindow);
    }
  }
}
