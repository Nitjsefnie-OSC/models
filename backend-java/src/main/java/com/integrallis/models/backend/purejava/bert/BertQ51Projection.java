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

import com.integrallis.models.backend.purejava.ops.PinnedReduction;
import com.integrallis.models.backend.purejava.quant.Q5_1ScalarDequantizer;
import java.lang.foreign.MemorySegment;

/** BERT's measured exact projection route; causal decoders use their own projection planner. */
final class BertQ51Projection {
  private static final Q5_1ScalarDequantizer DECODER = new Q5_1ScalarDequantizer();

  private BertQ51Projection() {}

  static void multiply(
      float[] output,
      float[] input,
      MemorySegment weights,
      int batch,
      int rows,
      int columns,
      float[] decoded) {
    long stride =
        (long) columns / Q5_1ScalarDequantizer.BLOCK_SIZE * Q5_1ScalarDequantizer.BLOCK_BYTES;
    for (int row = 0; row < rows; row++) {
      DECODER.dequantize(weights, row * stride, decoded, 0, columns);
      for (int token = 0; token < batch; token++) {
        output[token * rows + row] =
            PinnedReduction.dot(input, token * columns, decoded, 0, columns);
      }
    }
  }
}
