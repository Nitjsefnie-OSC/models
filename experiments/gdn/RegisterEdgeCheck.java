package com.integrallis.models.backend.purejava.qwen35;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Nonuniform tails, repeated keys, zero vectors, exact gates, and strongly decayed state. */
public final class RegisterEdgeCheck {
  public static void main(String[] args) {
    int cases = 0;
    for (int kd : new int[] {1, 7, 13, 128})
      for (int vd : new int[] {1, 3, 9, 127, 128})
        for (int tokens : new int[] {1, 8, 19})
          for (int variant = 0; variant < 3; variant++) {
            int kh = 2, vh = 6;
            var r = new SplittableRandom(1100L + variant);
            float[] q = GdnProof.random(r, tokens * kh * kd, 1),
                k = GdnProof.random(r, tokens * kh * kd, 1),
                v = GdnProof.random(r, tokens * vh * vd, 100),
                g = new float[tokens * vh],
                b = new float[tokens * vh];
            for (int n = 0; n < g.length; n++) {
              g[n] = variant == 2 ? -80 : -(n % 5) * .13f;
              b[n] = (n % 3) * .5f;
            }
            if (variant == 1) {
              Arrays.fill(q, -0f);
              Arrays.fill(k, 0);
            }
            var i =
                new GdnProof.Input(
                    q, k, v, g, b, GdnProof.random(r, vh * kd * vd, 5), tokens, kh, vh, kd, vd);
            var expected = GdnProof.run("java", i);
            for (String arm :
                new String[] {
                  "java-register",
                  "java-register-parallel",
                  "java-tiled",
                  "java-tiled-parallel",
                  "java-tiled-stage"
                }) {
              var actual = GdnProof.run(arm, i);
              if (!Arrays.equals(expected.output(), actual.output())
                  || !Arrays.equals(expected.state(), actual.state()))
                throw new AssertionError(
                    arm
                        + " kd="
                        + kd
                        + " vd="
                        + vd
                        + " t="
                        + tokens
                        + " variant="
                        + variant
                        + " "
                        + GdnProof.diff(expected.output(), actual.output()));
            }
            cases++;
          }
    System.out.println("PASS " + cases + " cases, serial and parallel exact bits");
  }
}
