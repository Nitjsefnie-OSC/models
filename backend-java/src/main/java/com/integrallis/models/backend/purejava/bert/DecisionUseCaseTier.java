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
package com.integrallis.models.backend.purejava.bert;

/**
 * What a qualified decision model may be used for, which is not a single yes or no.
 *
 * <p>This follows the catalog's existing shape rather than inventing one. {@code RagUseCaseTier}
 * does not exclude a model that cannot answer unguarded — it publishes it at the weaker tier and
 * lets only the stronger tier export a {@code grounding} quality to the router. The limitation is
 * recorded and the model still ships.
 *
 * <p>Calibration is the same kind of property. A decision model whose ranking is reliable is useful
 * even when its published probability was fitted on another distribution; what must not happen is a
 * caller reading that probability as a probability. So the number decides the tier, and the tier
 * decides whether a confidence quality reaches the router.
 *
 * <p><b>No other entry in this catalog faces a calibration bar at all.</b> The RAG gate scores
 * pipeline contribution and the tool gate scores call grammar; neither mentions confidence.
 * Excluding a model for missing a bar its peers were never held to would be a double standard, and
 * admitting it silently would be worse. Tiering is how both are avoided.
 */
public enum DecisionUseCaseTier {

  /**
   * Accurate, and its confidence means what it says.
   *
   * <p>The only tier that exports a confidence quality. A caller may gate on the probability.
   */
  CALIBRATED_DECISION,

  /**
   * Accurate, with a confidence that is not a fitted probability in this regime.
   *
   * <p>The ranking is trustworthy — use {@code argmax}, use the margin between options, route on
   * the chosen answer. Do not threshold the published number as though it were a probability, and
   * do not show it to a user as a percentage. A model lands here when its accuracy gates pass and
   * its expected calibration error does not.
   */
  RANKED_DECISION,

  /** Did not clear the accuracy gates. Nothing about it is publishable. */
  UNQUALIFIED;

  /** Whether a model at this tier may publish a confidence quality for callers to gate on. */
  public boolean exportsConfidenceQuality() {
    return this == CALIBRATED_DECISION;
  }

  /** Whether a model at this tier may be published at all. */
  public boolean publishable() {
    return this != UNQUALIFIED;
  }
}
