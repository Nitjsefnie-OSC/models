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

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.SplittableRandom;

public final class Q51WidthProbe {
  private static volatile float sink;

  private Q51WidthProbe() {}

  public static void main(String[] args) {
    int blocks = 128, count = blocks * 32;
    byte[] bytes = new byte[7 + blocks * 24];
    ByteBuffer packed = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    var random = new SplittableRandom(713891);
    float[] expected = new float[count + 6];
    Arrays.fill(expected, 99f);
    for (int b = 0; b < blocks; b++) {
      int pos = 7 + b * 24;
      short d = (short) (random.nextInt(0x7c00) | (random.nextBoolean() ? 0x8000 : 0));
      short m = (short) (random.nextInt(0x7c00) | (random.nextBoolean() ? 0x8000 : 0));
      packed.putShort(pos, d);
      packed.putShort(pos + 2, m);
      int high = random.nextInt();
      packed.putInt(pos + 4, high);
      for (int n = 0; n < 16; n++) {
        int v = random.nextInt(256);
        bytes[pos + 8 + n] = (byte) v;
        expected[3 + b * 32 + n] =
            ((v & 15) | (((high >>> n) & 1) << 4)) * Float16.toFloat(d) + Float16.toFloat(m);
        expected[3 + b * 32 + 16 + n] =
            ((v >>> 4) | (((high >>> (n + 16)) & 1) << 4)) * Float16.toFloat(d)
                + Float16.toFloat(m);
      }
    }
    var decoder = new Q5_1Dequantizer();
    float[] out = new float[expected.length];
    Arrays.fill(out, 99f);
    try (var arena = Arena.ofConfined()) {
      var src = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
      decoder.dequantize(src, 7, out, 3, count);
      verify(out, expected);
      for (int i = 0; i < 10000; i++) {
        decoder.dequantize(src, 7, out, 3, count);
        sink = out[i % count + 3];
      }
      verify(out, expected);
      System.out.println("EXACT values=" + count);
    }
  }

  static void verify(float[] a, float[] b) {
    for (int i = 0; i < a.length; i++)
      if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i]))
        throw new AssertionError(i + " " + a[i] + " " + b[i]);
  }
}
