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
package com.integrallis.models.backend.nativekernel;

import java.nio.file.Path;

/**
 * Whether the loaded shim advertises the native activation route, which is what makes a model's
 * full logits reproducible from one session to the next.
 *
 * <p>Where it is advertised the activations run the pinned SLEEF port, which is bit-identical from
 * cold to compiled. Where it is not, they fall back to the Java route, and on x86 that route is not
 * bit-stable across HotSpot's compilation of it: {@code sigmoidAndScaledSoftplus} computes softplus
 * through the vector LOG and EXP operations, whose interpreted results differ from the compiled
 * intrinsic. Measured on two separate runners (AMD EPYC 7763 and EPYC 9V45, both
 * `activeVectorBits=256`) as 3 differing lanes of 320, while {@code swiGlu} was identical on the
 * same hosts and across sixteen lengths locally.
 *
 * <p>That is a property of the released Vectors activation bodies, not of this release: Vectors
 * 0.1.30 deliberately keeps the released x86 arithmetic and scopes its own cold/hot activation
 * determinism contract to aarch64, and Models {@code v0.3.58} carries no native activations at all,
 * so x86 ran this same Java route there. Token-level greedy continuations are stable on every
 * platform and are asserted unconditionally; the full-logit bit comparison is asserted where the
 * route that guarantees it is actually present.
 */
final class NativeActivationRoute {
  private NativeActivationRoute() {}

  /** True when the loaded shim advertises the deterministic native activation route. */
  static boolean deterministicActivationsAdvertised() {
    String configured = System.getProperty("models.native.kernels.library");
    if (configured == null || configured.isBlank()) {
      return false;
    }
    try (var library = NativeKernelLibrary.open(Path.of(configured))) {
      return library.supports(NativeKernelCapability.SLEEF_ACTIVATIONS_F32);
    }
  }
}
