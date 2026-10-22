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
package com.integrallis.models.api.catalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reports the models available on this machine, so callers do not have to describe them by hand.
 *
 * <p>Discovered through {@link java.util.ServiceLoader}. The interface lives here rather than
 * beside the router because the dependency runs the other way: ModelJars depends on models, so an
 * SPI it implements has to sit on the models side. Anything else would invert the tiers.
 *
 * <p>Written by hand, the fields of a model description are guesswork. Nobody knows their model's
 * time to first token, and the number is hardware-specific, so a value copied from a README is not
 * merely imprecise — it is a measurement of somebody else's machine. A catalog reports what was
 * actually measured here, and says nothing where nothing was measured.
 *
 * <p>Implementations must not throw for an absent or unreadable catalog. Returning an empty list is
 * the correct answer for a machine with no models installed, and is not an error worth propagating
 * into a caller that may be perfectly happy using hosted models.
 */
public interface ModelCatalogProvider {

  /**
   * Human-readable name of this catalog, used in diagnostics.
   *
   * @return a short name, e.g. {@code modeljars}
   */
  String name();

  /**
   * Everything this catalog knows about.
   *
   * @return the models, or an empty list when none are installed
   */
  List<DiscoveredModel> discover();

  /**
   * Whether a caller has to ask for this catalog by name before it contributes anything.
   *
   * <p>False for catalogs of models the caller installed deliberately. True for on-device platform
   * intelligence — Apple Foundation Models and its equivalents — which is present by virtue of the
   * hardware rather than by anyone's choice. Silently routing to it would make the same code select
   * a different model on a developer's laptop than in production, and the difference would only
   * surface as a behaviour change nobody asked for.
   *
   * @return true when this catalog contributes only on explicit opt-in
   */
  default boolean requiresOptIn() {
    return false;
  }

  /**
   * Where this catalog has already put the weights for one of its models, if it has.
   *
   * <p>{@link #discover()} answers "what is here"; this answers "and where", which is what the
   * difference between a routing descriptor and a loadable model has always been. Without it a
   * caller can learn from the catalog that a model exists, what it was measured at, and that it is
   * local — and still have no way to open it except by naming a file the catalog already knows.
   *
   * <p>The path may be a single file or a directory, because that is what the formats are: a GGUF
   * or CACT artifact is one file and a safetensors checkpoint is a directory. Resolving which is
   * which belongs to whatever opens it, not here.
   *
   * <p><b>Return empty rather than fetching.</b> This method reports what is already on disk; it
   * must not download, unpack or otherwise acquire anything, and it must not block on the network.
   * A catalog entry whose weights are not present yet is {@link Optional#empty()}, and so is every
   * entry of a catalog that has no local files at all — a hosted catalog, or on-device platform
   * intelligence, which has weights but none this process may point at. Acquisition is the caller's
   * decision to make explicitly, and a method that quietly pulled gigabytes because somebody asked
   * where a file was would make that decision for them.
   *
   * <p>The default returns empty, so a catalog written against an earlier version keeps compiling
   * and keeps meaning exactly what it did: it describes models and locates none.
   *
   * @param id the {@link DiscoveredModel#id()} to locate
   * @return the artifact's path when this catalog has it on disk, otherwise empty
   */
  default Optional<Path> locate(String id) {
    return Optional.empty();
  }
}
