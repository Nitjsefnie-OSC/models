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
package com.integrallis.models.backend.purejava.quant;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Scalar Q5_1 unpacking for BERT projections.
 *
 * <p>The SIMD decoder remains useful for causal projections. Whole-BERT comparisons found that its
 * cold Vector API allocations increased peak resident memory; scalar unpacking keeps the identical
 * unfused scale/minimum arithmetic while avoiding that measured cost.
 */
public final class Q5_1ScalarDequantizer implements Dequantizer {
  /** Weights per block, which a row length must be a multiple of. */
  public static final int BLOCK_SIZE = 32;

  /** Bytes per block: two f16 headers, a four-byte fifth-bit plane, sixteen nibble bytes. */
  public static final int BLOCK_BYTES = 24;

  private static final int HALF_BLOCK = BLOCK_SIZE / 2;
  private static final ValueLayout.OfShort LE_SHORT =
      ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  private static final ValueLayout.OfInt LE_INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  @Override
  public void dequantize(MemorySegment src, long srcOffset, float[] dst, int dstOffset, int count) {
    DequantizationChecks.validate(src, srcOffset, dst, dstOffset, count, BLOCK_SIZE, BLOCK_BYTES);
    int numBlocks = count / BLOCK_SIZE;
    for (int block = 0; block < numBlocks; block++) {
      long blockOffset = srcOffset + (long) block * BLOCK_BYTES;
      float scale = Float16.toFloat(src.get(LE_SHORT, blockOffset));
      float minimum = Float16.toFloat(src.get(LE_SHORT, blockOffset + 2));
      int high = src.get(LE_INT, blockOffset + 4);
      long nibbleOffset = blockOffset + 8;
      int outIdx = dstOffset + block * BLOCK_SIZE;
      for (int i = 0; i < HALF_BLOCK; i++) {
        byte packed = src.get(ValueLayout.JAVA_BYTE, nibbleOffset + i);
        int lowFifth = ((high >>> i) << 4) & 0x10;
        int highFifth = (high >>> (i + 12)) & 0x10;
        int low = (packed & 0x0F) | lowFifth;
        int highNibble = ((packed >>> 4) & 0x0F) | highFifth;
        dst[outIdx + i] = low * scale + minimum;
        dst[outIdx + i + HALF_BLOCK] = highNibble * scale + minimum;
      }
    }
  }
}
