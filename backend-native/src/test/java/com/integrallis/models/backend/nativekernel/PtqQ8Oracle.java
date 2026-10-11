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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/** Independent scalar Java oracle for the PTQ1_0 x Q8_0 numerical contract. Test code only. */
public final class PtqQ8Oracle {
  private PtqQ8Oracle() {}

  public static float[] multiply(
      MemorySegment weights, float[] input, int batches, int rows, int columns) {
    byte[] quantized = new byte[batches * columns];
    float[] scales = new float[quantized.length / 32];
    for (int block = 0; block < scales.length; block++) {
      float maximum = 0;
      for (int j = 0; j < 32; j++) maximum = Math.max(maximum, Math.abs(input[block * 32 + j]));
      float inverse = maximum == 0 ? 0 : 127f / maximum;
      scales[block] = Float.float16ToFloat(Float.floatToFloat16(maximum / 127f));
      for (int j = 0; j < 32; j++)
        quantized[block * 32 + j] = (byte) Math.rint(input[block * 32 + j] * inverse);
    }
    float[] output = new float[batches * rows];
    int[] trits = new int[128];
    int[] powers = {1, 3, 9, 27, 81};
    for (int row = 0; row < rows; row++) {
      for (int block = 0; block < columns / 128; block++) {
        long base = ((long) row * (columns / 128) + block) * 28;
        int target = 0;
        for (int n = 0; n < 5; n++)
          for (int m = 0; m < 16; m++) {
            int packed =
                (Byte.toUnsignedInt(weights.get(ValueLayout.JAVA_BYTE, base + m)) * powers[n])
                    & 255;
            trits[target++] = (packed * 3 >>> 8) - 1;
          }
        for (int n = 0; n < 5; n++)
          for (int m = 0; m < 8; m++) {
            int packed =
                (Byte.toUnsignedInt(weights.get(ValueLayout.JAVA_BYTE, base + 16 + m)) * powers[n])
                    & 255;
            trits[target++] = (packed * 3 >>> 8) - 1;
          }
        for (int n = 0; n < 4; n++)
          for (int m = 0; m < 2; m++) {
            int packed =
                (Byte.toUnsignedInt(weights.get(ValueLayout.JAVA_BYTE, base + 24 + m)) * powers[n])
                    & 255;
            trits[target++] = (packed * 3 >>> 8) - 1;
          }
        float scale =
            Float.float16ToFloat(
                weights.get(
                    ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                    base + 26));
        for (int batch = 0; batch < batches; batch++) {
          float sum = 0;
          for (int k = 0; k < 4; k++) {
            int integerSum = 0;
            for (int j = 0; j < 32; j++) {
              integerSum +=
                  trits[k * 32 + j] * quantized[batch * columns + block * 128 + k * 32 + j];
            }
            sum = Math.fma(integerSum, scales[batch * columns / 32 + block * 4 + k], sum);
          }
          output[batch * rows + row] = Math.fma(sum, scale, output[batch * rows + row]);
        }
      }
    }
    return output;
  }
}
