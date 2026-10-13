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
package com.integrallis.models.backend.purejava.ops;

import com.integrallis.models.backend.purejava.quant.Q5_1Dequantizer;
import com.integrallis.models.backend.purejava.quant.TernaryDequantizer;
import com.sun.management.ThreadMXBean;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.Random;
import jdk.incubator.vector.FloatVector;

/** Fresh-process guard against allocating unsupported vector operations on the inference path. */
public final class NativeWidthAllocationProbe {
  private static final int SIZE = 4096;
  private static volatile float sink;
  private final float[] input = new float[SIZE];
  private final float[] other = new float[SIZE];
  private final float[] scores = new float[5 * 64];
  private final float[] probabilities = new float[515];
  private final byte[] quants = new byte[SIZE];
  private final float[] scales = new float[SIZE / 32];
  private final MemorySegment ptq = MemorySegment.ofArray(new byte[SIZE / 128 * 28]);
  private final MemorySegment q51 = MemorySegment.ofArray(new byte[SIZE / 32 * 24]);
  private final Q5_1Dequantizer decoder = new Q5_1Dequantizer();

  private NativeWidthAllocationProbe() {
    Random random = new Random(193);
    for (int i = 0; i < SIZE; i++) {
      input[i] = random.nextFloat() - .5f;
      other[i] = random.nextFloat() - .5f;
    }
    random.nextBytes(quants);
    java.util.Arrays.fill(scales, .125f);
    var half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    for (long i = 0; i < ptq.byteSize(); i++)
      ptq.set(ValueLayout.JAVA_BYTE, i, (byte) random.nextInt(243));
    for (int i = 0; i < SIZE / 128; i++) ptq.set(half, i * 28L + 26, Float.floatToFloat16(.125f));
    for (int i = 0; i < SIZE / 32; i++) {
      q51.set(half, i * 24L, Float.floatToFloat16(.125f));
      q51.set(half, i * 24L + 2, Float.floatToFloat16(-.5f));
    }
  }

  private void invoke(int kernel, int iteration) {
    input[0] = (iteration % 31 - 15) * .03125f;
    switch (kernel) {
      case 0 -> sink = PinnedReduction.dot(input, 0, other, 0, SIZE);
      case 1 -> {
        q51.set(ValueLayout.JAVA_BYTE, 8, (byte) iteration);
        decoder.dequantize(q51, 0, other, 0, SIZE);
        sink = other[iteration % 32];
      }
      case 2 -> {
        GroupedQueryAttentionKernel.scoreGroup(
            input, 0, 64, 5, other, 0, 64, 64, 64, .125f, scores, 0, 64);
        sink = scores[iteration % scores.length];
      }
      case 3 -> {
        System.arraycopy(input, 0, probabilities, 0, probabilities.length);
        GroupedQueryAttentionKernel.softmax(probabilities, 0, probabilities.length);
        sink = probabilities[iteration % probabilities.length];
      }
      case 4 -> {
        quants[0] = (byte) (iteration % 255 - 127);
        sink = TernaryDequantizer.dotProductPtq1_0Q8_0(ptq, 0, quants, scales, 0, SIZE);
      }
      case 5 -> sink = TernaryDequantizer.dotProductPtq1_0(ptq, 0, input, 0, SIZE);
      default -> throw new AssertionError(kernel);
    }
  }

  public static void main(String[] args) {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported())
      throw new AssertionError("Thread allocation measurement is required");
    bean.setThreadAllocatedMemoryEnabled(true);
    NativeWidthAllocationProbe probe = new NativeWidthAllocationProbe();
    long thread = Thread.currentThread().threadId();
    for (int kernel = 0; kernel < 6; kernel++) {
      for (int i = 0; i < 20_000; i++) probe.invoke(kernel, i);
      long start = bean.getThreadAllocatedBytes(thread);
      for (int i = 0; i < 2_000; i++) probe.invoke(kernel, i);
      long allocated = bean.getThreadAllocatedBytes(thread) - start;
      // This is a coarse regression guard, not a zero-allocation or performance qualification.
      // The broken unsupported-width paths allocated tens/hundreds of KB per invocation.
      if (allocated > 2_000L * 64)
        throw new AssertionError("kernel=" + kernel + " bytesPerCall=" + allocated / 2_000.0);
      if (!Float.isFinite(sink)) throw new AssertionError("Nonfinite output for kernel " + kernel);
      System.out.println("kernel=" + kernel + " bytesPerCall=" + allocated / 2_000.0);
    }
    System.out.println(
        "BOUNDED kernels=6 preferredBits=" + FloatVector.SPECIES_PREFERRED.vectorBitSize());
  }
}
