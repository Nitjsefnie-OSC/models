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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integrallis.models.backend.purejava.gguf.GgufTensorType;
import com.integrallis.models.backend.purejava.quant.TernaryDequantizer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the public matrix provider and real worker dispatch against the Java PTQ reference. */
@Tag("unit")
class PtqNativeKernelTest {
  @Test
  void packedProjectionMatchesJavaBitwiseAcrossBatchesAndWorkerTransitions() {
    Path library = Path.of(System.getProperty("models.native.kernels.library"));
    for (int threads : new int[] {1, 4}) {
      try (var kernel = RustGgufBatchedMatrixKernel.open(library, threads);
          var arena = Arena.ofShared()) {
        assertThat(kernel.supports(GgufTensorType.PTQ1_0)).isTrue();
        var random = new Random(143);
        for (int cols : new int[] {128, 5120, 17408}) {
          // Large -> small -> large must wake every worker after the small dispatch.
          for (int rows : new int[] {129, 1, 137}) {
            byte[] packed = new byte[rows * cols / 128 * 28];
            random.nextBytes(packed);
            int[] scales = {0, 1, 0x3000, 0x3c00, 0xbc00, 0x1400};
            for (int block = 0; block < packed.length / 28; block++) {
              int scale = scales[block % scales.length];
              packed[block * 28 + 26] = (byte) scale;
              packed[block * 28 + 27] = (byte) (scale >>> 8);
            }
            MemorySegment weights = arena.allocate(packed.length + 13).asSlice(13);
            weights.copyFrom(MemorySegment.ofArray(packed));
            for (int batch : new int[] {1, 3, 4, 5, 16}) {
              float[] input = new float[batch * cols];
              for (int i = 0; i < input.length; i++) input[i] = random.nextFloat() * 2 - 1;
              float[] actual = new float[batch * rows];
              kernel.multiply(actual, input, weights, GgufTensorType.PTQ1_0, batch, rows, cols);
              for (int b = 0; b < batch; b++) {
                for (int row = 0; row < rows; row++) {
                  float expected =
                      TernaryDequantizer.dotProductPtq1_0(
                          weights, (long) row * (cols / 128) * 28, input, b * cols, cols);
                  assertThat(Float.floatToRawIntBits(actual[b * rows + row]))
                      .as("threads=%d batch=%d row=%d cols=%d", threads, b, row, cols)
                      .isEqualTo(Float.floatToRawIntBits(expected));
                }
              }
            }
          }
        }
        assertThatThrownBy(
                () ->
                    kernel.multiply(
                        new float[1],
                        new float[127],
                        arena.allocate(28),
                        GgufTensorType.PTQ1_0,
                        1,
                        1,
                        127))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }
}
