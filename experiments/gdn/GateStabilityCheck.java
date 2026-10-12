package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.vectors.core.VectorUtil;
import java.util.Arrays;
import java.util.SplittableRandom;

/** Checks the published dependency's gate outputs before and after repeated execution. */
public final class GateStabilityCheck {
  public static void main(String[] args) {
    int n = 1024;
    SplittableRandom random = new SplittableRandom(4021);
    float[] sigmoid = GdnProof.random(random, n, 8);
    float[] softplus = GdnProof.random(random, n, 8);
    float[] bias = GdnProof.random(random, n, 2);
    float[] scale = GdnProof.random(random, n, 2);
    float[] actual = new float[n], scaled = new float[n];
    float[] firstSigmoid = null, firstScaled = null;
    int firstChange = -1;
    for (int repeat = 0; repeat < 20000; repeat++) {
      System.arraycopy(sigmoid, 0, actual, 0, n);
      VectorUtil.sigmoidAndScaledSoftplus(actual, 0, softplus, 0, bias, 0, scale, 0, scaled, 0, n);
      if (repeat == 0) {
        firstSigmoid = actual.clone();
        firstScaled = scaled.clone();
      } else if (firstChange < 0
          && (!Arrays.equals(firstSigmoid, actual) || !Arrays.equals(firstScaled, scaled))) {
        firstChange = repeat;
        System.out.println(
            "FIRST_CHANGE repeat="
                + repeat
                + " sigmoid="
                + GdnProof.diff(firstSigmoid, actual)
                + " scaled="
                + GdnProof.diff(firstScaled, scaled));
      }
    }
    System.out.println(
        "FINAL firstChange="
            + firstChange
            + " sigmoid="
            + GdnProof.diff(firstSigmoid, actual)
            + " scaled="
            + GdnProof.diff(firstScaled, scaled));
  }
}
