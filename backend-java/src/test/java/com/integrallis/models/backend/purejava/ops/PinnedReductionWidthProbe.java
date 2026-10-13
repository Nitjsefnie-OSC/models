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

import java.util.ArrayList;
import java.util.Random;
import jdk.incubator.vector.FloatVector;

/** Child-JVM correctness probe: physical widths must preserve the original eight logical lanes. */
public final class PinnedReductionWidthProbe {
  private static volatile int sink;

  private record Case(float[] a, float[] b, int offset, int size, int expected) {}

  private PinnedReductionWidthProbe() {}

  public static void main(String[] args) {
    var cases = new ArrayList<Case>();
    Random random = new Random(731994);
    for (int size :
        new int[] {0, 1, 7, 8, 16, 17, 23, 24, 31, 32, 33, 63, 64, 65, 127, 896, 1536, 4864}) {
      for (int offset : new int[] {0, 3}) {
        for (int seed = 0; seed < 4; seed++) {
          float[] a = new float[size + offset + 5];
          float[] b = new float[size + offset + 5];
          for (int n = 0; n < a.length; n++) {
            a[n] = Math.scalb(random.nextFloat() - 0.5f, random.nextInt(61) - 30);
            b[n] = Math.scalb(random.nextFloat() - 0.5f, random.nextInt(61) - 30);
          }
          cases.add(
              new Case(
                  a,
                  b,
                  offset,
                  size,
                  Float.floatToRawIntBits(reference(a, offset, b, offset, size))));
        }
      }
    }
    float[] special = {
      0.0f,
      -0.0f,
      Float.MIN_VALUE,
      -Float.MIN_VALUE,
      Float.MIN_NORMAL,
      -Float.MIN_NORMAL,
      Float.POSITIVE_INFINITY,
      Float.NaN
    };
    for (float value : special) {
      float[] a = new float[40];
      float[] b = new float[40];
      java.util.Arrays.fill(a, value);
      java.util.Arrays.fill(b, 0.75f);
      cases.add(new Case(a, b, 3, 33, Float.floatToRawIntBits(reference(a, 3, b, 3, 33))));
    }
    for (Case item : cases) verify(item);
    for (int repeat = 0; repeat < 30000; repeat++) verify(cases.get(repeat % cases.size()));
    for (Case item : cases) verify(item);
    System.out.println(
        "EXACT cases="
            + cases.size()
            + " preferredBits="
            + FloatVector.SPECIES_PREFERRED.vectorBitSize()
            + " checksum="
            + sink);
  }

  private static void verify(Case item) {
    int actual =
        Float.floatToRawIntBits(
            PinnedReduction.dot(item.a, item.offset, item.b, item.offset, item.size));
    if (actual != item.expected
        && !(Float.isNaN(Float.intBitsToFloat(actual))
            && Float.isNaN(Float.intBitsToFloat(item.expected))))
      throw new AssertionError(
          "size="
              + item.size
              + " offset="
              + item.offset
              + " expected="
              + item.expected
              + " actual="
              + actual);
    sink = actual;
  }

  /** Independent scalar transcription of the published four-accumulator/eight-lane contract. */
  private static float reference(float[] a, int ao, float[] b, int bo, int length) {
    float result = 0.0f;
    int i = 0;
    if (length > 16) {
      float[][] sums = new float[4][8];
      int limit = length - length % 8;
      for (; i < limit - 24; i += 32) {
        for (int accumulator = 0; accumulator < 4; accumulator++) {
          for (int lane = 0; lane < 8; lane++) {
            int at = i + accumulator * 8 + lane;
            sums[accumulator][lane] = Math.fma(a[ao + at], b[bo + at], sums[accumulator][lane]);
          }
        }
      }
      for (; i < limit; i += 8) {
        for (int lane = 0; lane < 8; lane++)
          sums[0][lane] = Math.fma(a[ao + i + lane], b[bo + i + lane], sums[0][lane]);
      }
      float[] lanes = new float[8];
      for (int lane = 0; lane < 8; lane++)
        lanes[lane] = (sums[0][lane] + sums[1][lane]) + (sums[2][lane] + sums[3][lane]);
      result +=
          ((lanes[4] + lanes[0]) + (lanes[6] + lanes[2]))
              + ((lanes[5] + lanes[1]) + (lanes[7] + lanes[3]));
    }
    for (; i < length; i++) result = Math.fma(a[ao + i], b[bo + i], result);
    return result;
  }
}
