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

/**
 * A price that applies from some prompt size upwards.
 *
 * <p>Providers do not all charge one rate. Anthropic's Haiku 5.5 charges $0.10 and $0.50 per
 * million below a 100,000-token prompt and $0.50 and $2.50 at or above it, a five-fold step that a
 * single pair of rates cannot express. A candidate declaring only the flat pair prices a long
 * request at its cheap rate, which understates the bill and lets a budget stop spending later than
 * it was asked to.
 *
 * <p>Tiers are selected on input tokens, because that is what providers meter the step on. The
 * candidate's own {@code costPerMillionTokens} is the rate from zero; each tier replaces it from
 * {@code fromInputTokens} upwards.
 *
 * @param fromInputTokens smallest prompt this rate applies to, above zero
 * @param inputPerMillion prompt price per million tokens at this tier
 * @param outputPerMillion completion price per million tokens at this tier
 */
public record PricingTier(long fromInputTokens, double inputPerMillion, double outputPerMillion) {

  /** Validates the tier. */
  public PricingTier {
    if (fromInputTokens <= 0) {
      throw new IllegalArgumentException(
          "fromInputTokens must be above zero; the candidate's own rates apply from zero");
    }
    if (!(inputPerMillion >= 0) || !(outputPerMillion >= 0)) {
      throw new IllegalArgumentException("tier prices must not be negative");
    }
  }
}
